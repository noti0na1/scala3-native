package dotty.tools
package repl

import dotc.ast.tpd
import dotc.core.Constants.Constant
import dotc.core.Contexts.*
import dotc.core.Decorators.*
import dotc.core.Flags.*
import dotc.core.NameOps.*
import dotc.core.Phases.Phase
import dotc.core.StdNames.{nme, str}
import dotc.core.Symbols.*
import dotc.core.Types.MethodType
import dotc.core.Scopes.newScope
import dotc.util.Spans.*
import dotc.transform.{Inlining, PickleQuotes, Pickler, PostInlining, Splicing, Staging}

/** The REPL's compiler in `snc`: NIR instead of JVM bytecode (GenNIR comes
 *  from the built-in Scala Native plugin), wrappers with `$show`, and a runner
 *  object per line (`GenRunner`): for the evaluator (`EvaluatorBackend`), an
 *  `EvalLine`; for the relink backend, a main object. The pickler phases are
 *  `NativeCompiler`'s, without sbt's.
 */
class NativeReplCompiler(evaluator: Boolean = false) extends ReplCompiler(generateShow = true):

  /** The lines whose wrappers' initialization does something, by index. */
  private val initializing = scala.collection.mutable.Set.empty[Int]

  /** Whether running line `index` does something: its wrapper has a
   *  statement that is not a definition, or a field (a val that is neither
   *  lazy nor a module), such as `val _ = println("hi")`, a statement of
   *  the wrapper, or `val (_, _) = f()`, a private field. A line without
   *  them defines only classes, objects, types and methods.
   */
  def initializes(index: Int): Boolean = initializing(index)

  override protected def frontendPhases: List[List[Phase]] =
    super.frontendPhases :+ List(new NativeReplCompiler.GenRunner(evaluator, initializing += _))

  override protected def picklerPhases: List[List[Phase]] =
    List(new Pickler) ::
    List(new Inlining) ::
    List(new PostInlining) ::
    List(new Staging) ::
    List(new Splicing) ::
    List(new PickleQuotes) ::
    Nil

  override protected def backendPhases: List[List[Phase]] = Nil

object NativeReplCompiler:

  /** The main object of the program that runs line `index`. */
  def runnerName(index: Int): String = s"rs$$runner$$$index"

  /** The runtime object that the runners call. */
  val runtimeRunner: String = "dotty.tools.repl.runtime.Runner"

  /** The runtime trait the evaluator's runners implement. */
  val runtimeEvalLine: String = "dotty.tools.repl.runtime.EvalLine"

  /** The runner of line `index` as the evaluator names its module (NIR). */
  def runnerModule(index: Int): String = runnerName(index) + "$"

  /** Gives the line being compiled a main object for `RelinkBackend`:
   *
   *  ```
   *  object rs$runner$N:
   *    def main(args: Array[String]): Unit =
   *      dotty.tools.repl.runtime.Runner.run(args,
   *        Array(i, j, ...), Array(() => rs$line$i, () => rs$line$j, ...),
   *        (w, h, c, o) => rs$line$N.$show(w, h, c, o))
   *  ```
   *
   *  where `i, j, ...` are the earlier lines that compiled and initialized,
   *  which the program runs again first (relink-and-replay). The main object
   *  is separate from the wrapper, whose initialization is the line itself.
   *
   *  For the evaluator, it is instead
   *
   *  ```
   *  object rs$runner$N extends dotty.tools.repl.runtime.EvalLine:
   *    def init(): Any = rs$line$N
   *    def show(w: Int, h: Int, c: Boolean, o: Array[Int]): Array[String] =
   *      rs$line$N.$show(w, h, c, o)
   *  ```
   */
  class GenRunner(evaluator: Boolean = false, initializes: Int => Unit = _ => ()) extends Phase:
    def phaseName: String = "replRunner"

    override def description: String = "generate the main object of a relinked REPL line"

    protected def run(using Context): Unit =
      import tpd.*
      ctx.compilationUnit.tpdTree match
        case pkg @ PackageDef(pid, stats) =>
          pkg.getAttachment(ReplCompiler.ReplState).foreach: state =>
            val index = state.objectIndex
            val wrapperName = (str.REPL_SESSION_LINE + index).toTermName.moduleClassName
            val initializing = stats.exists:
              case TypeDef(`wrapperName`, tmpl: Template) =>
                tmpl.body.exists:
                  case vdef: ValDef => !vdef.symbol.isOneOf(Lazy | Module)
                  case _: MemberDef | _: Import | _: Export => false
                  case tree => !tree.isEmpty
              case _ => false
            if initializing then initializes(index)
            if evaluator then
              val evalLine = getClassIfDefined(runtimeEvalLine)
              if evalLine.exists then
                val runnerDef = evalLineObject(index, evalLine.asClass, pkg.span.endPos)
                ctx.compilationUnit.tpdTree = cpy.PackageDef(pkg)(pid, stats ++ runnerDef)
            else
              val runner = getModuleIfDefined(runtimeRunner)
              if runner.exists then
                val earlier = state.validObjectIndexes.filter(_ != index).toList
                val runnerDef = mainObject(index, earlier, runner, pkg.span.endPos)
                ctx.compilationUnit.tpdTree = cpy.PackageDef(pkg)(pid, stats ++ runnerDef)
        case _ =>

    private def wrapper(index: Int)(using Context): Symbol =
      defn.EmptyPackageClass.info.decl((str.REPL_SESSION_LINE + index).toTermName).symbol

    /** The runner the evaluator calls (`EvalLine`). */
    private def evalLineObject(index: Int, evalLine: ClassSymbol, span: Span)(using Context): List[tpd.Tree] =
      import tpd.*
      val module = newCompleteModuleSymbol(defn.EmptyPackageClass, runnerName(index).toTermName,
        ModuleValCreationFlags | Synthetic, ModuleClassCreationFlags | Synthetic,
        List(defn.ObjectType, evalLine.typeRef), newScope, coord = span)
      module.entered
      module.moduleClass.entered
      val cls = module.moduleClass.asClass
      val current = wrapper(index)
      val show = current.moduleClass.info.decl(ReplShow.name).symbol
      for annot <- current.getAnnotation(defn.ExperimentalAnnot).orElse(current.moduleClass.getAnnotation(defn.ExperimentalAnnot)) do
        module.addAnnotation(annot)
        cls.addAnnotation(annot)
      val initSym = newSymbol(cls, "init".toTermName, Method | Synthetic,
        MethodType(Nil, defn.AnyType), coord = span).entered
      val showParams = List(defn.IntType, defn.IntType, defn.BooleanType, defn.ArrayOf(defn.IntType))
      val showSym = newSymbol(cls, "show".toTermName, Method | Synthetic,
        MethodType(List("width", "height", "color", "offsets").map(_.toTermName), showParams, defn.ArrayOf(defn.StringType)),
        coord = span).entered
      val initDef = DefDef(initSym, _ => ref(current)).withSpan(span)
      val showDef = DefDef(showSym, paramss => ref(current).select(show).appliedToArgs(paramss.head)).withSpan(span)
      ModuleDef(module, List(initDef, showDef)).trees.map(_.withSpan(span))

    private def mainObject(index: Int, earlier: List[Int], runner: Symbol, span: Span)(using Context): List[tpd.Tree] =
      import tpd.*
      val module = newCompleteModuleSymbol(defn.EmptyPackageClass, runnerName(index).toTermName,
        ModuleValCreationFlags | Synthetic, ModuleClassCreationFlags | Synthetic,
        List(defn.ObjectType), newScope, coord = span)
      module.entered
      module.moduleClass.entered
      val cls = module.moduleClass.asClass
      val mainSym = newSymbol(cls, nme.main, Method | Synthetic,
        MethodType(List(nme.args), List(defn.ArrayOf(defn.StringType)), defn.UnitType),
        coord = span).entered
      val current = wrapper(index)
      val show = current.moduleClass.info.decl(ReplShow.name).symbol
      // A wrapper may be experimental (`import language.experimental.x`)
      for annot <- current.getAnnotation(defn.ExperimentalAnnot).orElse(current.moduleClass.getAnnotation(defn.ExperimentalAnnot)) do
        module.addAnnotation(annot)
        cls.addAnnotation(annot)
      val main = DefDef(mainSym, paramss =>
        given Context = ctx.withOwner(mainSym)
        val anyFunction0 = defn.FunctionType(0).appliedTo(defn.AnyType)
        val showParams = List(defn.IntType, defn.IntType, defn.BooleanType, defn.ArrayOf(defn.IntType))
        val showFunction = defn.FunctionType(4).appliedTo(showParams :+ defn.ArrayOf(defn.StringType))
        val indexes = JavaSeqLiteral(earlier.map(i => Literal(Constant(i))), TypeTree(defn.IntType))
        // The closures are cast to the runtime's function types: under capture
        // checking, they capture the wrappers, which the runtime does not track
        val forces = JavaSeqLiteral(
          earlier.map(i => Lambda(MethodType(Nil, defn.AnyType), _ => ref(wrapper(i))).cast(anyFunction0)),
          TypeTree(anyFunction0))
        val showFn = Lambda(
          MethodType(
            List("width", "height", "color", "offsets").map(_.toTermName),
            showParams,
            defn.ArrayOf(defn.StringType)),
          params => ref(current).select(show).appliedToArgs(params)).cast(showFunction)
        ref(runner).select("run".toTermName).appliedToArgs(List(paramss.head.head, indexes, forces, showFn))
      ).withSpan(span)
      ModuleDef(module, List(main)).trees.map(_.withSpan(span))
  end GenRunner
end NativeReplCompiler
