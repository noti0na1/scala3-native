package dotty.tools.dotc
package quoted

import java.io.{PrintWriter, StringWriter}
import java.lang.reflect.{InvocationTargetException, Method => JLRMethod}

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.FlatName
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.TypeErasure
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.util.SrcPos
import dotty.tools.io.AbstractFileClassLoader

/** The macro executor of the JVM: it loads the compiled macro code with
 *  `classLoader0` and calls it by reflection.
 */
class ReflectiveMacroExecutor(classLoader0: ClassLoader)(using Context) extends MacroExecutor:
  import Interpreter.*

  /** The class loader of the macro code. A REPL line also finds the classes
   *  of earlier lines, in the output directory.
   */
  val classLoader: ClassLoader =
    if ctx.owner.topLevelClass.name.startsWith(str.REPL_SESSION_LINE) then
      new AbstractFileClassLoader(ctx.settings.outputDir.value, ctx.settings.javaOutputVersion.value, classLoader0)
    else classLoader0

  /** Runs `body` with the class loader as the thread's context class loader. */
  override def run[T](body: => T)(using Context): T =
    val oldContextClassLoader = Thread.currentThread().getContextClassLoader
    Thread.currentThread().setContextClassLoader(classLoader)
    try body
    finally Thread.currentThread().setContextClassLoader(oldContextClassLoader)

  def callMethod(inst: Object, fn: Symbol, args: List[Object], pos: SrcPos)(using Context): Object = {
    val clazz = inst.getClass
    val name = fn.name.asTermName
    val method = getMethod(clazz, name, paramsSig(fn), pos)
    invoke(method, inst, args, pos)
  }

  def staticField(sym: Symbol, pos: SrcPos)(using Context): Object = {
    val clazz = loadClass(sym.owner.fullName.toString, pos)
    val field = clazz.getField(sym.name.toString)
    field.get(null)
  }

  def newInstance(fn: Symbol, args: List[Object], pos: SrcPos)(using Context): Object = {
    val className = fn.owner.fullName.mangledString.replaceAll("\\$\\.", "\\$")
    val clazz = loadClass(className, pos)
    val constr = clazz.getConstructor(paramsSig(fn)*)
    constr.newInstance(args*).asInstanceOf[Object]
  }

  def loadModule(sym: Symbol, pos: SrcPos)(using Context): Object =
    if (sym.owner.is(Package)) {
      // is top level object
      val moduleClass = loadModuleClass(sym, sym.fullName.toString, pos)
      moduleClass.getField(str.MODULE_INSTANCE_FIELD).get(null)
    }
    else {
      // nested object in an object
      val clazz = loadModuleClass(sym, sym.binaryClassName, pos)
      clazz.getConstructor().newInstance().asInstanceOf[Object]
    }

  /** The class `name` of the module class `sym`. In a unit that cannot be
   *  suspended, such as a REPL line, a module that this run compiles has no
   *  class yet, and the inliner has already reported the macro as defined in
   *  the same source file or run: the expansion stops without another error,
   *  as with the native executor.
   */
  private def loadModuleClass(sym: Symbol, name: String, pos: SrcPos)(using Context): Class[?] =
    try loadClass(name, pos)
    catch
      case _: ClassNotFoundException if sym.isDefinedInCurrentRun && ctx.reporter.errorsReported =>
        throw new scala.quoted.runtime.StopMacroExpansion

  private def loadClass(name: String, pos: SrcPos)(using Context): Class[?] =
    try classLoader.loadClass(name)
    catch
      case MissingClassValidInCurrentRun(sym, origin) =>
        suspendOnMissing(sym, origin, pos)

  private def getMethod(clazz: Class[?], name: Name, paramClasses: List[Class[?]], pos: SrcPos)(using Context): JLRMethod =
    try clazz.getMethod(name.toString, paramClasses*)
    catch {
      case _: NoSuchMethodException =>
        val msg = em"Could not find method ${clazz.getCanonicalName}.$name with parameters ($paramClasses%, %)"
        throw new StopInterpretation(msg, pos)
      case MissingClassValidInCurrentRun(sym, origin) =>
        suspendOnMissing(sym, origin, pos)
    }

  /** Calls `method`, and throws what the macro code throws, unwrapped. */
  private def invoke(method: JLRMethod, inst: Object, args: List[Object], pos: SrcPos)(using Context): Object =
    try method.invoke(inst, args*)
    catch {
      case ex: InvocationTargetException =>
        throw ex.getTargetException
      case ex: RuntimeException =>
        val sw = new StringWriter()
        sw.write("A runtime exception occurred while executing macro expansion\n")
        sw.write(ex.getMessage)
        sw.write("\n")
        ex.printStackTrace(new PrintWriter(sw))
        sw.write("\n")
        throw new StopInterpretation(sw.toString.toMessage, pos)
    }

  /** List of classes of the parameters of the signature of `sym` */
  private def paramsSig(sym: Symbol)(using Context): List[Class[?]] = {
    def paramClass(param: Type): Class[?] = {
      def arrayDepth(tpe: Type, depth: Int): (Type, Int) = tpe match {
        case JavaArrayType(elemType) => arrayDepth(elemType, depth + 1)
        case _ => (tpe, depth)
      }
      def javaArraySig(tpe: Type): String = {
        val (elemType, depth) = arrayDepth(tpe, 0)
        val sym = elemType.classSymbol
        val suffix =
          if (sym == defn.BooleanClass) "Z"
          else if (sym == defn.ByteClass) "B"
          else if (sym == defn.ShortClass) "S"
          else if (sym == defn.IntClass) "I"
          else if (sym == defn.LongClass) "J"
          else if (sym == defn.FloatClass) "F"
          else if (sym == defn.DoubleClass) "D"
          else if (sym == defn.CharClass) "C"
          else "L" + javaSig(elemType) + ";"
        ("[" * depth) + suffix
      }
      def javaSig(tpe: Type): String = tpe match {
        case tpe: JavaArrayType => javaArraySig(tpe)
        case _ =>
          // Take the flatten name of the class and the full package name
          val pack = tpe.classSymbol.topLevelClass.owner
          val packageName = if (pack == defn.EmptyPackageClass) "" else s"${pack.fullName}."
          packageName + tpe.classSymbol.fullNameSeparated(FlatName).toString
      }

      val sym = param.classSymbol
      if (sym == defn.BooleanClass) classOf[Boolean]
      else if (sym == defn.ByteClass) classOf[Byte]
      else if (sym == defn.CharClass) classOf[Char]
      else if (sym == defn.ShortClass) classOf[Short]
      else if (sym == defn.IntClass) classOf[Int]
      else if (sym == defn.LongClass) classOf[Long]
      else if (sym == defn.FloatClass) classOf[Float]
      else if (sym == defn.DoubleClass) classOf[Double]
      else java.lang.Class.forName(javaSig(param), false, classLoader)
    }
    def getExtraParams(tp: Type): List[Type] = tp.widenDealias match {
      case tp: AppliedType if defn.isContextFunctionType(tp) =>
        // Call context function type direct method
        tp.args.init.map(arg => TypeErasure.erasure(arg)) ::: getExtraParams(tp.args.last)
      case _ => Nil
    }
    val extraParams = getExtraParams(sym.info.finalResultType)
    val allParams = TypeErasure.erasure(sym.info) match {
      case meth: MethodType => meth.paramInfos ::: extraParams
      case _ => extraParams
    }
    allParams.map(paramClass)
  }

end ReflectiveMacroExecutor
