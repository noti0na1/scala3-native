package dotty.tools.dotc

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import scala.scalanative.build.Mode
import scala.scalanative.live.{ExtensionLinker => ToolsLinker, Manifest}
import scala.scalanative.nir.Global

import dotty.tools.dotc.config.SncDistribution
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.live.ExtensionLinker
import dotty.tools.dotc.quoted.{Interpreter, MacroInvokers, MacroLoader, MacroRegistry}
import dotty.tools.dotc.reporting.Message
import dotty.tools.dotc.util.{NativeFiles, SrcPos}
import dotty.tools.io.AbstractFile

/** Macro code from the class path, linked as extensions of this compiler
 *  (`snc-host`) and loaded into it.
 *
 *  When an expansion first needs code of a class path entry (a jar or a
 *  directory of TASTy and NIR, or the output directory for a class an
 *  earlier run of this compiler compiled), its entry table is generated from
 *  its NIR (`MacroGlue`) and linked with the code it reaches into a bundle,
 *  against this compiler's manifest. Code that does not link, such as code
 *  that needs a JDK API that javalib lacks, becomes stubs that throw when they
 *  run. The bundle is loaded, and its entries are registered with
 *  `MacroRegistry`.
 *
 *  Bundles are cached in `$XDG_CACHE_HOME/snc/macros` (`~/.cache/...`) under
 *  a hash of the entry's content, this compiler's identity (its manifest's
 *  hash, and its distribution's version or else its executable) and the
 *  linker's settings; the entries that a bundle took classes from are
 *  checked on use: their contents, and that no entry before one of them has
 *  one of the classes it gave. A link writes to a directory of its own and
 *  renames it into place, so concurrent compilers share the cache safely:
 *  the first rename wins.
 *
 *  A class is defined by one loaded extension only. A bundle that would
 *  define a class that a loaded one defines (code that two entries' macros
 *  both reach) is linked again against the loaded ones (a chain), and then only defines what they do not.
 */
final class MacroExtensions(host: ToolsLinker.Host, linker: HostExtensionLinker) extends MacroLoader:
  import MacroExtensions.*
  import MacroCache.{BundleName, MissingClassesFile, isMac}

  private val cache = new MacroCache(MacroCache.defaultDir)

  /** The extensions loaded so far, in load order. */
  private val loaded = mutable.ArrayBuffer.empty[CachedMacros]
  private val defined = mutable.Set.empty[String]
  /** Entries whose macro code is loaded, by path and content hash. */
  private val loadedRoots = mutable.Set.empty[(Path, String)]
  /** Entries whose macro code does not link or load, with the error. */
  private val failedRoots = mutable.Map.empty[(Path, String), Message]

  /** This compiler in cache keys: its manifest's hash, and the version of
   *  the distribution it comes in (`lib/snc-distribution.properties`), which
   *  an install of the same distribution keeps; for an `snc-host` that does
   *  not come from a distribution, its executable's size and modification
   *  time instead.
   */
  private lazy val hostId: String =
    val manifestHash = MacroCache.manifestHash(host.manifest)
    val binary = host.binary
    SncDistribution.find(Option(binary.getParent)).map(_.version).filter(_ != "unknown") match
      case Some(version) => s"$manifestHash distribution $version"
      case None => s"$manifestHash ${Files.size(binary)} ${Files.getLastModifiedTime(binary).toMillis}"

  /** The link mode: debug (no optimizer, `-O0`), unless `SNC_MACRO_MODE` is
   *  `release`, the host's mode with the optimizer.
   */
  private val release = sys.env.get("SNC_MACRO_MODE").contains("release")

  def load(owner: Symbol, pos: SrcPos)(using Context): Boolean =
    val start = System.nanoTime()
    entryOf(owner) match
      case None => false
      case Some(root) =>
        val nirFile = owner.binaryClassName.replace('.', '/') + ".nir"
        val rootHash = cache.contentHash(root)
        if loadedRoots.contains((root, rootHash)) then false
        else if failedRoots.contains((root, rootHash)) then
          throw new Interpreter.StopInterpretation(failedRoots((root, rootHash)), pos)
        else
          def noNir() =
            if !hasFile(root, nirFile) then
              throw new Interpreter.StopInterpretation(
                em"""Cannot expand this macro: ${owner.showLocated} has no compiled code in ${root.toString}.
                    |snc-host runs macro code compiled to NIR by Scala Native's compiler plugin: the NIR must be next to the TASTy""", pos)
          val outcome =
            try loadRoot(root, rootHash, noNir, pos)
            catch case e: Interpreter.StopInterpretation =>
              failedRoots((root, rootHash)) = e.msg
              log(f"$root\tfailed\t${(System.nanoTime() - start) / 1e6}%.1f ms")
              throw e
          loadedRoots += ((root, rootHash))
          log(f"$root\t$outcome\t${(System.nanoTime() - start) / 1e6}%.1f ms")
          true

  /** Loads the macro code of `root`, from the cache or linked now (after
   *  `check` accepts it); what it did.
   */
  private def loadRoot(root: Path, rootHash: String, check: () => Unit, pos: SrcPos)(using Context): String =
    val classPath = linkClassPath(root)
    val ingredients = Seq(CacheVersion, hostId, NativeFiles.toolchainId, if release then "release" else "debug", rootHash)
    val key0 = MacroCache.key(ingredients)
    var outcome = "cached"
    val unchained = cache.lookup(key0, depsValid(classPath)).getOrElse {
      check()
      outcome = "linked"
      link(key0, root, classPath, Nil, pos)
    }
    val chosen =
      if unchained.classes.exists(defined) then
        // Code that loaded extensions define: linked against them
        val chain = loaded.toList
        val key1 = MacroCache.key(ingredients ++ chain.map(_.key))
        outcome += ", chained"
        cache.lookup(key1, depsValid(classPath)).getOrElse {
          outcome += " and linked"
          link(key1, root, classPath, chain, pos)
        }
      else unchained
    register(chosen, root, pos)
    outcome

  /** Loads a bundle and registers its entries. */
  private def register(ext: CachedMacros, root: Path, pos: SrcPos)(using Context): Unit =
    if ext.keys.nonEmpty then
      val invokers =
        try
          val handle = linker.load(ext.bundle, global = true)
          handle.module(ext.glue).asInstanceOf[MacroInvokers]
        catch
          case e: ExtensionLinker.LoadError =>
            throw new Interpreter.StopInterpretation(
              em"Cannot expand this macro: the macro code of ${root.toString} does not load: ${e.getMessage}", pos)
      MacroRegistry.register("", ext.keys.mkString("\n"), invokers)
      loaded += ext
      defined ++= ext.classes

  /** Links the macro code of `root` against this compiler and the extensions
   *  of `chain`, into the cache entry `key`.
   */
  private def link(key: String, root: Path, classPath: Seq[Path], chain: List[CachedMacros], pos: SrcPos)(using Context): CachedMacros =
    val work = cache.newWorkDir(key)
    val glue = Global.Top(s"snc.macros.Glue_${key.take(20)}$$")
    val glueDir = work.resolve("glue")
    val generated =
      try MacroGlue.generate(root, glueDir, glue, MacroGlue.builtinStubs)
      catch case NonFatal(e) =>
        throw new Interpreter.StopInterpretation(
          em"Cannot expand this macro: the NIR of ${root.toString} cannot be read: ${e.toString}", pos)
    val classes =
      if generated.keys.isEmpty then Set.empty[String]
      else
        val logLines = mutable.ArrayBuffer.empty[String]
        val request = ToolsLinker.Request(
          host = host,
          roots = Seq(glueDir),
          workDir = work.resolve("link"),
          moduleName = BundleName,
          classPath = classPath,
          mode = if release then None else Some(Mode.debug),
          optimize = release,
          chain = chain.map(_.delta),
          delta = Some(work.resolve("link").resolve(BundleName + ".snm")),
          logger = HostExtensionLinker.logger { msg =>
            logLines += msg
            if verbose then Console.err.println(msg)
          },
          stubUnlinkable = true)
        try linker.linkRequest(request)
        catch case e: ExtensionLinker.LinkError =>
          logLines += ("error: " + e.getMessage)
          Files.write(work.resolve("link.log"), logLines.mkString("", "\n", "\n").getBytes(UTF_8))
          NativeFiles.deleteRecursively(work.resolve("link"))
          throw new Interpreter.StopInterpretation(
            em"""Cannot expand this macro: the macro code of ${root.toString} does not link against this compiler:
                |${e.getMessage}
                |The link's log is in ${work.resolve("link.log").toString}""", pos)
        Files.write(work.resolve("link.log"), logLines.mkString("", "\n", "\n").getBytes(UTF_8))
        val linkDir = work.resolve("link")
        Files.move(linkDir.resolve(bundleFile), work.resolve(bundleFile))
        Files.move(linkDir.resolve(BundleName + ".snm"), work.resolve(BundleName + ".snm"))
        val stubbed = linkDir.resolve("native").resolve("stubbed-methods.txt")
        if Files.isRegularFile(stubbed) then Files.move(stubbed, work.resolve("stubbed-methods.txt"))
        val missing = linkDir.resolve("native").resolve(MissingClassesFile)
        if Files.isRegularFile(missing) then Files.move(missing, work.resolve(MissingClassesFile))
        NativeFiles.deleteRecursively(linkDir)
        NativeFiles.deleteRecursively(glueDir)
        val delta = Manifest.read(work.resolve(BundleName + ".snm"))
        (delta.classes.iterator.map(_.name) ++ delta.traits.iterator.map(_.name)).toSet
    val deps = dependencies(root, classPath, classes)
    cache.publish(key, work, glue.id, generated.keys, classes, deps)

  /** The class path entries other than `root` and the host's that `classes`
   *  come from: each entry that was the first on `classPath` to have one of
   *  them, as the linker takes a class from the first entry that has it.
   */
  private def dependencies(root: Path, classPath: Seq[Path], classes: Set[String]): Seq[MacroDependency] =
    var wanted = classes.map(_.replace('.', '/') + ".nir")
    val deps = Seq.newBuilder[MacroDependency]
    var before = Map.empty[Path, String]
    for entry <- classPath if wanted.nonEmpty do
      val ours = entry == root || hostClassPath.contains(entry)
      val provided = cache.nirFiles(entry).filter(wanted)
      wanted --= provided
      if provided.nonEmpty && !ours then
        deps += MacroDependency(entry, cache.contentHash(entry), provided, before)
      if !ours then MacroCache.stamp(entry).foreach(stamp => before += entry -> stamp)
    deps.result()

  /** Whether a bundle linked with the classes of `deps` would be linked with
   *  the same classes from `classPath`: each dependency is on it with the same
   *  content, and no entry before it there has one of the classes it gave,
   *  unless the entry was before it at the link too, unchanged (so it did not
   *  have them then); and no entry has one of the `missing` classes, which no
   *  entry had at the link, so that code needing them became stubs.
   */
  private def depsValid(classPath: Seq[Path])(deps: Seq[MacroDependency], missing: Set[String]): Boolean =
    val missingFiles = missing.map(_.replace('.', '/') + ".nir")
    (missingFiles.isEmpty || !classPath.exists(entry =>
      !hostClassPath.contains(entry) && cache.nirFiles(entry).exists(missingFiles))) &&
    deps.forall { dep =>
      val at = classPath.indexOf(dep.path)
      at >= 0 && Files.exists(dep.path) && cache.contentHash(dep.path) == dep.hash &&
        classPath.take(at).forall { entry =>
          entry == classPath.head || hostClassPath.contains(entry) ||
            MacroCache.stamp(entry).exists(dep.before.get(entry).contains) ||
            !cache.nirFiles(entry).exists(dep.provides)
        }
    }

  private lazy val hostClassPath: Set[Path] = host.classPath.map(_.toAbsolutePath.normalize).toSet

  /** What an extension link reads classes from besides the host's own: the
   *  entry, then the compiler's class path and output directory.
   */
  private def linkClassPath(root: Path)(using Context): Seq[Path] =
    val cp = ctx.settings.classpath.value.split(File.pathSeparatorChar).toSeq.filter(_.nonEmpty)
      .map(p => Paths.get(p).toAbsolutePath.normalize)
    val out = Option(ctx.settings.outputDir.value.jpath).map(_.toAbsolutePath.normalize)
    (root +: (cp ++ out)).distinct.filter(Files.exists(_))

  /** The class path entry `owner`'s class came from: the jar or directory of
   *  its TASTy, or for a class compiled from source by an earlier run (the
   *  first run of a compilation with suspended units),
   *  the output directory.
   */
  private def entryOf(owner: Symbol)(using Context): Option[Path] =
    val file = owner.associatedFile
    if file == null then None
    else if file.ext.isScalaBinary then
      file.enclosing match
        case Some(archive) if archive.jpath != null && !archive.isDirectoryPlain =>
          Some(archive.jpath.toAbsolutePath.normalize)
        case _ =>
          Option(file.jpath).map { path =>
            // The directory the TASTy's package directories are in
            val depth = owner.topLevelClass.owner.fullName.toString match
              case "<empty>" | "" => 0
              case pkg => pkg.count(_ == '.') + 1
            var dir = path.toAbsolutePath.normalize.getParent
            for _ <- 0 until depth do dir = dir.getParent
            dir
          }
    else Option(ctx.settings.outputDir.value.jpath).map(_.toAbsolutePath.normalize)

  private def hasFile(root: Path, relative: String): Boolean =
    if Files.isDirectory(root) then Files.isRegularFile(root.resolve(relative))
    else NativeFiles.nirFiles(root).contains(relative)

  private def bundleFile = BundleName + (if isMac then ".bundle" else ".so")

  private val verbose = sys.env.contains("SNC_LINK_VERBOSE")

  private def log(line: String): Unit =
    sys.env.get("SNC_MACRO_LOG").foreach { file =>
      try Files.write(Paths.get(file), (line + "\n").getBytes(UTF_8),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
      catch case NonFatal(_) => ()
    }

end MacroExtensions

object MacroExtensions:
  /** Bumped when bundles or their entry tables change shape. */
  private val CacheVersion = "snc-macros 2"

  extension (file: AbstractFile)
    /** A directory of the file system, not an archive. */
    private def isDirectoryPlain: Boolean = file.jpath != null && Files.isDirectory(file.jpath)

/** The cache of macro bundles. `<dir>/ext/<key>/<variant>/` holds a bundle,
 *  its delta, `glue.txt` (the glue module, then the entries' keys),
 *  `classes.txt` (what it defines), `deps.txt` (the other class path entries
 *  it took classes from, `MacroDependency`; a variant per set of entries and
 *  their contents), the stubs the link made, `missing-classes.txt` (the
 *  classes no entry had, which code of the bundle needs: the variant is stale
 *  once an entry has one), and `done`; `<dir>/sums/` the
 *  content hashes of jars by path, size and time; `<dir>/tmp/` links in
 *  progress, each renamed into place when it is complete.
 */
final class MacroCache(val dir: Path):
  import MacroCache.*

  /** The content hash of a jar (remembered by path, size and modification
   *  time, also in `sums/`) or of a directory's NIR files (computed each
   *  time: directories change between runs).
   */
  val contentHash = new NativeFiles.ContentHashes(Some(dir.resolve("sums")))

  private val nirFileSets = mutable.HashMap.empty[(Path, String), Set[String]]

  /** The `.nir` files of a class path entry (`NativeFiles.nirFiles`), those
   *  of a jar remembered by its size and modification time; none for an
   *  entry that does not exist.
   */
  def nirFiles(entry: Path): Set[String] =
    if Files.isDirectory(entry) then NativeFiles.nirFiles(entry)
    else stamp(entry).fold(Set.empty[String])(s => nirFileSets.getOrElseUpdate((entry, s), NativeFiles.nirFiles(entry)))

  /** A cached bundle of `key` whose dependencies `valid` accepts: one of the
   *  variants under `ext/<key>/`, one per set of dependencies it was linked
   *  with.
   */
  def lookup(key: String, valid: (Seq[MacroDependency], Set[String]) => Boolean): Option[CachedMacros] =
    val entry = dir.resolve("ext").resolve(key)
    if !Files.isDirectory(entry) then None
    else
      val variants =
        val stream = Files.list(entry)
        try stream.iterator.asScala.toList.sortBy(_.getFileName.toString)
        finally stream.close()
      variants.iterator.flatMap(read(key, _)).find((_, deps, missing) => valid(deps, missing)).map(_._1)

  private def read(key: String, variant: Path): Option[(CachedMacros, Seq[MacroDependency], Set[String])] =
    if !NativeFiles.isComplete(variant) then None
    else
      try
        val glue = Files.readAllLines(variant.resolve("glue.txt"), UTF_8).asScala.toSeq
        val classes = Files.readAllLines(variant.resolve("classes.txt"), UTF_8).asScala.filter(_.nonEmpty).toSet
        val deps = MacroDependency.read(Files.readAllLines(variant.resolve("deps.txt"), UTF_8).asScala.toList)
        val missingList = variant.resolve(MissingClassesFile)
        val missing =
          if Files.isRegularFile(missingList) then Files.readAllLines(missingList, UTF_8).asScala.filter(_.nonEmpty).toSet
          else Set.empty[String]
        Some((CachedMacros(key, variant, glue.head, glue.tail.filter(_.nonEmpty), classes), deps, missing))
      catch case NonFatal(_) => None

  /** A new directory for a link of `key`. */
  def newWorkDir(key: String): Path = NativeFiles.newWorkDir(dir, key.take(20) + ".")

  /** Puts a finished link in place as the variant of `key` for `deps`, with
   *  one rename: the cached entry, which is another compiler's if it was
   *  first.
   */
  def publish(key: String, work: Path, glue: String, keys: Seq[String], classes: Set[String],
      deps: Seq[MacroDependency]): CachedMacros =
    Files.write(work.resolve("glue.txt"), (glue +: keys).mkString("", "\n", "\n").getBytes(UTF_8))
    Files.write(work.resolve("classes.txt"), classes.toSeq.sorted.mkString("", "\n", "\n").getBytes(UTF_8))
    Files.write(work.resolve("deps.txt"), MacroDependency.lines(deps).mkString("", "\n", "\n").getBytes(UTF_8))
    val entry = Files.createDirectories(dir.resolve("ext").resolve(key))
    val variant = entry.resolve(NativeFiles.key(deps.map(d => s"${d.hash} ${d.path}")).take(16))
    // Another compiler may have put the same link in place first
    NativeFiles.publish(work, variant)
    read(key, variant).map(_._1).getOrElse(throw new java.io.IOException(s"cannot put $work in place as $variant"))

end MacroCache

/** A class path entry that a bundle took classes from: its content hash, the
 *  NIR files of the classes it was the first entry to have (`provides`), and
 *  the jars before it on the class path then, with their stamps
 *  (`MacroCache.stamp`), which did not have them.
 */
final case class MacroDependency(path: Path, hash: String, provides: Set[String], before: Map[Path, String])

object MacroDependency:
  /** `deps.txt`: a line `<hash> <path>` per dependency, then lines
   *  `  provides <NIR file>` and `  before <stamp> <path>`.
   */
  def lines(deps: Seq[MacroDependency]): Seq[String] =
    deps.flatMap { d =>
      s"${d.hash} ${d.path}" +: (d.provides.toSeq.sorted.map("  provides " + _) ++
        d.before.toSeq.sortBy(_._1.toString).map((p, stamp) => s"  before $stamp $p"))
    }

  def read(lines: List[String]): Seq[MacroDependency] =
    val deps = Seq.newBuilder[MacroDependency]
    var rest = lines.filter(_.nonEmpty)
    while rest.nonEmpty do
      val head = rest.head
      val i = head.indexOf(' ')
      val (details, next) = rest.tail.span(_.startsWith("  "))
      val provides = details.collect { case s"  provides $file" => file }.toSet
      val before = details.collect { case s"  before $stamp $path" => Paths.get(path) -> stamp }.toMap
      deps += MacroDependency(Paths.get(head.substring(i + 1)), head.substring(0, i), provides, before)
      rest = next
    deps.result()

/** A bundle in the macro cache: its glue module, the keys of its entries and
 *  the classes and traits it defines.
 */
final case class CachedMacros(key: String, dir: Path, glue: String, keys: Seq[String], classes: Set[String]):
  def bundle: Path = dir.resolve(MacroCache.BundleName + (if MacroCache.isMac then ".bundle" else ".so"))
  def delta: Path = dir.resolve(MacroCache.BundleName + ".snm")

object MacroCache:
  val BundleName = "macros"
  /** What the extension linker writes in its work directory: the classes that
   *  the stubs it made need and that no class path entry has.
   */
  val MissingClassesFile = "missing-classes.txt"
  val isMac = System.getProperty("os.name", "").toLowerCase.contains("mac")

  /** `$XDG_CACHE_HOME/snc/macros`, or `~/.cache/snc/macros`. */
  def defaultDir: Path =
    NativeFiles.cacheDir("macros").getOrElse(Paths.get(System.getProperty("user.home"), ".cache", "snc", "macros"))

  def key(ingredients: Seq[String]): String = NativeFiles.key(ingredients)

  /** A jar's size and modification time; none for a directory, whose files
   *  change without it.
   */
  def stamp(entry: Path): Option[String] =
    if Files.isRegularFile(entry) then Some(s"${Files.size(entry)}:${Files.getLastModifiedTime(entry).toMillis}")
    else None

  /** The hash in a manifest's header: magic, version, then 40 hex digits. */
  def manifestHash(manifest: Path): String =
    val in = Files.newInputStream(manifest)
    try new String(in.readNBytes(48), UTF_8).drop(8)
    finally in.close()

end MacroCache
