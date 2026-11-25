package dotty.tools
package repl

import dotc.ast.tpd
import dotc.config.Feature
import dotc.core.Constants.Constant
import dotc.core.Contexts.*
import dotc.core.Decorators.*
import dotc.core.Flags.*
import dotc.core.NameKinds.SimpleNameKind
import dotc.core.NameOps.*
import dotc.core.Names.TermName
import dotc.core.Phases.Phase
import dotc.core.StdNames.{nme, str}
import dotc.core.Symbols.*
import dotc.core.Types.MethodType
import dotc.report
import dotty.vendored.fansi

/** Rendering generated at compile time: each wrapper object gets a method
 *
 *  ```
 *  def $show(width: Int, height: Int, color: Boolean, offsets: Array[Int]): Array[String]
 *  ```
 *
 *  that renders the line's values (`shownValues`, in that order) with the
 *  REPL runtime's pretty-printer (`dotty.tools.repl.runtime.Show`), the i-th
 *  value starting at column `offsets(i)`. A backend without reflection calls
 *  it in the process that holds the values, after the wrapper has
 *  initialized.
 */
object ReplShow:

  val name: TermName = "$show".toTermName

  /** The runtime object that renders a value; it must be on the class path. */
  val runtimeShow: String = "dotty.tools.repl.runtime.Show"

  /** The values of a wrapper's module class that `$show` renders: its
   *  non-lazy vals and vars, as `ReplDriver` shows them, in the order they
   *  are declared.
   */
  def shownValues(wrapper: Symbol)(using Context): List[Symbol] =
    wrapper.info.decls.toList.filter: sym =>
      sym.isTerm && !sym.is(Method)
      && !sym.isOneOf(ParamAccessor | Private | Synthetic | Artifact | Module | Lazy | Erased)
      && sym.name.is(SimpleNameKind)

  /** The `offsets` argument of `$show` for `line`. */
  def offsets(line: ReplLine)(using Context): Array[Int] =
    val prefixes = line.values.map(v => v.symbol -> v.prefixLength).toMap
    shownValues(line.wrapper).map(prefixes.getOrElse(_, 0)).toArray

  /** The texts of `line`'s values, from the result of `$show`. As on the JVM,
   *  a value of type `Unit` is not shown.
   */
  def results(line: ReplLine, texts: Seq[String | Null])(using Context): ReplResult =
    val bySymbol = shownValues(line.wrapper).zip(texts).toMap
    ReplResult.Values(line.values.map: v =>
      bySymbol.get(v.symbol) match
        case Some(text: String) if v.symbol.info != defn.UnitType => Some(fansi.Str(text))
        case _ => None)

  /** Adds `$show` to the wrapper of the line being compiled, in the units
   *  that `ReplPhase` wrapped. It runs after `PostTyper` because `$show` is
   *  the REPL's code, to which `PostTyper`'s checks of user code (safe mode)
   *  do not apply.
   */
  class GenShow extends Phase:
    def phaseName: String = "replShow"

    override def description: String = "generate $show in REPL wrappers"

    protected def run(using Context): Unit =
      import tpd.*
      ctx.compilationUnit.tpdTree match
        case pkg @ PackageDef(pid, stats) =>
          pkg.getAttachment(ReplCompiler.ReplState).foreach: state =>
            val wrapperName = (str.REPL_SESSION_LINE + state.objectIndex).toTermName.moduleClassName
            val show = getModuleIfDefined(runtimeShow)
            if !show.exists then
              report.error(s"$runtimeShow is not on the class path: the REPL needs its runtime to show values")
            else
              val newStats = stats.mapConserve:
                case cdef @ TypeDef(`wrapperName`, tmpl: Template) =>
                  cpy.TypeDef(cdef)(rhs = cpy.Template(tmpl)(body = tmpl.body :+ showMethod(cdef.symbol.asClass, show)))
                case stat => stat
              ctx.compilationUnit.tpdTree = cpy.PackageDef(pkg)(pid, newStats)
        case _ =>

    private def showMethod(cls: ClassSymbol, show: Symbol)(using Context): tpd.Tree =
      import tpd.*
      val sym = newSymbol(cls, name, Method | Synthetic,
        MethodType(
          List("width", "height", "color", "offsets").map(_.toTermName),
          List(defn.IntType, defn.IntType, defn.BooleanType, defn.ArrayOf(defn.IntType)),
          defn.ArrayOf(defn.StringType)),
        coord = cls.span.endPos).entered
      // The runtime's renderer is not capture checked: with capture checking,
      // a value, which may hold capabilities, is passed to it as a pure `Any`
      def pure(value: Tree): Tree =
        if Feature.ccEnabled then ref(defn.Caps_unsafeAssumePure).appliedToType(defn.AnyType).appliedTo(value)
        else value
      DefDef(sym, paramss =>
        val width :: height :: color :: offsets :: Nil = paramss.head: @unchecked
        val texts = shownValues(cls).zipWithIndex.map: (value, i) =>
          val offset = offsets.select(nme.apply).appliedTo(Literal(Constant(i)))
          ref(show).select(nme.apply).appliedToArgs(List(pure(This(cls).select(value)), width, height, color, offset))
        JavaSeqLiteral(texts, TypeTree(defn.StringType))
      ).withSpan(cls.span.endPos)
  end GenShow
end ReplShow
