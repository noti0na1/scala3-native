package dotty.tools
package repl

import java.io.File
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*
import scala.scalanative.libc.signal
import scala.scalanative.unsafe.*

import dotty.tools.dotc.config.SncDistribution
import dotty.tools.dotc.util.NativeFiles

/** `snc repl`: the native REPL (see README.md, "REPL").
 *
 *  ```
 *  snc repl [REPL options] [compiler options]
 *  snc repl --transcripts <report dir> [REPL options] [compiler options] <transcript>...
 *  ```
 *
 *  REPL options, taken out before the compiler sees the rest:
 *
 *  - `--snlink <path>`: the linker of the evaluator host and of the relink
 *    backend's programs; by default `snlink` next to `snc`, else on the
 *    `PATH`;
 *  - `--repl-runtime <jar>`: the REPL runtime; by default `repl-runtime.jar`
 *    next to `snc` or in `../lib`;
 *  - `--link-option <option>` (repeatable): an option for `snlink`; by
 *    default `--no-optimize --multithreading true`;
 *  - `--repl-work-dir <dir>`: where sessions keep their code and links; by
 *    default the cache `~/.cache/snc/repl` if no other REPL uses it, else a
 *    new temporary directory, deleted at the end;
 *  - `--repl-timings <file>`: appends the link and run time of each line;
 *  - `--repl-backend evaluator|relink`: how lines run (the evaluator by
 *    default, see `EvaluatorBackend`, whose lines the extension linker built
 *    into `snc-host` links);
 *  - `--evaluator-host <binary>`: a prebuilt evaluator host (with its
 *    manifest `<binary>.snm` next to it), instead of the cached one;
 *  - `--host-link-option <option>` (repeatable): an option for `snlink`
 *    when it links the evaluator host;
 *  - `--link-evaluator-host <dir>`: links the evaluator host for the class
 *    path into `<dir>` and exits, without starting the REPL (a distribution
 *    ships the host for its own libraries);
 *  - `--driven`: compiles the lines of the program that started it and has
 *    that program run them (`DrivenRepl`), which `--evaluator-host` names.
 *
 *  The class path (`-classpath`) must hold what lines are compiled and
 *  linked with: Scala Native's libraries, `scala-library` of `snc`'s Scala
 *  version and the NIR of that library (`scalalib_native0.5_3`,
 *  `scala3lib_native0.5_3`), as a build tool passes them. The runtime is
 *  added to it. Without `-classpath`, an `snc` from a distribution uses the
 *  distribution's libraries (`SncDistribution.replClasspath`), and the
 *  evaluator host that comes with them, if there is one.
 */
object NativeRepl:

  final case class Options(
    snlink: Option[String] = None,
    runtime: Option[String] = None,
    linkOptions: List[String] = Nil,
    workDir: Option[String] = None,
    timings: Option[String] = None,
    transcripts: Option[String] = None,
    backend: NativeReplConfig.Backend = NativeReplConfig.Backend.Evaluator,
    evaluatorHost: Option[String] = None,
    hostLinkOptions: List[String] = Nil,
    linkHostTo: Option[String] = None,
    driven: Boolean = false,
    firstLine: Option[String] = None,
    commands: Option[String] = None,
    rest: List[String] = Nil)

  def parse(args: List[String], options: Options = Options()): Options = args match
    case "--snlink" :: path :: more => parse(more, options.copy(snlink = Some(path)))
    case "--repl-runtime" :: path :: more => parse(more, options.copy(runtime = Some(path)))
    case "--link-option" :: option :: more => parse(more, options.copy(linkOptions = options.linkOptions :+ option))
    case "--repl-work-dir" :: dir :: more => parse(more, options.copy(workDir = Some(dir)))
    case "--repl-timings" :: file :: more => parse(more, options.copy(timings = Some(file)))
    case "--transcripts" :: dir :: more => parse(more, options.copy(transcripts = Some(dir)))
    case "--repl-backend" :: "evaluator" :: more => parse(more, options.copy(backend = NativeReplConfig.Backend.Evaluator))
    case "--repl-backend" :: "relink" :: more => parse(more, options.copy(backend = NativeReplConfig.Backend.Relink))
    case "--evaluator-host" :: path :: more => parse(more, options.copy(evaluatorHost = Some(path)))
    case "--host-link-option" :: option :: more => parse(more, options.copy(hostLinkOptions = options.hostLinkOptions :+ option))
    case "--link-evaluator-host" :: dir :: more => parse(more, options.copy(linkHostTo = Some(dir)))
    case "--driven" :: more => parse(more, options.copy(driven = true))
    case "--first-line" :: n :: more => parse(more, options.copy(firstLine = Some(n)))
    case "--commands" :: names :: more => parse(more, options.copy(commands = Some(names)))
    case arg :: more => parse(more, options.copy(rest = options.rest :+ arg))
    case Nil => options

  /** The versions `snc` is built from: the Scala 3 nightly it corresponds to
   *  and the Scala Native version of its plugins.
   */
  final case class Versions(scalaVersion: String, scalaNativeVersion: String)

  /** Runs the REPL; `sncDir` is the directory of the `snc` executable. */
  def main(args: Array[String], sncDir: Option[Path], versions: Versions): Int =
    val options = parse(args.toList)
    val distribution = SncDistribution.find(sncDir)
    def nextToSnc(name: String): Option[Path] =
      sncDir.toList
        .flatMap(dir => List(dir.resolve(name), dir.resolve("..").resolve("lib").resolve(name).normalize()))
        .find(Files.isRegularFile(_))
    val runtime = options.runtime.map(Paths.get(_)).orElse(nextToSnc("repl-runtime.jar"))
    val snlink = options.snlink.orElse(nextToSnc("snlink").map(_.toString)).getOrElse("snlink")
    runtime match
      case None =>
        System.err.println(
          "snc repl: repl-runtime.jar not found next to snc; give its path with --repl-runtime <jar>")
        2
      case Some(runtimeJar) =>
        // The runtime goes on the class path, which also is the link class path
        val (entries, settings) = takeClasspath(options.rest)
        val classpath =
          if entries.nonEmpty then entries
          else distribution.toList.flatMap(_.replClasspath.map(_.toString))
        val fullClasspath = classpath :+ runtimeJar.toString
        val compilerSettings = settings ++ List("-classpath", fullClasspath.mkString(File.pathSeparator))
        options.linkHostTo match
          case Some(dir) =>
            linkEvaluatorHost(
              NativeReplConfig(snlink = snlink, linkClasspath = fullClasspath.filter(_.nonEmpty).map(Paths.get(_)),
                linkOptions = Nil, workDir = Paths.get(dir), timings = None, hostLinkOptions = options.hostLinkOptions),
              Paths.get(dir))
          case None =>
            run(options, distribution, sncDir, versions, snlink, runtimeJar, fullClasspath, compilerSettings)

  /** Runs the REPL, or its transcripts, with the class path `fullClasspath`. */
  private def run(options: Options, distribution: Option[SncDistribution], sncDir: Option[Path], versions: Versions,
      snlink: String, runtimeJar: Path, fullClasspath: List[String], compilerSettings: List[String]): Int =
    if options.backend == NativeReplConfig.Backend.Evaluator && dotty.tools.dotc.live.ExtensionLinker.get.isEmpty then
      System.err.println(EvaluatorBackend.NoLinkerMessage)
      return 2
    if options.driven && (options.evaluatorHost.isEmpty || options.backend != NativeReplConfig.Backend.Evaluator) then
      System.err.println("snc repl: --driven needs the driver's binary as --evaluator-host, and the evaluator backend")
      return 2
    if options.commands.isDefined && !options.driven then
      System.err.println("snc repl: --commands is for a driven REPL (--driven)")
      return 2
    val firstLine = options.firstLine.map(_.toIntOption.filter(_ >= 1))
    if firstLine.exists(_.isEmpty) then
      System.err.println(s"snc repl: --first-line needs a positive number, not ${options.firstLine.get}")
      return 2
    // The cache is locked when this REPL takes it (`cacheDir`); a directory
    // given with --repl-work-dir is not. A driven REPL works in a new one.
    val (workDir, temporary, locked) = options.workDir match
      case Some(dir) => (Paths.get(dir), false, false)
      case None if options.driven => (Files.createTempDirectory("snc-repl"), true, false)
      case None =>
        cacheDir() match
          case Some(dir) => (dir, false, true)
          case None => (Files.createTempDirectory("snc-repl"), true, false)
    val config = NativeReplConfig(
      snlink = snlink,
      linkClasspath = fullClasspath.filter(_.nonEmpty).map(Paths.get(_)),
      linkOptions = defaultLinkOptions(options.linkOptions) ++ options.linkOptions,
      workDir = workDir,
      timings = options.timings.map(Paths.get(_)),
      backend = options.backend,
      hostBinary = options.evaluatorHost.map(Paths.get(_).toAbsolutePath)
        .orElse(prebuiltHost(distribution, fullClasspath, runtimeJar, options)),
      hostLinkOptions = options.hostLinkOptions,
      driven = options.driven,
      firstLine = firstLine.flatten.getOrElse(1),
      commands = options.commands.map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet))
    // :dep resolves with coursier's native launcher (DependencyResolver)
    DependencyResolver.configure(DependencyResolver.Session(
      scalaVersion = versions.scalaVersion,
      scalaNativeVersion = versions.scalaNativeVersion,
      distribution = distribution,
      sncDir = sncDir,
      classpath = config.linkClasspath,
      workDir = workDir))
    // A Ctrl-C while a line runs stops the line, not the REPL
    signal.signal(signal.SIGINT, CFuncPtr1.fromScalaFunction[CInt, Unit](_ => ()))
    try
      options.transcripts match
        case _ if options.driven => DrivenRepl.run(compilerSettings.toArray, config)
        case Some(reportDir) =>
          val (files, others) = compilerSettings.partition(f => !f.startsWith("-") && Files.isRegularFile(Paths.get(f)) && !f.endsWith(".jar"))
          TranscriptRunner.runTranscripts(files.map(Paths.get(_)), others.toArray, config, Paths.get(reportDir))
        case None =>
          val driver = new NativeReplDriver(compilerSettings.toArray, System.out, config)
          try driver.tryRunning
          finally driver.close()
          0
    finally
      if temporary then NativeFiles.deleteRecursively(workDir)
      else if locked then
        Files.deleteIfExists(workDir.resolve("lock").resolve("pid"))
        Files.deleteIfExists(workDir.resolve("lock"))

  /** Links the evaluator host for `config`'s class path into `dir`, as
   *  `snrepl-host` and its manifest, without the linker's work files, and
   *  prints the binary's path.
   */
  private def linkEvaluatorHost(config: NativeReplConfig, dir: Path): Int =
    Files.createDirectories(dir)
    val command = EvaluatorBackend.Host.linkCommand(config, dir.toAbsolutePath)
    val process = new ProcessBuilder(command.asJava).inheritIO().start()
    // With a timeout, waitFor reaps the process in a single-threaded snc
    while !process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) do ()
    val exit = process.exitValue()
    val stubs = dir.resolve("native").resolve("stubbed-methods.txt")
    if Files.isRegularFile(stubs) then
      Files.move(stubs, dir.resolve("stubbed-methods.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    NativeFiles.deleteRecursively(dir.resolve("native"))
    if exit == 0 then println(dir.resolve("snrepl-host").toAbsolutePath)
    exit

  /** The evaluator host that comes with the distribution, when this session
   *  links with the class path it was linked for: the distribution's
   *  libraries and the runtime, with no `--host-link-option`.
   */
  private def prebuiltHost(distribution: Option[SncDistribution], classpath: List[String], runtime: Path,
      options: Options): Option[Path] =
    def file(p: Path): Path =
      val abs = p.toAbsolutePath.normalize
      if Files.exists(abs) then abs.toRealPath() else abs
    for
      dist <- distribution
      host <- dist.evaluatorHost
      if options.hostLinkOptions.isEmpty
      if Files.isRegularFile(host.resolveSibling(host.getFileName.toString + ".snm"))
      if classpath.filter(_.nonEmpty).map(e => file(Paths.get(e))) == (dist.replClasspath :+ runtime).map(file)
    yield host

  /** The options lines are linked with, unless `chosen` sets them: no NIR
   *  optimizer (the program runs once), and multithreading, so that lines can
   *  start threads; setting it explicitly avoids the second reachability
   *  analysis that detecting it costs. The evaluator's lines take
   *  multithreading from its host.
   */
  private def defaultLinkOptions(chosen: List[String]): List[String] =
    List(
      List("--optimize", "--no-optimize") -> List("--no-optimize"),
      List("--multithreading") -> List("--multithreading", "true"))
      .collect { case (names, default) if !chosen.exists(names.contains) => default }
      .flatten

  /** The REPL's cache, `$XDG_CACHE_HOME/snc/repl` or `~/.cache/snc/repl`,
   *  where `snlink` keeps what it compiled between sessions, if no other REPL
   *  uses it. The REPL that uses it holds `lock/pid`; a lock whose process
   *  has ended is taken over.
   */
  private def cacheDir(): Option[Path] =
    NativeFiles.cacheDir("repl").flatMap: cache =>
      try
        val dir = Files.createDirectories(cache)
        val lock = dir.resolve("lock")
        def take(): Boolean =
          try
            Files.createDirectory(lock)
            Files.write(lock.resolve("pid"), scala.scalanative.posix.unistd.getpid().toString.getBytes)
            true
          catch case _: java.nio.file.FileAlreadyExistsException => false
        def holderIsGone: Boolean =
          try
            val pid = new String(Files.readAllBytes(lock.resolve("pid"))).trim.toInt
            scala.scalanative.posix.signal.kill(pid, 0) != 0
          catch case scala.util.control.NonFatal(_) => false
        if take() then Some(dir)
        else if holderIsGone then
          Files.deleteIfExists(lock.resolve("pid"))
          Files.deleteIfExists(lock)
          if take() then Some(dir) else None
        else None
      catch case scala.util.control.NonFatal(_) => None

  /** The entries of the `-classpath` options in `args`, and the other arguments. */
  private def takeClasspath(args: List[String]): (List[String], List[String]) = args match
    case ("-classpath" | "-cp" | "--class-path") :: cp :: more =>
      val (entries, others) = takeClasspath(more)
      (cp.split(File.pathSeparator).toList ++ entries, others)
    case arg :: more =>
      val (entries, others) = takeClasspath(more)
      (entries, arg :: others)
    case Nil => (Nil, Nil)
end NativeRepl
