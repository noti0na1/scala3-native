package dotty.tools.dotc

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Try

import scala.scalanative.build.{BuildException, Logger, Mode}
import scala.scalanative.linker.LinkingException
import scala.scalanative.live.{ExtensionLinker => ToolsLinker}
import scala.scalanative.runtime.{Extension, ExtensionLoadError}

import dotty.tools.dotc.live.ExtensionLinker

/** `snc`'s extension linker: the toolchain's extension link inside `snc`,
 *  against `snc`'s own manifest, and the runtime's loader. One link at a time.
 */
final class HostExtensionLinker(val host: ToolsLinker.Host) extends ExtensionLinker:
  import ExtensionLinker.*

  private lazy val linker = new ToolsLinker(ToolsLinker.Toolchain.discover())
  private val loaded = mutable.Map.empty[Path, Loaded]

  def link(roots: Seq[Path], workDir: Path, name: String, options: Options): Path =
    val request = ToolsLinker.Request(
      host = host,
      roots = roots.map(_.toAbsolutePath),
      workDir = workDir.toAbsolutePath,
      moduleName = name,
      classPath = options.classPath.map(_.toAbsolutePath),
      mode = if options.debug then Some(Mode.debug) else None,
      optimize = !options.debug,
      logger = HostExtensionLinker.logger(options.log))
    linkRequest(request)

  /** Links an extension as the toolchain's request says: also with a chain,
   *  a delta, or stubs for code that does not link (the macro executor's
   *  links, `MacroExtensions`).
   */
  def linkRequest(request: ToolsLinker.Request): Path =
    try linker.link(request)
    catch
      case e: BuildException => throw new LinkError(e.getMessage, e)
      case e: LinkingException => throw new LinkError(e.getMessage, e)

  def request(args: Seq[String]): Response =
    val response = linker.link(args)
    Response(response.artifact, response.log)

  def load(bundle: Path): Loaded = load(bundle, global = false)

  /** Loads a bundle as `load` does; with `global`, extensions loaded after it
   *  bind to its symbols (those linked with its delta in their chain).
   */
  def load(bundle: Path, global: Boolean): Loaded =
    val path = bundle.toAbsolutePath.normalize
    loaded.getOrElseUpdate(path, {
      val ext =
        try Extension.load(path.toString, global)
        catch case e: ExtensionLoadError => throw new LoadError(e.getMessage)
      new Loaded:
        def path: Path = bundle
        def module(name: String): AnyRef = ext.module(name)
    })

object HostExtensionLinker:

  /** This `snc` as a host, if it is one: its manifest `<executable>.snm`, and
   *  the NIR it was linked from, listed one entry per line in
   *  `<executable>.classpath` or given by `SNC_HOST_CLASSPATH`
   *  (colon-separated). Both files are looked for next to the executable,
   *  then in `../lib`, as in a distribution's bundle (`bin/snc`,
   *  `lib/snc.snm`, `lib/snc.classpath`). Relative entries of the list are
   *  relative to its directory.
   */
  def find(): Option[ToolsLinker.Host] =
    for
      exe <- SncExecutable.executablePath().flatMap(p => Try(Paths.get(p).toRealPath()).toOption)
      manifest <- besideExecutable(exe, ".snm")
    yield
      val classPath = sys.env.get("SNC_HOST_CLASSPATH") match
        case Some(cp) => cp.split(java.io.File.pathSeparatorChar).toSeq.filter(_.nonEmpty).map(Paths.get(_))
        case None =>
          besideExecutable(exe, ".classpath") match
            case Some(listed) =>
              Files.readAllLines(listed, UTF_8).asScala.toSeq.map(_.trim).filter(_.nonEmpty)
                .map(entry => listed.getParent.resolve(entry).normalize())
            case None => Nil
      ToolsLinker.Host(manifest, exe, classPath)

  /** `<executable><suffix>` next to the executable, or in `../lib`. */
  private def besideExecutable(exe: Path, suffix: String): Option[Path] =
    candidates(exe, suffix).find(Files.isRegularFile(_))

  private def candidates(exe: Path, suffix: String): List[Path] =
    val name = exe.getFileName.toString + suffix
    List(exe.resolveSibling(name), exe.getParent.resolve("..").resolve("lib").resolve(name).normalize())

  /** Why `find` finds no host: the manifests it looked for. */
  def notAHost: String =
    val exe = SncExecutable.executablePath().flatMap(p => Try(Paths.get(p).toRealPath()).toOption)
    val where = exe.fold("<executable>.snm next to the executable or in ../lib")(e =>
      candidates(e, ".snm").mkString(" or "))
    s"this snc is not a live-execution host (no manifest: looked for $where)"

  def logger(log: String => Unit): Logger =
    Logger(
      traceFn = _ => (),
      debugFn = _ => (),
      infoFn = log,
      warnFn = msg => log("warning: " + msg),
      errorFn = msg => log("error: " + msg))
