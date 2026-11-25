package dotty.tools
package repl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import scala.scalanative.libc.signal
import scala.scalanative.posix.{fcntl, poll, spawn, unistd}
import scala.scalanative.posix.pollOps.*
import scala.scalanative.posix.sys.wait as pwait
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import dotc.core.Contexts.*
import dotc.live.ExtensionLinker
import dotc.util.NativeFiles
import dotty.tools.io.{AbstractFile, Directory, PlainDirectory}
import dotty.vendored.fansi

/** The native REPL's evaluator backend (see README.md, "REPL"):
 *  lines run in a second, long-lived process.
 *
 *  - The evaluator, `snrepl-host`, is a Scala Native program linked once per
 *    class path as a live-execution host whose boundary is everything the
 *    class path defines (`Host`). It is multithreaded, keeps the session's
 *    values and loads each line as an extension
 *    (`dotty.tools.repl.runtime.Evaluator`).
 *  - Each line's NIR, with that of the definitions-only lines before it
 *    (which `run` does not link), is linked as an extension against the
 *    host's manifest and the deltas of the earlier lines, by the extension
 *    linker built into `snc-host` (`dotty.tools.dotc.live.ExtensionLinker`).
 *    As `snc` is single-threaded, the linker reads the host's manifest and
 *    the class path in steps while the line editor waits for keys (`idle`).
 *    A line's classes can extend and use the earlier lines' classes, and its
 *    code polls the evaluator's interrupt flag.
 *  - The evaluator loads the bundle, runs the line on a thread of its own
 *    (`rs$runner$N.init()`), renders its values with `$show` and answers.
 *    Values stay: a later line does not run an earlier one again.
 *  - Ctrl-C asks the evaluator to interrupt the line; if it does not stop
 *    within 3 s, or on a second Ctrl-C, the evaluator is killed. A crash
 *    (a signal, an exit) is reported as the line's error. In both cases a
 *    new evaluator loads the session's bundles and runs the earlier lines
 *    again, with their output discarded.
 *  - Jars and directories with NIR added to the class path (`:dep`, `:jar`)
 *    are linked whole as an extension into the chain, with stubs for code
 *    that does not link, and loaded into the evaluator (`Libraries`): later
 *    lines link against their delta instead of copying their classes. The
 *    bundle is cached by the content of the jars, the host and the library
 *    bundles before it.
 *
 *  - Driven (`snc repl --driven`, `DrivenRepl`): the program that drives
 *    this REPL is the evaluator, linked as a live-execution host and given
 *    with `--evaluator-host`; the requests and responses go through the
 *    driver's channel, and nothing restarts it.
 *
 *  @param capture whether the lines' output goes through this process (the
 *                 transcript runner); else the evaluator writes to the
 *                 terminal itself
 */
class EvaluatorBackend(config: NativeReplConfig, capture: Boolean) extends NativeReplBackend:
  import EvaluatorBackend.*

  private val sessionDir = Files.createTempDirectory(Files.createDirectories(config.workDir.resolve("sessions")), "session")
  private val classesDir = Files.createDirectories(sessionDir.resolve("classes"))
  private var outputDirs = Vector(classesDir)
  private var extraClasspath = Vector.empty[Path]

  /** The .nir files linked so far, with their sizes and modification times. */
  private val seen = mutable.HashMap.empty[Path, (Long, Long)]

  /** The links of lines so far, which number their directories and modules:
   *  each link has its own, also after one whose bundle was not loaded.
   */
  private var links = 0

  /** A linked and loaded group of lines. `runner` is the runner module of the
   *  line that ran, once it has run without an error; otherwise it is empty,
   *  and a replay only loads the bundle. A bundle of libraries has no runner
   *  and its cache key as `library`.
   */
  private final case class Batch(bundle: Path, delta: Path, var runner: String, library: Option[String] = None)
  private val batches = mutable.ArrayBuffer.empty[Batch]

  /** Jars and directories with NIR added to the class path (`:dep`, `:jar`)
   *  that the evaluator has not loaded yet.
   */
  private var pendingLibraries = Vector.empty[Path]

  private var evaluator: EvaluatorChannel | Null = null

  /** How the last line that reached this backend ended: `OK`, or the status
   *  of its failure (`ERROR` without an evaluator, `LINKERROR`, or the
   *  evaluator's: `FAILED`, `INTERRUPTED`, `LOADERROR`, `KILLED`, `CRASHED`);
   *  none if no line reached it since `clearOutcome`.
   */
  private var outcome: Option[String] = None
  def lastOutcome: Option[String] = outcome
  def clearOutcome(): Unit = outcome = None

  /** The extension linker built into this `snc` (`snc-host`), which links
   *  the lines; `NativeRepl` starts no evaluator backend without one.
   */
  private val linker: ExtensionLinker = ExtensionLinker.get.getOrElse(throw new IllegalStateException(NoLinkerMessage))

  /** The linker's remaining warm-up steps, taken by `idle`. */
  private var warmUp: Iterator[String] = Iterator.empty

  // At startup, while the user types the first line: if the host is linked,
  // start the evaluator and warm up the linker; else start the host's link
  // in the background, and `idle` does the rest once the link has ended
  Interrupts.install()
  Host.cached(config) match
    case Some(host) => prepare(host)
    case None => Host.startBuild(config)

  /** Starts the evaluator and has the linker read the host's manifest and the
   *  class path (a link of nothing), without waiting for either.
   */
  private def prepare(host: Host): Unit =
    try
      if evaluator == null then
        evaluator = startEvaluator(host, awaitReady = false)
      val empty = Files.createDirectories(sessionDir.resolve("warmup").resolve("nir"))
      val args =
        List("--extension", "--host", host.manifest.toString, "--host-binary", host.binary.toString,
          "--ext", empty.toString, "--base-dir", sessionDir.resolve("warmup").resolve("link").toString,
          "--module", "warmup", "--no-optimize")
        ++ (outputDirs ++ config.linkClasspath).map(_.toString)
      // This process is single-threaded: the linker warms up in steps, while
      // the line editor waits for keys
      warmUp = linker.prepare(args ++ LinkerOptions)
    catch case NonFatal(_) => ()

  /** Takes steps of the linker's warm-up until this call has lasted
   *  `WarmUpMillis`. A step runs to its end, so a key typed during one waits
   *  for it. When `SNC_REPL_WARMUP_LOG` names a file, each step's time is
   *  appended to it.
   */
  private def warmUpSteps(): Unit =
    val start = System.nanoTime()
    while warmUp.hasNext && System.nanoTime() - start < WarmUpMillis * 1000000L do
      val stepStart = System.nanoTime()
      val step =
        try warmUp.next()
        catch case NonFatal(e) =>
          warmUp = Iterator.empty
          s"failed: $e"
      sys.env.get("SNC_REPL_WARMUP_LOG").foreach: file =>
        val line = f"$step ${(System.nanoTime() - stepStart) / 1e6}%.1f ms\n"
        Files.write(Paths.get(file), line.getBytes(UTF_8),
          java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)

  /** Links as `snlink --extension` with `args` would, in this process. */
  private def request(args: List[String]): LinkResponse =
    val r = linker.request(args ++ LinkerOptions)
    LinkResponse(r.artifact.map(_.toString), r.log)

  /** Called by the line editor while it waits for a key: once the host's
   *  background link has ended, starts the evaluator, then warms up the
   *  linker, so that the first line that runs does not wait for them.
   */
  def idle(): Unit =
    if evaluator == null then
      Host.poll(config) match
        case Some(Right(host)) => prepare(host)
        case _ =>
    else warmUpSteps()

  private val compiler = new NativeReplCompiler(evaluator = true)

  def newCompiler(): ReplCompiler = compiler

  def defaultOutputDir(): AbstractFile = new PlainDirectory(Directory(classesDir))

  def addToClasspath(entries: Seq[Path])(using Context): Unit =
    extraClasspath ++= entries
    val onHost = config.linkClasspath.map(_.toAbsolutePath.normalize).toSet
    pendingLibraries ++= entries.map(_.toAbsolutePath.normalize)
      .filter(e => !onHost(e) && !pendingLibraries.contains(e) && Libraries.hasNir(e))
    // Linked and loaded now if the host is ready, so that the time and any
    // error show here; else before the next line that runs
    if pendingLibraries.nonEmpty then
      try
        Host.poll(config) match
          case Some(Right(host)) => loadLibraries(host, None).foreach(println)
          case _ =>
      catch case NonFatal(e) => println(s"Loading the jars into the evaluator failed: $e")

  override def close(): Unit =
    val e = evaluator
    if e != null then e.stop()
    evaluator = null
    NativeFiles.deleteRecursively(sessionDir)

  def run(line: ReplLine)(using Context): ReplResult =
    ctx.settings.outputDir.value.jpath match
      case null =>
      case path => if !outputDirs.contains(path) then outputDirs :+= path
    // A line whose wrapper's initialization does nothing defines only
    // classes, objects, types and methods: its NIR is linked with the next
    // line that runs
    if line.values.isEmpty && !compiler.initializes(line.index) then
      outcome = Some("OK")
      return ReplResult.Values(Nil)
    Interrupts.clear()
    val timing = new Timing(line.index)
    val result =
      try
        Host.await(config) match
          case Left(error) =>
            outcome = Some("ERROR")
            ReplResult.Failed(fansi.Str(error))
          case Right(host) =>
            loadLibraries(host, Some(timing)).foreach(println)
            link(line, host, timing) match
              case Left(error) =>
                outcome = Some("LINKERROR")
                ReplResult.Failed(fansi.Str(error))
              case Right((batch, files)) =>
                val result =
                  if Interrupts.pending then
                    outcome = Some("INTERRUPTED")
                    ReplResult.Failed(fansi.Str("Interrupted."))
                  else evaluate(line, host, batch, timing)
                // Later lines chain against a bundle that the evaluator
                // loaded, so its files are not linked again; a bundle that it
                // did not load is dropped, and the next line links them again
                if batches.lastOption.exists(_ eq batch) then seen ++= files
                else
                  skipFilesOf(line, files)
                  NativeFiles.deleteRecursively(batch.delta.getParent)
                result
      catch case NonFatal(e) =>
        outcome = Some("ERROR")
        ReplResult.Failed(fansi.Str(s"The native REPL failed: $e"))
    timing.record(config.timings)
    result

  /** Marks the files of `line`, which did not link or load and so is
   *  invalid, as linked: the next line that runs links the others, those of
   *  the definitions-only lines before it, but not these.
   */
  private def skipFilesOf(line: ReplLine, files: Seq[(Path, (Long, Long))]): Unit =
    def of(name: String, prefix: String) =
      name == s"$prefix${line.index}.nir" || name.startsWith(s"$prefix${line.index}$$")
    for (file, fileStamp) <- files do
      val name = file.getFileName.toString
      if of(name, "rs$line$") || of(name, "rs$runner$") then seen(file) = fileStamp

  /** Links the libraries added since the last call into a bundle, from the
   *  cache if it holds one, and loads it into the evaluator; the error, if
   *  that fails. Lines then link the libraries' classes into their own
   *  bundles, as they did before the libraries were added.
   */
  private def loadLibraries(host: Host, lineTiming: Option[Timing]): Option[String] =
    if pendingLibraries.isEmpty then None
    else
      val roots = pendingLibraries.toList
      pendingLibraries = Vector.empty
      if evaluator == null then prepare(host)
      val start = System.nanoTime()
      linkLibraries(roots, host) match
        case Left(error) => Some(error)
        case Right((batch, linked)) =>
          val linkTime = System.nanoTime() - start
          val response = ensureEvaluator(host).call(List("REPLAY", batch.bundle.toString, "").mkString("\t"),
            interruptible = false)
          val total = System.nanoTime() - start
          Libraries.recordTiming(config.timings, roots.size, linked, linkTime, total - linkTime)
          lineTiming.foreach(_.libraries = total)
          if response.status == "OK" then
            batches += batch
            None
          else
            Some(s"Loading the jars into the evaluator failed: ${response.status} " +
              response.texts.headOption.flatMap(Option(_)).getOrElse(""))

  /** The bundle of `roots`, linked whole against the host and the library
   *  bundles loaded before (not the lines, which libraries do not use), so
   *  that its cache key depends on those only; and whether it was linked now.
   */
  private def linkLibraries(roots: List[Path], host: Host): Either[String, (Batch, Boolean)] =
    val chain = batches.filter(_.library.nonEmpty).toList
    val classpath = (extraClasspath.map(_.toAbsolutePath.normalize).toList ++ config.linkClasspath).distinct
    val key = Libraries.key(host, roots, classpath.filterNot(roots.contains).filterNot(config.linkClasspath.contains),
      chain.flatMap(_.library), lineLinkOptions(config.linkOptions) ++ LinkerOptions)
    val cached = Libraries.cacheRoot(config).resolve(key)
    def batchIn(dir: Path) =
      Batch(dir.resolve(Libraries.BundleName + Libraries.bundleSuffix), dir.resolve(Libraries.BundleName + ".snm"), "",
        Some(key))
    if NativeFiles.isComplete(cached) then Right((batchIn(cached), false))
    else
      System.err.println(s"snc repl: linking ${Libraries.describe(roots)} into the evaluator; " +
        "this happens once per set of jars.")
      val work = NativeFiles.newWorkDir(Libraries.cacheRoot(config), key.take(16) + ".")
      val linkDir = work.resolve("link")
      val args =
        List("--extension", "--host", host.manifest.toString, "--host-binary", host.binary.toString)
        ++ roots.flatMap(r => List("--ext", r.toString))
        ++ chain.flatMap(b => List("--chain", b.delta.toString))
        ++ List("--delta", work.resolve(Libraries.BundleName + ".snm").toString,
          "--stub-unlinkable",
          "--interrupt-handler", InterruptHandler,
          "--base-dir", linkDir.toString,
          "--module", Libraries.BundleName,
          "--no-optimize")
        ++ lineLinkOptions(config.linkOptions)
        ++ classpath.map(_.toString)
      warmUp = Iterator.empty
      val response = request(args)
      Files.write(work.resolve("link.log"), response.log.mkString("", "\n", "\n").getBytes(UTF_8))
      response.artifact match
        case None =>
          NativeFiles.deleteRecursively(linkDir)
          Left(NativeReplBackend.linkError("Linking the jars failed:", response.log) +
            System.lineSeparator + s"The link's log is in ${work.resolve("link.log")}")
        case Some(bundle) =>
          Files.move(Paths.get(bundle), work.resolve(Libraries.BundleName + Libraries.bundleSuffix))
          val stubs = linkDir.resolve("native").resolve("stubbed-methods.txt")
          if Files.isRegularFile(stubs) then Files.move(stubs, work.resolve("stubbed-methods.txt"))
          NativeFiles.deleteRecursively(linkDir)
          NativeFiles.publish(work, cached)
          Right((batchIn(cached), true))

  /** Links the NIR the compiler wrote since the last line that the evaluator
   *  loaded into a bundle, in a directory of its own: the bundle and the
   *  files linked into it, with their stamps when they were linked (the
   *  line may delete them).
   */
  private def link(line: ReplLine, host: Host, timing: Timing): Either[String, (Batch, Seq[(Path, (Long, Long))])] =
    val start = System.nanoTime()
    links += 1
    val k = links
    val batchDir = sessionDir.resolve(s"batch$k")
    val root = Files.createDirectories(batchDir.resolve("nir"))
    val fresh = newNirFiles()
    val stamps = fresh.map((_, file) => file -> stamp(file))
    fresh.foreach: (dir, file) =>
      val target = root.resolve(dir.relativize(file).toString)
      Files.createDirectories(target.getParent)
      Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
    val delta = batchDir.resolve(s"batch$k.snm")
    val args =
      List("--extension", "--host", host.manifest.toString, "--host-binary", host.binary.toString,
        "--ext", root.toString)
      ++ batches.toList.flatMap(b => List("--chain", b.delta.toString))
      ++ List("--delta", delta.toString,
        "--interrupt-handler", InterruptHandler,
        "--base-dir", batchDir.resolve("link").toString,
        "--module", s"batch$k",
        "--no-optimize")
      ++ lineLinkOptions(config.linkOptions)
      ++ (outputDirs ++ extraClasspath ++ config.linkClasspath).map(_.toString)
    // The link reads whatever the warm-up has not read
    warmUp = Iterator.empty
    val response = request(args)
    timing.link = System.nanoTime() - start
    timing.phases = response.phases
    response.artifact match
      case Some(bundle) => Right((Batch(Paths.get(bundle), delta, ""), stamps))
      case None =>
        skipFilesOf(line, stamps)
        NativeFiles.deleteRecursively(batchDir)
        Left(NativeReplBackend.linkError("Linking the line failed:", response.log))

  /** .nir files of the session's output directories that are new or changed
   *  since they were last linked.
   */
  private def newNirFiles(): Seq[(Path, Path)] =
    outputDirs.flatMap: dir =>
      if !Files.isDirectory(dir) then Nil
      else
        val stream = Files.walk(dir)
        try
          stream.iterator.asScala
            .filter(p => p.toString.endsWith(".nir") && Files.isRegularFile(p))
            .filter(p => !seen.get(p).contains(stamp(p)))
            .map(p => (dir, p))
            .toList
        finally stream.close()

  private def stamp(p: Path): (Long, Long) =
    (Files.size(p), Files.getLastModifiedTime(p).toMillis)

  /** Runs `line` from `batch` in the evaluator. */
  private def evaluate(line: ReplLine, host: Host, batch: Batch, timing: Timing)(using Context): ReplResult =
    val start = System.nanoTime()
    val evaluator = ensureEvaluator(host)
    timing.start = System.nanoTime() - start
    val runner = NativeReplCompiler.runnerModule(line.index)
    val request = List("EVAL", batch.bundle.toString, runner,
      ctx.settings.pageWidth.value.toString,
      ctx.settings.XreplPrintHeight.value.toString,
      (ctx.settings.color.value != "never").toString,
      ReplShow.offsets(line).mkString(",")).mkString("\t")
    val response = evaluator.call(request, interruptible = true)
    outcome = Some(response.status)
    timing.eval = System.nanoTime() - start
    timing.load = response.loadMicros * 1000L
    timing.runInEvaluator = response.runMicros * 1000L
    response.status match
      case "OK" =>
        batch.runner = runner
        batches += batch
        ReplShow.results(line, response.texts)
      case "FAILED" =>
        batches += batch
        ReplResult.Failed(fansi.Str(String.valueOf(response.texts.headOption.flatMap(Option(_)).getOrElse(""))))
      case "INTERRUPTED" =>
        batches += batch
        ReplResult.Failed(fansi.Str("Interrupted."))
      case "LOADERROR" =>
        // Not loaded: later lines do not link against it
        ReplResult.Failed(fansi.Str(s"Loading the line failed: ${response.texts.headOption.flatMap(Option(_)).getOrElse("")}"))
      case "KILLED" =>
        batches += batch
        restart(host)
        ReplResult.Failed(fansi.Str(
          "Interrupted. The line did not stop, so the evaluator was restarted; the earlier lines ran again, with their output discarded."))
      case "CRASHED" =>
        batches += batch
        val why = response.texts.headOption.flatMap(Option(_)).getOrElse("")
        restart(host)
        ReplResult.Failed(fansi.Str(
          s"The evaluator crashed ($why) while running this line. It was restarted; the earlier lines ran again, with their output discarded."))
      case other =>
        ReplResult.Failed(fansi.Str(s"Unexpected response from the evaluator: $other"))

  private def ensureEvaluator(host: Host): EvaluatorChannel =
    val e = evaluator
    if e != null && (e.isAlive || config.driven) then e
    else if e != null then
      // It ended between lines: a new one, with the session replayed
      restart(host)
      evaluator.nn
    else
      val started = startEvaluator(host, awaitReady = true)
      evaluator = started
      started

  /** The evaluator: the driver's channel when driven, else a new process. */
  private def startEvaluator(host: Host, awaitReady: Boolean): EvaluatorChannel =
    if config.driven then DrivenRepl.evaluator
    else EvaluatorProcess.start(host.binary, capture, awaitReady)

  /** A new evaluator, with the session's bundles loaded and the lines that
   *  ran run again, their output discarded.
   */
  private def restart(host: Host): Unit =
    if config.driven then return
    val old = evaluator
    if old != null then old.stop()
    evaluator = null
    val fresh = EvaluatorProcess.start(host.binary, capture)
    evaluator = fresh
    val failures = mutable.ArrayBuffer.empty[String]
    for batch <- batches do
      val response = fresh.call(List("REPLAY", batch.bundle.toString, batch.runner).mkString("\t"), interruptible = false)
      if response.status != "OK" then
        failures += s"${batch.runner}: ${response.status} ${response.texts.headOption.flatMap(Option(_)).getOrElse("")}"
    if failures.nonEmpty then
      System.err.println(failures.mkString("Running earlier lines again failed:\n  ", "\n  ", ""))

end EvaluatorBackend

object EvaluatorBackend:

  /** The host method extension code calls while the evaluator's interrupt
   *  flag is set.
   */
  val InterruptHandler = "dotty.tools.repl.runtime.Interrupt$.check"

  /** The evaluator's main. */
  val EvaluatorMain = "dotty.tools.repl.runtime.Evaluator"

  /** The options of every link of lines: clang at `-O0`. */
  private val LinkerOptions = List("--compile-option", "-O0")

  /** Why an `snc` without an extension linker (the plain `snc`) runs no
   *  evaluator.
   */
  val NoLinkerMessage =
    "snc repl: the REPL links its lines with snc-host's extension linker, which this snc does not have: " +
      "use snc-host (a distribution's snc), or --repl-backend relink"

  /** The `--link-option`s that apply to a line's link: not the optimizer
   *  (lines are linked without it) and not the mode, which the host fixes, as
   *  a line's safepoint polls must be the host's. A build tool passes the
   *  build's mode (scala-cli's `--native-mode`, `debug` by default).
   */
  def lineLinkOptions(options: List[String]): List[String] = options match
    case "--mode" :: _ :: rest => lineLinkOptions(rest)
    case ("--optimize" | "--no-optimize") :: rest => lineLinkOptions(rest)
    case option :: rest => option :: lineLinkOptions(rest)
    case Nil => Nil

  /** How long the linker's warm-up steps take in one idle call
   *  before it waits for the next.
   */
  private val WarmUpMillis = 20

  /** What the evaluator writes after a line's output when it is captured
   *  (`dotty.tools.repl.runtime.Evaluator.Marker`).
   */
  val OutputMarker: Array[Byte] = "\u0000SNREPL\u0000".getBytes(UTF_8)

  /** Ctrl-C while a line links or runs (SIGINT, which `snc repl` handles). */
  object Interrupts:
    @volatile var count: Int = 0
    def clear(): Unit = count = 0
    def pending: Boolean = count > 0
    private var installed = false
    /** Counts SIGINTs from now on. */
    def install(): Unit =
      if !installed then
        installed = true
        signal.signal(signal.SIGINT, CFuncPtr1.fromScalaFunction[CInt, Unit](_ => Interrupts.count += 1))
        // A write to an evaluator that ended fails instead of ending snc
        signal.signal(scala.scalanative.posix.signal.SIGPIPE, signal.SIG_IGN)

  /** Where the time of a line goes, appended to `--repl-timings`. */
  final class Timing(index: Int):
    var link, eval, start, load, runInEvaluator, libraries = 0L
    var phases: Seq[(String, Long)] = Nil
    def record(file: Option[Path]): Unit =
      for f <- file do
        def ms(ns: Long) = ns / 1000000
        val phaseText = phases.map((name, msValue) => s"$name=$msValue").mkString(",")
        val libs = if libraries > 0 then s", libraries ${ms(libraries)} ms" else ""
        val entry = s"line $index: link ${ms(link)} ms, run ${ms(eval)} ms, start ${ms(start)} ms, load ${ms(load)} ms, init+show ${ms(runInEvaluator)} ms$libs, phases $phaseText\n"
        Files.write(f, entry.getBytes(UTF_8),
          java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)

  /** The evaluator host for a class path: linked once, kept in the cache
   *  `$XDG_CACHE_HOME/snc/evaluator-hosts` (or `~/.cache/...`) under a key of
   *  the class path, the linker and the host's link options.
   */
  final case class Host(binary: Path, manifest: Path)

  object Host:
    private var cachedHost: Option[(List[Path], Either[String, Host])] = None

    /** The host, if it is linked already. */
    def cached(config: NativeReplConfig): Option[Host] =
      cachedHost match
        case Some((cp, Right(host))) if cp == config.linkClasspath => Some(host)
        case _ =>
          config.hostBinary match
            case Some(binary) => Some(Host(binary, siblingManifest(binary)))
            case None =>
              val binary = cacheRoot(config).resolve(keyOf(config)).resolve("snrepl-host")
              if Files.isRegularFile(binary) && Files.isRegularFile(siblingManifest(binary)) then
                Some(Host(binary, siblingManifest(binary)))
              else None

    /** A link of the host that runs in the background. */
    private final class Building(val classpath: List[Path], val process: Process, val buildDir: Path,
        val binary: Path)
    private var building: Option[Building] = None

    /** Starts linking the host in the background, unless it is linked or
     *  being linked already, or given with `--evaluator-host`. The link
     *  publishes the host in the cache itself, so a session that ends before
     *  it does still leaves the host for the next one.
     */
    def startBuild(config: NativeReplConfig): Unit =
      if config.hostBinary.isEmpty && known(config).isEmpty && cached(config).isEmpty
        && !building.exists(_.classpath == config.linkClasspath)
      then
        System.err.println(
          "snc repl: linking the evaluator for this class path in the background; this happens once and " +
            "takes a few minutes. Definitions compile meanwhile; the first line that runs waits for it.")
        building = Some(launch(config))

    /** The host, if it is known or its background link has ended. */
    def poll(config: NativeReplConfig): Option[Either[String, Host]] =
      known(config).orElse:
        building match
          case Some(b) if b.classpath == config.linkClasspath =>
            // waitFor with a timeout reaps the link: isAlive alone is not
            // updated in a single-threaded snc
            if b.process.waitFor(1, java.util.concurrent.TimeUnit.MILLISECONDS) then Some(finish(config, b))
            else None
          case _ =>
            config.hostBinary match
              case Some(binary) => Some(remember(config, Right(Host(binary, siblingManifest(binary)))))
              case None => cached(config).map(host => remember(config, Right(host)))

    /** The host: waits for its link, started now if it is not running. Ctrl-C
     *  ends the wait, not the link.
     */
    def await(config: NativeReplConfig): Either[String, Host] =
      poll(config) match
        case Some(host) => host
        case None =>
          if building.exists(_.classpath == config.linkClasspath) then
            System.err.println("snc repl: waiting for the evaluator, which is still being linked ...")
          else startBuild(config)
          var host = poll(config)
          while host.isEmpty && !Interrupts.pending && building.nonEmpty do
            // Up to 100 ms, less if the link ends
            building.foreach(_.process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            host = poll(config)
          host.getOrElse(Left("Interrupted. The evaluator is still being linked in the background."))

    private def known(config: NativeReplConfig): Option[Either[String, Host]] =
      cachedHost.collect { case (cp, host) if cp == config.linkClasspath => host }

    private def remember(config: NativeReplConfig, host: Either[String, Host]): Either[String, Host] =
      cachedHost = Some((config.linkClasspath, host))
      host

    private def siblingManifest(binary: Path): Path =
      binary.resolveSibling(binary.getFileName.toString + ".snm")

    private def cacheRoot(config: NativeReplConfig): Path =
      NativeFiles.cacheDir("evaluator-hosts").getOrElse(config.workDir.resolve("hosts"))

    /** What the background link runs, after its directories as `$1` and
     *  `$2` and the `snlink` command: it ignores Ctrl-C (which goes to the
     *  whole terminal's process group), and on success publishes the host in
     *  one step, without the linker's work files but with its list of stubs.
     *  The manifest names the binary it was linked with, so the two stay
     *  together. If another REPL published the host first, this copy is
     *  deleted.
     */
    private val LinkScript =
      """trap '' INT
        |b=$1; p=$2; shift 2
        |"$@" || exit $?
        |[ -f "$b/snrepl-host" ] || exit 70
        |if [ -f "$b/native/stubbed-methods.txt" ]; then mv -f "$b/native/stubbed-methods.txt" "$b/stubbed-methods.txt"; fi
        |rm -rf "$b/native"
        |if [ -d "$p" ]; then rm -rf "$b"; else mv "$b" "$p"; fi
        |""".stripMargin

    /** The `snlink` command that links the host for `config`'s class path
     *  into `baseDir`, as `snrepl-host` with its manifest `snrepl-host.snm`.
     */
    def linkCommand(config: NativeReplConfig, baseDir: Path): List[String] =
      List(config.snlink, "--main", EvaluatorMain, "--module", "snrepl-host",
        "--base-dir", baseDir.toString,
        // Compiled at -O2 without the NIR optimizer: the lines' extensions
        // are linked in the same mode, compiled at -O0
        "--mode", "release-fast", "--multithreading", "true", "--no-optimize",
        "--open", "*", "--callable", "*",
        "--callable", "-scala.scalanative.runtime.Intrinsics*",
        "--callable", "-scala.scalanative.unsafe.CFuncPtr*",
        "--stub-unlinkable")
      ++ config.hostLinkOptions
      ++ config.linkClasspath.map(_.toString)

    private def launch(config: NativeReplConfig): Building =
      val key = keyOf(config)
      val published = cacheRoot(config).resolve(key)
      val pid = unistd.getpid()
      val buildDir = Files.createDirectories(cacheRoot(config).resolve(s"$key.build-$pid"))
      val link = linkCommand(config, buildDir)
      val command = List("/bin/sh", "-c", LinkScript, "snrepl-host-link", buildDir.toString, published.toString) ++ link
      val process = new ProcessBuilder(command.asJava)
        .redirectErrorStream(true)
        .redirectOutput(buildDir.resolve("link.log").toFile)
        .start()
      new Building(config.linkClasspath, process, buildDir, published.resolve("snrepl-host"))

    /** The result of a background link that ended. */
    private def finish(config: NativeReplConfig, b: Building): Either[String, Host] =
      building = None
      val exit = b.process.exitValue()
      val manifest = siblingManifest(b.binary)
      if exit == 0 && Files.isRegularFile(b.binary) && Files.isRegularFile(manifest) then
        remember(config, Right(Host(b.binary, manifest)))
      else
        val log = b.buildDir.resolve("link.log")
        val lines = if Files.isRegularFile(log) then Files.readAllLines(log, UTF_8).asScala.toList else Nil
        val hint =
          if lines.exists(_.contains("live-execution loader is missing functions")) then
            List("The class path's nativelib predates the loader the evaluator needs (chained " +
              "extensions); use a newer one, or --repl-backend relink.")
          else Nil
        remember(config,
          Left((s"Linking the evaluator failed (snlink exited with $exit, log in $log):" +: lines.takeRight(20) ::: hint)
            .mkString(System.lineSeparator)))

    /** The cache key: the host format, the host's link options, the linker
     *  and class path files with their sizes and modification times, and
     *  the NIR files of class path directories with theirs, so that a
     *  directory compiled again gives another key.
     */
    private def keyOf(config: NativeReplConfig): String =
      def stamp(file: Path) = s"${Files.size(file)} ${Files.getLastModifiedTime(file).toMillis}"
      val parts = (Paths.get(config.snlink) :: config.linkClasspath).map: p =>
        val abs = p.toAbsolutePath
        if Files.isRegularFile(abs) then s"$abs ${stamp(abs)}"
        else if Files.isDirectory(abs) then
          NativeFiles.nirFiles(abs).toList.sorted.map(f => s"$f ${stamp(abs.resolve(f))}").mkString(s"$abs\n", "\n", "")
        else abs.toString
      val text = (HostFormat :: config.hostLinkOptions ::: parts).mkString("\n")
      import scala.util.hashing.MurmurHash3.stringHash
      f"${stringHash(text, 0x5eed)}%08x${stringHash(text, 0x1ab)}%08x"

    /** Part of the cache key: change it when the host's boundary or main
     *  changes, so that cached hosts are linked again.
     */
    private val HostFormat = "snrepl-host 3"
  end Host

  /** Jars and directories with NIR that are added to a session's class path,
   *  linked whole as an extension of the evaluator and kept in the cache
   *  `$XDG_CACHE_HOME/snc/libraries` (or `~/.cache/...`): `<key>/` holds the
   *  bundle, its delta, the stubs the link made, its log and `done`; `tmp/`
   *  holds links in progress, each renamed into place when it is complete.
   */
  object Libraries:
    val BundleName = "libraries"
    val bundleSuffix: String =
      if System.getProperty("os.name", "").toLowerCase.contains("mac") then ".bundle" else ".so"

    /** Bumped when library bundles change shape. */
    private val CacheVersion = "snc-libraries 1"

    /** Whether `entry`, a jar or a directory, holds NIR. */
    def hasNir(entry: Path): Boolean =
      try
        if Files.isDirectory(entry) then
          val stream = Files.walk(entry)
          try stream.iterator.asScala.exists(p => p.toString.endsWith(".nir") && Files.isRegularFile(p))
          finally stream.close()
        else if Files.isRegularFile(entry) then
          val zip = new java.util.zip.ZipFile(entry.toFile)
          try zip.entries.asScala.exists(_.getName.endsWith(".nir"))
          finally zip.close()
        else false
      catch case NonFatal(_) => false

    /** The content hashes of the jars and directories in keys. */
    private val contentHash = new NativeFiles.ContentHashes(sums = None)

    /** The cache key of a bundle of `roots` linked against `host` and the
     *  library bundles `chain` (their keys), reading `classpath` besides the
     *  host's class path, with `options`.
     */
    def key(host: Host, roots: List[Path], classpath: List[Path], chain: List[String], options: List[String]): String =
      val manifestHash =
        val in = Files.newInputStream(host.manifest)
        try new String(in.readNBytes(48), UTF_8) finally in.close()
      val ingredients =
        List(CacheVersion, manifestHash, Files.size(host.binary).toString, NativeFiles.toolchainId,
          options.mkString(" "))
          ++ roots.map(r => "root " + contentHash(r))
          ++ classpath.map(e => "cp " + contentHash(e))
          ++ chain.map("chain " + _)
      NativeFiles.key(ingredients)

    def cacheRoot(config: NativeReplConfig): Path =
      NativeFiles.cacheDir("libraries").getOrElse(config.workDir.resolve("libraries"))

    /** `os-lib_native0.5_3-0.11.8.jar and 1 other jar`. */
    def describe(roots: List[Path]): String =
      val first = roots.head.getFileName.toString
      roots.size - 1 match
        case 0 => first
        case 1 => s"$first and 1 other jar"
        case n => s"$first and $n other jars"

    /** Appends a library bundle's link (or cache lookup) and load times to
     *  `--repl-timings`.
     */
    def recordTiming(file: Option[Path], jars: Int, linked: Boolean, link: Long, load: Long): Unit =
      for f <- file do
        val how = if linked then "linked" else "cached"
        val entry = s"libraries: $jars jars, $how, link ${link / 1000000} ms, load ${load / 1000000} ms\n"
        Files.write(f, entry.getBytes(UTF_8),
          java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
  end Libraries

  /** The result of a link: the bundle, if any, the log, and the times of the
   *  link's phases, read from the log.
   */
  final case class LinkResponse(artifact: Option[String], log: Seq[String]):
    def phases: Seq[(String, Long)] =
      val Timed = """\[info\] (.*) \((\d+) ms\)""".r
      log.collect { case Timed(name, ms) => (name.nn.takeWhile(_ != '(').trim, ms.nn.toLong) }

  /** A response of the evaluator (see `dotty.tools.repl.runtime.Evaluator`),
   *  or of this side when the evaluator was killed (`KILLED`) or ended
   *  (`CRASHED`).
   */
  final case class Response(status: String, loadMicros: Long, runMicros: Long, texts: List[String | Null])

  /** Where the backend sends requests and reads responses: an evaluator
   *  process, or the program that drives this REPL.
   */
  trait EvaluatorChannel:
    def isAlive: Boolean
    /** Sends `request` and waits for its response. With `interruptible`,
     *  Ctrl-C may stop the line.
     */
    def call(request: String, interruptible: Boolean): Response
    def stop(): Unit

  /** A running evaluator: its pid, and the pipes of its control channel and,
   *  when capturing, of its output.
   */
  final class EvaluatorProcess private (pid: Int, requests: Int, responses: Int, output: Int)
  extends EvaluatorChannel:
    private var alive = true
    private val pending = new Pending
    private var outputTail = Array.emptyByteArray

    def isAlive: Boolean = alive

    /** Whether the `PONG` of a sent `PING` is still unread. */
    private var pongPending = false

    /** Sends `PING` without waiting for its answer, which the next `call`
     *  reads first.
     */
    def ping(): Unit =
      writeAll(requests, "PING\n".getBytes(UTF_8))
      pongPending = true

    /** Sends `request` and waits for its response, copying the evaluator's
     *  output meanwhile. With `interruptible`, Ctrl-C asks the evaluator to
     *  interrupt the line; it is killed if the line does not stop within 3 s
     *  or on a second Ctrl-C.
     */
    def call(request: String, interruptible: Boolean): Response =
      if !alive then return Response("CRASHED", 0, 0, List("it was not running"))
      if pongPending then
        pongPending = false
        val pong = await(interruptible = false)
        if pong.status != "PONG" then return pong
      try writeAll(requests, (request + "\n").getBytes(UTF_8))
      catch case NonFatal(_) => return Response("CRASHED", 0, 0, List(reap()))
      await(interruptible)

    /** Waits for the response to the request just sent. */
    private def await(interruptible: Boolean): Response =
      var response: Option[Response] = None
      var interruptSent = -1L
      var interruptsSeen = 0
      var markerSeen = output < 0
      while response.isEmpty || !markerSeen do
        if interruptible && Interrupts.count > interruptsSeen then
          interruptsSeen = Interrupts.count
          if interruptSent < 0 then
            interruptSent = System.nanoTime()
            try writeAll(requests, "INTERRUPT\n".getBytes(UTF_8))
            catch case NonFatal(_) => ()
          else
            kill()
            response = Some(Response("KILLED", 0, 0, Nil))
            markerSeen = true
        if response.isEmpty && interruptSent >= 0 && System.nanoTime() - interruptSent > 3000000000L then
          kill()
          response = Some(Response("KILLED", 0, 0, Nil))
          markerSeen = true
        if response.isEmpty || !markerSeen then
          waitReadable(50) match
            case (respReady, outReady) =>
              if outReady then markerSeen = copyOutput() || markerSeen
              if respReady && response.isEmpty then
                if !readResponse() then
                  // The evaluator ended
                  val why = reap()
                  response = Some(Response("CRASHED", 0, 0, List(why)))
                  markerSeen = true
                else
                  response = parse()
                  // No output marker comes with a `PONG`, only with a
                  // line's response
                  if response.exists(_.status == "PONG") then markerSeen = true
      response.get

    /** Waits until the response pipe or the output pipe can be read. */
    private def waitReadable(timeoutMs: Int): (Boolean, Boolean) =
      val fds = stackalloc[poll.struct_pollfd](2)
      val first = fds
      val second = fds + 1
      first.fd = responses
      first.events = poll.POLLIN.toShort
      first.revents = 0.toShort
      val n = if output >= 0 then 2 else 1
      if output >= 0 then
        second.fd = output
        second.events = poll.POLLIN.toShort
        second.revents = 0.toShort
      val ready = poll.poll(fds, n.toUInt, timeoutMs)
      if ready <= 0 then (false, false)
      else (first.revents != 0, n == 2 && second.revents != 0)

    /** Reads what the response pipe has; false at its end. */
    private def readResponse(): Boolean =
      val buf = new Array[Byte](65536)
      val n = unistd.read(responses, buf.at(0), buf.length.toCSize)
      if n <= 0 then false
      else
        pending.write(buf, 0, n)
        true

    /** A complete response from what was read, if there is one, taken out
     *  of `pending`. Until all of a response has arrived, nothing is taken
     *  and no text is decoded: the fields are read in order, and the first
     *  that is not complete ends the attempt.
     */
    private def parse(): Option[Response] =
      val bytes = pending.bytes
      val size = pending.size()
      var pos = 0
      def line(): String | Null =
        var end = pos
        while end < size && bytes(end) != '\n' do end += 1
        if end >= size then null
        else
          val s = new String(bytes, pos, end - pos, UTF_8)
          pos = end + 1
          s
      val header = line()
      if header == null then None
      else
        val fields = header.split("\t")
        if fields(0) == "PONG" then
          pending.consume(pos)
          Some(Response("PONG", 0, 0, Nil))
        else
          // Where each text starts and its length (-1 for none)
          val count = fields(3).toInt
          val starts = new Array[Int](count)
          val lengths = new Array[Int](count)
          var complete = true
          var i = 0
          while complete && i < count do
            val length = line()
            if length == null then complete = false
            else
              starts(i) = pos
              lengths(i) = length.trim.toInt
              if lengths(i) >= 0 then
                if pos + lengths(i) + 1 > size then complete = false
                else pos += lengths(i) + 1
              i += 1
          if !complete then None
          else
            val texts = List.tabulate[String | Null](count): i =>
              if lengths(i) < 0 then null else new String(bytes, starts(i), lengths(i), UTF_8)
            pending.consume(pos)
            Some(Response(fields(0), fields(1).toLong, fields(2).toLong, texts))

    /** Copies the evaluator's output to `System.out`; true if it saw the
     *  marker that ends a line's output.
     */
    private def copyOutput(): Boolean =
      val buf = new Array[Byte](65536)
      val n = unistd.read(output, buf.at(0), buf.length.toCSize)
      if n <= 0 then true
      else
        val data = outputTail ++ java.util.Arrays.copyOf(buf, n)
        val marker = OutputMarker
        val at = indexOf(data, marker)
        if at >= 0 then
          System.out.write(data, 0, at)
          System.out.flush()
          outputTail = java.util.Arrays.copyOfRange(data, at + marker.length, data.length)
          // Output after the marker comes from threads that outlive their
          // line
          if outputTail.nonEmpty then
            System.out.write(outputTail, 0, outputTail.length)
            outputTail = Array.emptyByteArray
          true
        else
          // Keep a tail that may be the start of a marker
          val keep = math.min(marker.length - 1, data.length)
          System.out.write(data, 0, data.length - keep)
          System.out.flush()
          outputTail = java.util.Arrays.copyOfRange(data, data.length - keep, data.length)
          false

    private def indexOf(data: Array[Byte], marker: Array[Byte]): Int =
      var i = 0
      var found = -1
      while found < 0 && i + marker.length <= data.length do
        var j = 0
        while j < marker.length && data(i + j) == marker(j) do j += 1
        if j == marker.length then found = i
        i += 1
      found

    private def kill(): Unit =
      scala.scalanative.posix.signal.kill(pid, scala.scalanative.posix.signal.SIGKILL)
      reap()

    /** Waits for the evaluator to end; how it ended. */
    private def reap(): String =
      alive = false
      val status = stackalloc[CInt]()
      val r = pwait.waitpid(pid, status, 0)
      unistd.close(requests)
      unistd.close(responses)
      if output >= 0 then unistd.close(output)
      if r != pid then "it ended"
      else if pwait.WIFSIGNALED(!status) then
        val sig = pwait.WTERMSIG(!status)
        // The numbers are the platform's: SIGBUS is 7 on Linux, 10 on macOS
        import scala.scalanative.posix.signal as signals
        List(signals.SIGILL -> "SIGILL", signals.SIGABRT -> "SIGABRT", signals.SIGBUS -> "SIGBUS",
          signals.SIGFPE -> "SIGFPE", signals.SIGKILL -> "SIGKILL", signals.SIGSEGV -> "SIGSEGV",
          signals.SIGTERM -> "SIGTERM")
          .collectFirst { case (number, name) if number == sig => name }
          .getOrElse(s"signal $sig")
      else s"exit code ${pwait.WEXITSTATUS(!status)}"

    def stop(): Unit =
      if alive then
        try writeAll(requests, "QUIT\n".getBytes(UTF_8))
        catch case NonFatal(_) => ()
        kill()
  end EvaluatorProcess

  /** The bytes read from the response pipe that no response has taken yet,
   *  readable in place.
   */
  private final class Pending extends java.io.ByteArrayOutputStream:
    def bytes: Array[Byte] = buf
    /** Drops the first `n` bytes. */
    def consume(n: Int): Unit =
      System.arraycopy(buf, n, buf, 0, count - n)
      count -= n

  object EvaluatorProcess:
    /** Starts `binary` with its control channel on file descriptors 3 and 4.
     *  With `awaitReady`, waits until it reads requests; else the first
     *  request waits.
     */
    def start(binary: Path, capture: Boolean, awaitReady: Boolean = true): EvaluatorProcess =
      Interrupts.install()
      val req = stackalloc[CInt](2)
      val resp = stackalloc[CInt](2)
      val out = stackalloc[CInt](2)
      if unistd.pipe(req) != 0 || unistd.pipe(resp) != 0 || (capture && unistd.pipe(out) != 0) then
        throw new java.io.IOException("cannot create pipes for the evaluator")
      val actions = stackalloc[spawn.posix_spawn_file_actions_t]()
      spawn.posix_spawn_file_actions_init(actions)
      spawn.posix_spawn_file_actions_adddup2(actions, req(0), 3)
      spawn.posix_spawn_file_actions_adddup2(actions, resp(1), 4)
      if capture then
        spawn.posix_spawn_file_actions_adddup2(actions, out(1), 1)
        spawn.posix_spawn_file_actions_adddup2(actions, out(1), 2)
      // dup2 leaves the targets open, the originals are closed
      val originals = List(req(0), req(1), resp(0), resp(1)) ++ (if capture then List(out(0), out(1)) else Nil)
      originals.filter(fd => fd > 4).foreach(fd => spawn.posix_spawn_file_actions_addclose(actions, fd))
      val pid = stackalloc[CInt]()
      val args = List(binary.toString) ++ (if capture then List("--capture") else Nil)
      val status = Zone.acquire { implicit z =>
        val argv = stackalloc[CString](args.size + 1)
        args.zipWithIndex.foreach((a, i) => argv(i) = toCString(a))
        argv(args.size) = null.asInstanceOf[CString]
        spawn.posix_spawn(pid, toCString(binary.toString), actions,
          null.asInstanceOf[Ptr[spawn.posix_spawnattr_t]], argv, unistd.environ)
      }
      spawn.posix_spawn_file_actions_destroy(actions)
      unistd.close(req(0))
      unistd.close(resp(1))
      if capture then unistd.close(out(1))
      if status != 0 then
        unistd.close(req(1))
        unistd.close(resp(0))
        if capture then unistd.close(out(0))
        throw new java.io.IOException(s"cannot start the evaluator $binary: error $status")
      // Not inherited by the processes this one starts later
      val ours = List(req(1), resp(0)) ++ (if capture then List(out(0)) else Nil)
      ours.foreach(fd => fcntl.fcntl(fd, fcntl.F_SETFD, fcntl.FD_CLOEXEC))
      val process = new EvaluatorProcess(!pid, req(1), resp(0), if capture then out(0) else -1)
      // It answers once it reads requests
      if awaitReady then process.call("PING", interruptible = false)
      else process.ping()
      process

  private def writeAll(fd: Int, bytes: Array[Byte]): Unit =
    var off = 0
    while off < bytes.length do
      val n = unistd.write(fd, bytes.at(off), (bytes.length - off).toCSize)
      if n <= 0 then throw new java.io.IOException("the evaluator does not read its requests")
      off += n
end EvaluatorBackend
