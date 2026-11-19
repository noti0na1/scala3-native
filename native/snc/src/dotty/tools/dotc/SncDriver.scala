package dotty.tools.dotc

import java.io.{BufferedOutputStream, FileDescriptor, FileOutputStream, PrintStream, PrintWriter}

import dotty.tools.dotc.config.{CommandLineParser, CompilerCommand, PlatformHooks, Properties, SncDistribution}
import dotty.tools.dotc.config.Settings.Setting
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.toMessage
import dotty.tools.dotc.reporting.Reporter

/** The `snc` command (see README.md): `scalac`'s command line, plus
 *  a few options of its own, which `SncDriver.run` takes out before the
 *  compiler sees the arguments:
 *
 *  - `-scalanative`: emit NIR (the default, and the only backend);
 *  - `--java-home <dir>`: the JDK whose Java API to compile against (see
 *    `SncJdk` for where `snc` looks without it), which `snc` reads without
 *    running it (`classpath.JdkClassPath`);
 *  - `--about`: print what this `snc` is, as JSON, and exit;
 *  - `--events`, `--events-text`: report on standard output as an event
 *    stream (see `SncEvents`), and print text to standard error;
 *  - `--analysis` (with `--events` or `--events-text`): run the sbt phases
 *    and send Zinc's analysis as events too (see `SncAnalysis`).
 */
object SncDriver extends NativeDriver:

  /** Exit codes. `scalac` exits with 1 for all three kinds of failure. */
  object Exit:
    val Ok = 0
    /** Compilation errors, including warnings under `-Werror`. */
    val Errors = 1
    /** A bad command line or setup: unknown or invalid options, an unsupported
     *  option, a plugin that is not built in, no Java boot class path.
     */
    val Usage = 2
    /** The compiler crashed; the stack trace is on standard error. */
    val Crash = 3

  private enum Events:
    case Binary, Text

  /** The JDK given by `--java-home`. */
  private var javaHome: Option[String] = None

  /** The home of the JDK whose API the compiler reads, once it is found. */
  var jdkHome: Option[java.nio.file.Path] = None

  /** Takes `--java-home <dir>` (or `--java-home=<dir>`) out of `args`. */
  private def javaHomeOption(args: List[String]): Either[String, (Option[String], List[String])] =
    args match
      case Nil => Right((None, Nil))
      case "--java-home" :: Nil => Left("--java-home needs a directory")
      case "--java-home" :: dir :: rest => javaHomeOption(rest).map((_, other) => (Some(dir), other))
      case arg :: rest if arg.startsWith("--java-home=") =>
        javaHomeOption(rest).map((_, other) => (Some(arg.stripPrefix("--java-home=")), other))
      case arg :: rest => javaHomeOption(rest).map((home, other) => (home, arg :: other))

  private object SncCommand extends CompilerCommand:
    override def cmdName: String = "snc"
    override def versionMsg: String = ScalacCommand.versionMsg
    override def ifErrorsMsg: String = "  snc -help  gives more information"

  override protected def command: CompilerCommand = SncCommand

  /** Compiles as the command line says, and returns the exit code. */
  def run(args: Array[String]): Int =
    // @argfiles, as scalac expands them
    val expanded = args.toList.flatMap: arg =>
      if arg.startsWith("@") then CommandLineParser.expandArg(arg) else List(arg)
    val about = expanded.contains("--about")
    val events =
      if expanded.contains("--events") then Some(Events.Binary)
      else if expanded.contains("--events-text") then Some(Events.Text)
      else None
    val analysis = expanded.contains("--analysis")
    val sncOptions = Set("--about", "--events", "--events-text", "--analysis", "-scalanative")
    val compilerArgs = javaHomeOption(expanded.filterNot(sncOptions)) match
      case Right((home, other)) =>
        javaHome = home
        other.toArray
      case Left(message) =>
        System.err.println(s"snc: $message")
        return Exit.Usage

    if about then
      println(aboutJson)
      Exit.Ok
    else events match
      case None => guarded(compile(compilerArgs, None, analysis))
      case Some(format) =>
        // Standard output carries the events only
        val stdout = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16)
        val sink =
          if format == Events.Binary then new SncEvents.BinarySink(stdout)
          else new SncEvents.TextSink(stdout)
        val stderr = System.err
        System.setOut(stderr)
        val reporter = new SncEvents.EventReporter(sink, new PrintWriter(stderr, true))
        sink.emit(SncEvents.startEvent)
        val exit = Console.withOut(stderr)(guarded(compile(compilerArgs, Some((reporter, sink)), analysis)))
        sink.emit(SncEvents.doneEvent(exit, reporter.errorCount, reporter.warningCount))
        sink.close()
        exit

  /** Runs `body`, and prints a stack trace for an exception, as the JVM does
   *  for one that a main method throws.
   */
  private def guarded(body: => Int): Int =
    try body
    catch case ex: Throwable =>
      Console.out.flush()
      System.err.print("Exception in thread \"main\" ")
      ex.printStackTrace(System.err)
      Exit.Crash

  /** Compiles, reporting to the event stream's reporter and sink if there is
   *  one, and sending Zinc's analysis to the sink with `analysis`.
   */
  private def compile(args: Array[String], events: Option[(Reporter, SncEvents.Sink)], analysis: Boolean): Int =
    val rootCtx = initCtx.fresh
    events.foreach((reporter, _) => rootCtx.setReporter(reporter))
    if analysis && events.isEmpty then
      report.error("--analysis requires --events or --events-text")(using rootCtx)
      return Exit.Usage
    val jars = jarOutputs(args.toList)
    if jars.nonEmpty then
      for (option, jar) <- jars do
        report.error(s"$option $jar: snc writes to directories only, not to jars")(using rootCtx)
      return Exit.Usage
    setup(args, rootCtx) match
      case Some((files, ctx)) =>
        checkSetup(eventsMode = events.isDefined)(using ctx)
        if ctx.reporter.hasErrors then Exit.Usage
        else
          val compileCtx = events match
            case Some((_, sink)) if analysis =>
              val jdkArchive = (file: java.nio.file.Path) =>
                jdkHome.exists(dotty.tools.dotc.classpath.JdkClassPath.isArchive(_, file))
              ctx.fresh.setIncCallback(new SncAnalysis.Callback(sink, files, jdkArchive))
            case _ => ctx
          val result = doCompile(newCompiler(using compileCtx), files)(using compileCtx)
          if result.hasErrors then Exit.Errors else Exit.Ok
      case None =>
        // -help, -version, no sources, or a bad command line
        if rootCtx.reporter.hasErrors then Exit.Usage else Exit.Ok

  /** The options that name a jar to write to, with the jar. The compiler
   *  writes jars through a zip file system, which the native compiler does
   *  not have.
   */
  private def jarOutputs(args: List[String]): List[(String, String)] =
    val outputOptions = Set("-d", "-Xearly-tasty-output", "-Xpickle-write",
      "-Yearly-tasty-output", "-Ypickle-write")
    def isJar(path: String) = path.toLowerCase.endsWith(".jar")
    args match
      case option :: value :: rest if outputOptions(option) && isJar(value) =>
        (option, value) :: jarOutputs(rest)
      case arg :: rest =>
        val option = arg.takeWhile(_ != ':')
        val value = arg.drop(option.length + 1)
        if outputOptions(option) && arg.contains(':') && isJar(value) then (option, value) :: jarOutputs(rest)
        else jarOutputs(rest)
      case Nil => Nil

  /** Reports what snc does not support, or does differently, in the
   *  settings that `setup` accepted.
   */
  private def checkSetup(eventsMode: Boolean)(using Context): Unit =
    val s = ctx.settings
    def warnIfSet(settings: Setting[?]*)(why: String): Unit =
      for setting <- settings if setting.wasSetByUser do
        report.configurationWarning(s"${setting.name.takeWhile(_ != '<')} has no effect: $why".toMessage)

    setupJdk()
    if s.scalajs.value then
      report.error("-scalajs is not supported: snc has no Scala.js backend")
    if eventsMode && s.Yreporter.wasSetByUser then
      report.error("-Yreporter cannot be combined with --events or --events-text")
    // Plugins are loaded now, so that an -Xplugin path to a plugin that is not
    // built in is a usage error
    ctx.base.pluginDescriptions

    for (setting, prefix) <- List(s.jvmargs -> "-J", s.defines -> "-D"); value <- setting.value do
      report.configurationWarning(s"$prefix$value has no effect: snc does not run on a JVM".toMessage)
    warnIfSet(s.Yusejavacp)("snc does not run on a JVM, so there is no java.class.path")
    warnIfSet(s.pluginsDir)("snc does not load plugins; its plugins are built in")
    // -Yopt-specific and -Wopt are private to ScalaSettings: found by name
    val privateOptimizerSettings =
      s.allSettings.filter(st => st.name == "-Yopt-specific" || st.name == "-Wopt")
    warnIfSet(privateOptimizerSettings*)("snc emits no JVM bytecode")
    warnIfSet(
      s.opt, s.optInline, s.YoptInlineHeuristics, s.YoptLogInline,
      s.YoptAnalysisBudget, s.YoptTrace, s.XnoGenericSig, s.XverifySignatures,
      s.Xdumpclasses, s.YbackendParallelism, s.YbackendWorkerQueue,
      s.XuncheckedJavaOutputVersion, s.XmainClass, s.XjarCompressionLevel
    )("snc emits no JVM bytecode")
    warnIfSet(s.scalajsGenStaticForwardersForNonTopLevelObjects, s.scalajsMapSourceURI)(
      "snc has no Scala.js backend")
    checkScalaLibrary()
  end checkSetup

  /** Finds the JDK (`SncJdk`) and installs the class path of its API for
   *  `-release` (by default the JDK's own release), or reports why there is
   *  none. Without a JDK, `-javabootclasspath` alone is the Java API.
   */
  private def setupJdk()(using Context): Unit =
    import dotty.tools.dotc.classpath.JdkClassPath
    val release = Option(ctx.settings.javaOutputVersion.value).filter(_.nonEmpty).flatMap(_.toIntOption)
    SncJdk.find(javaHome, sys.env, release) match
      case Left(_) if javaHome.isEmpty && sys.env.get("JAVA_HOME").forall(_.isEmpty) &&
          ctx.settings.javabootclasspath.value.nonEmpty =>
        () // the Java API comes from -javabootclasspath alone
      case Left(message) => report.error(message)
      case Right(found) =>
        JdkClassPath.jdk(found.home).flatMap(JdkClassPath(_, release)) match
          case Left(message) => report.error(s"$message (${found.from})")
          case Right(cp) =>
            if ctx.settings.verbose.value then
              report.echo(s"compiling against the JDK at ${found.home} (${found.from})")
            PlatformHooks.jdkClassPath = _ => Some(cp)
            jdkHome = Some(found.home)

  /** Warns about a Scala library on the class path whose version is not the
   *  one snc is built from: the compiler expects its own library, and the
   *  NIR of Scala Native's scalalib must match it at link time.
   */
  private def checkScalaLibrary()(using Context): Unit =
    val expected = SncBuildInfo.scalaVersion
    for
      entry <- dotty.tools.dotc.classpath.ClassPath.split(ctx.settings.classpath.value)
      version <- scalaLibraryVersion(entry)
      if version != expected
    do
      report.configurationWarning(
        s"""the class path has scala-library $version ($entry), but snc is Scala $expected:
           |use scala-library $expected, and Scala Native's scalalib and scala3lib
           |$expected+${SncBuildInfo.scalaNativeVersion}""".stripMargin.toMessage)

  /** The version in `library.properties`, if `entry` is a scala-library jar. */
  private def scalaLibraryVersion(entry: String): Option[String] =
    val file = new java.io.File(entry)
    if !file.getName.startsWith("scala-library") || !file.getName.endsWith(".jar") || !file.isFile then None
    else
      scala.util.Try {
        val zip = new java.util.zip.ZipFile(file)
        try
          Option(zip.getEntry("library.properties")).flatMap: e =>
            val properties = new java.util.Properties
            val in = zip.getInputStream(e)
            try properties.load(in) finally in.close()
            Option(properties.getProperty("version.number"))
        finally zip.close()
      }.toOption.flatten

  /** What this snc is, for build tools to check: see README.md. */
  def aboutJson: String =
    import Json.*
    val plugins = PlatformHooks.builtinPluginsInfo.map: (className, plugin, enabled) =>
      obj(
        "name" -> str(plugin.name),
        "className" -> str(className),
        "description" -> str(plugin.description),
        "enabledByDefault" -> enabled.toString)
    val tasty =
      import dotty.tools.tasty.TastyFormat.*
      obj("major" -> MajorVersion.toString, "minor" -> MinorVersion.toString,
        "experimental" -> ExperimentalVersion.toString)
    val distribution = SncDistribution.find(SncExecutable.executableDir())
    obj(
      "name" -> str("snc"),
      "scalaVersion" -> str(SncBuildInfo.scalaVersion),
      "scalalibVersion" -> str(s"${SncBuildInfo.scalaVersion}+${SncBuildInfo.scalaNativeVersion}"),
      "compilerVersion" -> str(Properties.versionNumberString),
      "versionMessage" -> str(ScalacCommand.versionMsg),
      "scalaNativeVersion" -> str(scala.scalanative.nir.Versions.current),
      "nirVersion" -> obj(
        "compat" -> scala.scalanative.nir.Versions.compat.toString,
        "revision" -> scala.scalanative.nir.Versions.revision.toString),
      "tastyVersion" -> tasty,
      "backends" -> arr(List(str("native"))),
      "defaultBackend" -> str("native"),
      "plugins" -> arr(plugins),
      "eventsProtocol" -> str(SncEvents.protocol),
      "analysis" -> "true",
      "executable" -> opt(SncExecutable.executablePath()),
      "distribution" -> opt(distribution.map(_.version)),
      "repository" -> opt(distribution.flatMap(_.repositoryUri)))
end SncDriver
