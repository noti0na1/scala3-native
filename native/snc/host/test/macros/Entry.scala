package snchost

import scala.quoted.{Expr, Quotes}

import dotty.tools.dotc.quoted.{MacroInvokers, MacroRegistry}

/** The extension's entry: `snc --load-extension <bundle> snchost.Entry$` runs
 *  it before compiling. It registers the implementations of `Macros` with the
 *  compiler's `MacroRegistry`, as the entry tables that the macro test rig and
 *  `MacroGlue` generate do, so that the compiler's interpreter runs them.
 */
object Entry extends (() => Unit):
  def apply(): Unit =
    MacroRegistry.register("", keys.mkString("\n"), Invokers)
    // Stack traces name the extension's methods
    val frame = new Exception().getStackTrace.find(_.getClassName.startsWith("snchost"))
    val where = frame.fold("?")(f => f.getClassName + "." + f.getMethodName)
    println(s"extension: registered ${keys.size} macro entries in $where")

  private val keys = Seq(
    "M snchost.Macros$",
    "C snchost.Macros$ describeImpl 2 Lscala.quoted.Expr;,Lscala.quoted.Quotes;",
    "C snchost.Macros$ times10Impl 2 Lscala.quoted.Expr;,Lscala.quoted.Quotes;",
    "C snchost.Macros$ boomImpl 1 Lscala.quoted.Quotes;",
    "C snchost.Macros$ abortImpl 1 Lscala.quoted.Quotes;",
    "C snchost.Macros$ $plus$plus$plus 1 Lscala.quoted.Quotes;")

  /** A subclass of the compiler's MacroInvokers, which the host declares
   *  open: the compiler calls it.
   */
  private object Invokers extends MacroInvokers:
    def invoke(index: Int, receiver: Object, args: Array[Object]): Object =
      def quotes(i: Int) = args(i).asInstanceOf[Quotes]
      index match
        case 0 => Macros
        case 1 => Macros.describeImpl(args(0).asInstanceOf[Expr[Any]])(using quotes(1))
        case 2 => Macros.times10Impl(args(0).asInstanceOf[Expr[Int]])(using quotes(1))
        case 3 => Macros.boomImpl()(using quotes(0))
        case 4 => Macros.abortImpl()(using quotes(0))
        case 5 => Macros.+++()(using quotes(0))
        case _ => throw new IllegalArgumentException(s"no macro entry $index")
