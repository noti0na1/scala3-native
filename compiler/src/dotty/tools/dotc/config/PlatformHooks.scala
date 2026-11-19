package dotty.tools.dotc.config

/** What the compiler does differently when it is built with Scala Native.
 *
 *  This is the JVM implementation. The Scala Native build of the compiler
 *  (`scala3-compiler-native`) replaces this file with one for a platform
 *  without runtime reflection, class loading or `java.lang.management`.
 */
object PlatformHooks:

  /** Whether the compiler runs on a JVM. Code that needs the JVM is guarded
   *  with `if PlatformHooks.isJvm then ... else ...`: the condition is a
   *  constant, so `FirstTransform` keeps only one of the branches, and the
   *  Scala Native linker never sees the other.
   */
  final val isJvm = true

  /** A new instance of the class named `className`, from its no-argument
   *  constructor. Throws a `ReflectiveOperationException` when that fails.
   */
  def newInstance(className: String): Any =
    Class.forName(className).nn.getDeclaredConstructor().nn.newInstance()

  /** The parameter names of the first public constructor of the class named
   *  `className`, if the class can be inspected.
   */
  def constructorParameterNames(className: String): Option[List[String]] =
    try
      val cls = Class.forName(className).nn
      Some(cls.getConstructors.nn.head.nn.getParameters.nn.map(_.nn.getName.nn).toList)
    catch case _: ClassNotFoundException => None

  /** A compiler with the default phases, as `-Xshow-phases` lists them. */
  def newCompiler(): dotty.tools.dotc.Compiler = new dotty.tools.dotc.Compiler

  /** The MD5 digest of `bytes`. */
  def md5(bytes: Array[Byte]): Array[Byte] =
    java.security.MessageDigest.getInstance("MD5").nn.digest(bytes).nn

  /** The plugins named by `-Xplugin` paths and found in `dirs`, loaded by
   *  class loaders.
   */
  def loadPlugins(paths: List[List[dotty.tools.io.Path]], dirs: List[dotty.tools.io.Path],
      ignoring: List[String])(using dotty.tools.dotc.core.Contexts.Context): List[scala.util.Try[dotty.tools.dotc.plugins.Plugin]] =
    dotty.tools.dotc.plugins.Plugin.loadAllFrom(paths, dirs, ignoring)

  /** The `MiniPhase` methods that the miniphase class `className` redefines,
   *  or null if unknown. Only the native compiler needs it: here, `MegaPhase`
   *  finds them by reflection.
   */
  def redefinedHooks(className: String): Set[String] | Null = null

  /** The Java platform's classes when the compiler does not run on a JVM;
   *  on a JVM, `ClassPath.jrt` reads the running JDK's.
   */
  def jdkClassPath(release: Option[String]): Option[dotty.tools.dotc.classpath.ClassPath] = None

  /** The executor of macro code: it loads the code with the macro class
   *  loader and calls it by reflection.
   */
  def macroExecutor(using dotty.tools.dotc.core.Contexts.Context): dotty.tools.dotc.quoted.MacroExecutor =
    new dotty.tools.dotc.quoted.ReflectiveMacroExecutor(dotty.tools.dotc.core.MacroClassLoader.fromContext)

  /** The timer `ImportSuggestions` uses to stop slow implicit searches. */
  type Timer = java.util.Timer
  type TimerTask = java.util.TimerTask

end PlatformHooks
