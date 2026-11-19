package dotty.tools.dotc.config

import java.util.Properties

import scala.util.{Failure, Success, Try}

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.plugins.{MissingPluginException, Plugin, PluginLoadException}
import dotty.tools.dotc.report
import dotty.tools.io.Path

/** What the compiler does differently when it is built with Scala Native.
 *
 *  This file replaces `compiler/src/dotty/tools/dotc/config/PlatformHooks.scala`
 *  in the `scala3-compiler-native` build, for a platform without runtime
 *  reflection, class loading or the JVM's management beans.
 */
object PlatformHooks:

  /** Whether the compiler runs on a JVM. Code that needs the JVM is guarded
   *  with `if PlatformHooks.isJvm then ... else ...`: the condition is a
   *  constant, so `FirstTransform` keeps only one of the branches, and the
   *  Scala Native linker never sees the other.
   */
  final val isJvm = false

  /** Classes cannot be loaded by name in the native compiler. */
  def newInstance(className: String): Any =
    throw new ClassNotFoundException(
      s"$className: the native compiler cannot load classes by name")

  /** Constructors cannot be inspected in the native compiler. */
  def constructorParameterNames(className: String): Option[List[String]] = None

  /** The Java platform's classes, from a JDK installation that the compiler
   *  reads without running it (`classpath.JdkClassPath`), for the Java
   *  release given by `-release` (by default the JDK's own). `snc` sets it
   *  from `--java-home` or the JDK it finds.
   */
  var jdkClassPath: Option[String] => Option[dotty.tools.dotc.classpath.ClassPath] = _ => None

  /** A compiler with the default phases, as `-Xshow-phases` lists them. */
  def newCompiler(): dotty.tools.dotc.Compiler = new dotty.tools.dotc.NativeCompiler

  /** The MD5 digest of `bytes`. */
  def md5(bytes: Array[Byte]): Array[Byte] = dotty.tools.dotc.util.Md5.digest(bytes)

  /** The plugins named by `-Xplugin` paths, then the built-in plugins that
   *  are enabled by default. The native compiler cannot load classes, so it
   *  reads the class name from each plugin's descriptor and instantiates its
   *  own copy of that plugin; a plugin it does not have is an error. Each
   *  plugin class is instantiated once, and plugins named in `ignoring`
   *  (`-Xplugin-disable`) are left out. Plugin directories (`-Xpluginsdir`)
   *  are not searched.
   */
  def loadPlugins(paths: List[List[Path]], dirs: List[Path], ignoring: List[String])(using Context): List[Try[Plugin]] =
    val seen = scala.collection.mutable.Set.empty[String]
    def instantiate(className: String, newPlugin: () => Plugin): Option[Try[Plugin]] =
      if !seen.add(className) then None
      else
        val plugin = newPlugin()
        if ignoring.contains(plugin.name) then
          Some(Failure(new PluginLoadException(plugin.name, s"Disabling plugin ${plugin.name}")))
        else Some(Success(plugin))
    val explicit = paths.flatMap: path =>
      Try(descriptorClass(path)).flatten match
        case Success(className) =>
          builtinPlugins.get(className) match
            case Some(BuiltinPlugin(newPlugin, _)) => instantiate(className, newPlugin)
            case None =>
              report.error(
                s"""Cannot load plugin $className from ${path.mkString(java.io.File.pathSeparator)}:
                   |the native compiler cannot load plugins, and this one is not built in.
                   |Built-in plugins: $builtinPluginList""".stripMargin)
              None
        case Failure(e) => Some(Failure(e))
    val defaults = builtinPlugins.toList.sortBy(_._1).flatMap:
      case (className, BuiltinPlugin(newPlugin, true)) =>
        instantiate(className, newPlugin).filter(_.isSuccess)
      case _ => None
    explicit ++ defaults

  /** The built-in plugins, as (class name, new instance, enabled by default). */
  def builtinPluginsInfo: List[(String, Plugin, Boolean)] =
    builtinPlugins.toList.sortBy(_._1).map((className, p) => (className, p.newPlugin(), p.enabledByDefault))

  private def builtinPluginList: String =
    if builtinPlugins.isEmpty then "none"
    else builtinPlugins.keys.toList.sorted.mkString(", ")

  /** The `MiniPhase` methods that the miniphase class `className` redefines,
   *  which `MegaPhase` finds by reflection on the JVM. The tables come from
   *  reflection on the JVM build's classes (see `NativeBuild`). For a class
   *  they do not list, null: that miniphase then takes part for every tree.
   */
  def redefinedHooks(className: String): Set[String] | Null =
    miniPhaseHooks.getOrElse(className, null)

  private val miniPhaseHooks =
    scala.collection.mutable.Map.from(dotty.tools.dotc.transform.MiniPhaseHooks.table)

  /** Adds the hook table of miniphases built into the compiler, such as a
   *  plugin's. Main classes call it before they compile.
   */
  def registerMiniPhaseHooks(table: Iterable[(String, Set[String])]): Unit =
    miniPhaseHooks ++= table

  /** A plugin built into the native compiler, and whether it is enabled
   *  without an `-Xplugin` path.
   */
  private case class BuiltinPlugin(newPlugin: () => Plugin, enabledByDefault: Boolean)

  /** The plugins built into the native compiler, by class name. */
  private val builtinPlugins = scala.collection.mutable.Map.empty[String, BuiltinPlugin]

  /** Builds the plugin whose class is `className` into the compiler. An
   *  `-Xplugin` path to the plugin's jar selects it, as on the JVM; with
   *  `enabledByDefault`, it is also enabled without one, unless
   *  `-Xplugin-disable` names it. Main classes call it before they compile.
   */
  def registerPlugin(className: String, newPlugin: () => Plugin, enabledByDefault: Boolean = false): Unit =
    builtinPlugins(className) = BuiltinPlugin(newPlugin, enabledByDefault)

  /** The `pluginClass` of the first plugin descriptor among `path`'s jars and
   *  directories.
   */
  private def descriptorClass(path: List[Path]): Try[String] =
    val descriptorName = "plugin.properties"
    def read(in: java.io.InputStream | Null): Option[String] =
      if in == null then None
      else
        val properties = new Properties
        try properties.load(in) finally in.close()
        Option(properties.getProperty("pluginClass"))
    val found = path.iterator.flatMap: p =>
      if p.isDirectory then
        val file = p / descriptorName
        if file.isFile then read(new java.io.FileInputStream(file.jpath.toFile)) else None
      else if p.isFile then
        val jar = new java.util.zip.ZipFile(p.jpath.toFile)
        try
          val entry = jar.getEntry(descriptorName)
          if entry == null then None else read(jar.getInputStream(entry))
        finally jar.close()
      else None
    found.nextOption() match
      case Some(className) => Success(className)
      case None => Failure(new MissingPluginException(path))

  /** The executor of macro code. The native compiler has no class loader: it
   *  runs the macro code in `quoted.MacroRegistry`, which a main class
   *  registers before compiling or a `quoted.MacroLoader` links and loads
   *  when an expansion needs it (`snc-host`). Without either, macro expansion
   *  is an error.
   */
  def macroExecutor(using Context): dotty.tools.dotc.quoted.MacroExecutor = registryExecutor

  private val registryExecutor = new dotty.tools.dotc.quoted.RegistryMacroExecutor

  /** The extension of the file that the backend writes for each class, which
   *  the sbt phases report to Zinc as the class's product: the native compiler
   *  writes NIR (with the Scala Native plugin) and no class files.
   */
  final val classFileExtension = ".nir"

  /** The timer `ImportSuggestions` uses to stop slow implicit searches, in
   *  place of `java.util.Timer`. The native compiler is single-threaded, so
   *  no task ever runs: a search for one suggestion is not cut short, but
   *  suggestions still stop at their overall deadline.
   */
  class Timer:
    def schedule(task: TimerTask, delayMillis: Long): Unit = ()
    def cancel(): Unit = ()

  abstract class TimerTask extends Runnable:
    /** Stops the task from running; it never runs, so always true. */
    def cancel(): Boolean = true

end PlatformHooks
