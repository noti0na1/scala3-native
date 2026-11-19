package dotty.tools.dotc
package quoted

import scala.collection.mutable
import scala.reflect.ClassTag

import java.io.{PrintWriter, StringWriter}

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.TreeMapWithImplicits
import dotty.tools.dotc.core.Annotations.*
import dotty.tools.dotc.core.Constants.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Denotations.staticRef
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.FlatName
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.TypeErasure
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.quoted.*
import dotty.tools.dotc.typer.ImportInfo.withRootImports
import dotty.tools.dotc.util.SrcPos
import dotty.tools.dotc.reporting.Message
import dotty.tools.dotc.core.CyclicReference

/** Tree interpreter for metaprogramming constructs. It runs compiled code
 *  through `executor`.
 */
class Interpreter(pos: SrcPos, executor: MacroExecutor)(using Context):
  import Interpreter.*
  import tpd.*

  /** Local variable environment */
  type Env = Map[Symbol, Object]
  def emptyEnv: Env = Map.empty
  inline def env(using e: Env): e.type = e

  /** Returns the result of interpreting the code in the tree.
   *  Return Some of the result or None if the result type is not consistent with the expected type.
   *  Throws a StopInterpretation if the tree could not be interpreted or a runtime exception occurred.
   */
  final def interpret[T](tree: Tree)(using ct: ClassTag[T]): Option[T] =
    interpretTree(tree)(using emptyEnv) match {
      case obj: T => Some(obj)
      case obj =>
        // TODO upgrade to a full type tag check or something similar
        report.error(em"Interpreted tree returned a result of an unexpected type. Expected ${ct.runtimeClass} but was ${obj.getClass}", pos)
        None
    }

  /** Returns the result of interpreting the code in the tree.
   *  Throws a StopInterpretation if the tree could not be interpreted or a runtime exception occurred.
   */
  protected def interpretTree(tree: Tree)(using Env): Object = tree match {
    case Literal(Constant(value)) =>
      interpretLiteral(value)

    case tree: Ident if tree.symbol.is(Inline, butNot = Method) =>
      tree.tpe.widenTermRefExpr match
        case ConstantType(c) => c.value.asInstanceOf[Object]
        case _ => throw new StopInterpretation(em"${tree.symbol} could not be inlined", tree.srcPos)

    // TODO disallow interpreted method calls as arguments
    case Call(fn, args) =>
      if (fn.symbol.isConstructor)
        interpretNew(fn.symbol, args.flatten.map(interpretTree))
      else if (fn.symbol.is(Module))
        interpretModuleAccess(fn.symbol)
      else if (fn.symbol.is(Method) && fn.symbol.isStatic) {
        interpretedStaticMethodCall(fn.symbol.owner, fn.symbol, interpretArgs(args, fn.symbol.info))
      }
      else if fn.symbol.isStatic then
        assert(args.isEmpty)
        interpretedStaticFieldAccess(fn.symbol)
      else if (fn.qualifier.symbol.is(Module) && fn.qualifier.symbol.isStatic)
        if (fn.name == nme.asInstanceOfPM)
          interpretModuleAccess(fn.qualifier.symbol)
        else {
          interpretedStaticMethodCall(fn.qualifier.symbol.moduleClass, fn.symbol, interpretArgs(args, fn.symbol.info))
        }
      else if (env.contains(fn.symbol))
        env(fn.symbol)
      else if (tree.symbol.is(InlineProxy))
        interpretTree(tree.symbol.defTree.asInstanceOf[ValOrDefDef].rhs)
      else
        unexpectedTree(tree)

    case closureDef((ddef @ DefDef(_, ValDefs(arg :: Nil) :: Nil, _, _))) =>
      (obj: AnyRef) => interpretTree(ddef.rhs)(using env.updated(arg.symbol, obj))

    // Interpret `foo(j = x, i = y)` which it is expanded to
    // `val j$1 = x; val i$1 = y; foo(i = i$1, j = j$1)`
    case Block(stats, expr) => interpretBlock(stats, expr)
    case NamedArg(_, arg) => interpretTree(arg)

    case Inlined(_, bindings, expansion) => interpretBlock(bindings, expansion)

    case Typed(expr, _) =>
      interpretTree(expr)

    case SeqLiteral(elems, _) =>
      interpretVarargs(elems.map(e => interpretTree(e)))

    case _ =>
      unexpectedTree(tree)
  }

  private def interpretArgs(argss: List[List[Tree]], fnType: Type)(using Env): List[Object] = {
    def interpretArgsGroup(args: List[Tree], argTypes: List[Type]): List[Object] =
      assert(args.size == argTypes.size)
      val view =
        for (arg, info) <- args.lazyZip(argTypes) yield
          info match
            case _: ExprType => () => interpretTree(arg) // by-name argument
            case _ => interpretTree(arg) // by-value argument
      view.toList

    fnType.dealias match
      case fnType: MethodType =>
        val argTypes = fnType.paramInfos
        assert(argss.head.size == argTypes.size)
        val nonErasedArgs = argss.head.lazyZip(fnType.paramErasureStatuses).collect { case (arg, false) => arg }.toList
        val nonErasedArgTypes = fnType.paramInfos.lazyZip(fnType.paramErasureStatuses).collect { case (arg, false) => arg }.toList
        assert(nonErasedArgs.size == nonErasedArgTypes.size)
        interpretArgsGroup(nonErasedArgs, nonErasedArgTypes) ::: interpretArgs(argss.tail, fnType.resType)
      case fnType: AppliedType if defn.isContextFunctionType(fnType) =>
        val argTypes :+ resType = fnType.args: @unchecked
        interpretArgsGroup(argss.head, argTypes) ::: interpretArgs(argss.tail, resType)
      case fnType: PolyType => interpretArgs(argss, fnType.resType)
      case fnType: ExprType => interpretArgs(argss, fnType.resType)
      case _ =>
        assert(argss.isEmpty)
        Nil
  }

  private def interpretBlock(stats: List[Tree], expr: Tree)(using Env) = {
    var unexpected: Option[Object] = None
    val newEnv = stats.foldLeft(env)((accEnv, stat) => stat match
      case stat: ValDef =>
        accEnv.updated(stat.symbol, interpretTree(stat.rhs)(using accEnv))
      case stat =>
        if (unexpected.isEmpty)
          unexpected = Some(unexpectedTree(stat))
        accEnv
    )
    unexpected.getOrElse(interpretTree(expr)(using newEnv))
  }

  private def interpretLiteral(value: Any): Object =
    value.asInstanceOf[Object]

  private def interpretVarargs(args: List[Object]): Object =
    args.toSeq

  private def interpretedStaticMethodCall(moduleClass: Symbol, fn: Symbol, args: List[Object]): Object = {
    // Loading the module runs its initializer, which is macro code too
    try executor.callMethod(executor.loadModule(moduleClass, pos), fn, args, pos)
    catch {
      case ex: (StopInterpretation | CompilationUnit.SuspendException) =>
        throw ex
      case ex: scala.quoted.runtime.StopMacroExpansion =>
        throw ex
      case ex: dotty.tools.dotc.core.RecursionOverflow =>
        throw ex
      case MissingClassValidInCurrentRun(sym, origin) =>
        suspendOnMissing(sym, origin, pos)
      case targetException: Throwable =>
        val sw = new StringWriter()
        sw.write("Exception occurred while executing macro expansion.\n")
        if (!ctx.settings.Ydebug.value) {
          // The trace up to the macro implementation's frame, which names its
          // class and method as compiled (`$plus` for `+`, `A$B$`); without
          // one (an implementation inlined into its caller, an exception of a
          // module's initializer), up to the interpreter's and its executors'
          val className = fn.owner.binaryClassName
          val methodName = fn.name.mangledString
          val stackTrace = targetException.getStackTrace
          val end = stackTrace.lastIndexWhere { x =>
            x.getClassName == className && x.getMethodName == methodName
          }
          val shortStackTrace =
            if end >= 0 then stackTrace.take(end + 1)
            else stackTrace.takeWhile(!_.getClassName.startsWith("dotty.tools.dotc.quoted."))
          targetException.setStackTrace(shortStackTrace)
          targetException.printStackTrace(new PrintWriter(sw))

          targetException match
            case _: CyclicReference => sw.write("\nSee full stack trace using -Ydebug")
            case _ =>
        } else {
          targetException.printStackTrace(new PrintWriter(sw))
        }
        sw.write("\n")
        throw new StopInterpretation(sw.toString.toMessage, pos)
    }
  }

  private def interpretedStaticFieldAccess(sym: Symbol): Object =
    executor.staticField(sym, pos)

  private def interpretModuleAccess(fn: Symbol): Object =
    executor.loadModule(fn.moduleClass, pos)

  private def interpretNew(fn: Symbol, args: List[Object]): Object =
    executor.newInstance(fn, args, pos)

  private def unexpectedTree(tree: Tree): Object =
    throw new StopInterpretation(em"Unexpected tree could not be interpreted: ${tree.toString}", tree.srcPos)
end Interpreter

object Interpreter:
  /** Exception that stops interpretation if some issue is found */
  class StopInterpretation(val msg: Message, val pos: SrcPos) extends Exception

  object Call:
    import tpd.*
    /** Matches an expression that is either a field access or an application
     *  It returns a TermRef containing field accessed or a method reference and the arguments passed to it.
     */
    def unapply(arg: Tree)(using Context): Option[(RefTree, List[List[Tree]])] =
      Call0.unapply(arg).map((fn, args) => (fn, args.reverse))

    private object Call0 {
      def unapply(arg: Tree)(using Context): Option[(RefTree, List[List[Tree]])] = arg match {
        case Select(Call0(fn, args), nme.apply) if defn.isContextFunctionType(fn.tpe.widenDealias.finalResultType) =>
          Some((fn, args))
        case fn: Ident => Some((tpd.desugarIdent(fn).withSpan(fn.span), Nil))
        case fn: Select => Some((fn, Nil))
        case Apply(f @ Call0(fn, argss), args) => Some((fn, args :: argss))
        case TypeApply(Call0(fn, argss), _) => Some((fn, argss))
        case _ => None
      }
    }
  end Call

  enum ClassOrigin:
    case Classpath, Source

  object MissingClassValidInCurrentRun {
    def unapply(targetException: Throwable)(using Context): Option[(Symbol, ClassOrigin)] = {
      if !ctx.compilationUnit.isSuspendable then None
      else targetException match
        case _: NoClassDefFoundError | _: ClassNotFoundException =>
          val message = targetException.getMessage
          if message eq null then None
          else
            val className = message.replace('/', '.')
            val topLevelSym =
              if className.endsWith(str.MODULE_SUFFIX) then
                staticRef(className.stripSuffix(str.MODULE_SUFFIX).toTermName).symbol.moduleClass
              else
                staticRef(className.toTypeName).symbol
            val sym = if topLevelSym.exists then topLevelSym else nestedClassFromBinaryName(className)
            if sym.isDefinedInBinary then
              // i.e. the associated file is `.tasty`, if the macro classloader is not able to find the class,
              // possibly it indicates that it comes from a pipeline-compiled dependency.
              Some((sym, ClassOrigin.Classpath))
            else if sym.isDefinedInCurrentRun || (sym.exists && !sym.srcPos.span.exists) then
              // If the symbol does not a a position we assume that it came from the current run and it has an error
              Some((sym, ClassOrigin.Source))
            else
              None
        case _ => None
    }

    /** The class whose binary name is `className`, for classes nested in other classes or objects
     *  (e.g. `pkg.Outer$Inner` or `pkg.package$C`), which `staticRef` cannot resolve.
     */
    private def nestedClassFromBinaryName(className: String)(using Context): Symbol =
      val lastDot = className.lastIndexOf('.')
      val pkg =
        if lastDot < 0 then defn.EmptyPackageClass
        else staticRef(className.take(lastDot).toTermName, generateStubs = false, isPackage = true).symbol.moduleClass
      def search(cls: Symbol): Symbol =
        val name = cls.binaryClassName
        if name == className then cls
        else if !className.startsWith(name) then NoSymbol
        else cls.info.decls.iterator.filter(_.isClass).map(search).find(_.exists).getOrElse(NoSymbol)
      if !pkg.exists then NoSymbol
      else
        val outerName = termName(className.drop(lastDot + 1).takeWhile(_ != '$')).decode
        val outerClasses = List(pkg.info.decl(outerName.toTypeName).symbol, pkg.info.decl(outerName).symbol.moduleClass)
        outerClasses.filter(_.isClass).map(search).find(_.exists).getOrElse(NoSymbol)
  }

  def suspendOnMissing(sym: Symbol, origin: ClassOrigin, pos: SrcPos)(using Context): Nothing =
    if origin == ClassOrigin.Classpath then
      throw StopInterpretation(
          em"""Macro code depends on ${sym.showLocated} found on the classpath, but could not be loaded while evaluating the macro.
              |  This is likely because class files could not be found in the classpath entry for the symbol.
              |
              |  A possible cause is if the origin of this symbol was built with pipelined compilation;
              |  in which case, this problem may go away by disabling pipelining for that origin.
              |
              |  $sym is defined in file ${sym.associatedFile}""", pos)
    else if ctx.settings.YnoSuspendedUnits.value then
      throw StopInterpretation(em"suspension triggered by a dependency on missing ${sym.showLocated} not allowed with -Yno-suspended-units", pos)
    else
      ctx.compilationUnit.suspend(i"suspension triggered by a dependency on missing ${sym.showLocated}") // this throws a SuspendException
