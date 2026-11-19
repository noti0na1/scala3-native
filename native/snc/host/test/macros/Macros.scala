package snchost

import scala.quoted.*

/** Macros whose implementations a host `snc` runs from an extension that it
 *  linked and loaded itself (README.md, "Testing"). Each
 *  expansion prints what it found, so that the compiler's output shows that
 *  the extension's code ran.
 */
object Macros:

  /** The tree of `x` counted and printed, a tree the macro builds, and facts
   *  computed with standard-library methods `snc` itself never calls.
   */
  inline def describe(inline x: Any): String = ${ describeImpl('x) }

  /** `x` with every integer literal multiplied by 10, by a `TreeMap`. */
  inline def times10(inline x: Int): Int = ${ times10Impl('x) }

  /** An expansion that throws an exception of the extension's own. */
  inline def boom(): Int = ${ boomImpl() }

  /** An expansion that reports an error and aborts. */
  inline def abort(): Int = ${ abortImpl() }

  /** An expansion that throws, from an implementation whose compiled name is
   *  encoded (`$plus$plus$plus`): the error's trace ends at its frame.
   */
  inline def boomOp(): Int = ${ +++() }

  def describeImpl(x: Expr[Any])(using Quotes): Expr[String] =
    import quotes.reflect.*
    // QuotesImpl: a tree built and printed by the compiler
    val built = Apply(Select.unique(Literal(StringConstant("ab")), "+"), List(Literal(IntConstant(1))))
    val shown = built.show
    val structure = Printer.TreeStructure.show(built)
    // An open trait the extension implements; the library's foldOverTree
    // calls back its foldTree for every node
    val counter = new TreeAccumulator[Int]:
      def foldTree(n: Int, tree: Tree)(owner: Symbol): Int = foldOverTree(n + 1, tree)(owner)
    val nodes = counter.foldTree(0, x.asTerm)(Symbol.spliceOwner)
    // A subclass of the open trait Ordering, called back by List.sorted
    val reverse = new Ordering[String]:
      def compare(a: String, b: String): Int = b.compareTo(a)
    val sorted = List("bb", "a", "ccc").sorted(reverse)
    // Standard-library methods snc itself never calls
    val perms = List(1, 2, 3).permutations.map(_.mkString).toList.sorted
    val powers = LazyList.iterate(1)(_ * 2).take(10).sum
    val name = "macro".capitalize
    // An exception the host throws, caught here
    val caught =
      try { List.empty[Int].head; "nothing" }
      catch case e: NoSuchElementException => "caught " + e.getClass.getName
    val result = s"tree: $shown | $structure | ${x.show}: $nodes nodes | sorted: $sorted | " +
      s"permutations: ${perms.mkString(",")} | powers: $powers | $name | $caught"
    println("describe: " + result)
    Expr(result)

  def times10Impl(x: Expr[Int])(using Quotes): Expr[Int] =
    import quotes.reflect.*
    val mapper = new TreeMap:
      override def transformTerm(tree: Term)(owner: Symbol): Term = tree match
        case Literal(IntConstant(n)) => Literal(IntConstant(n * 10))
        case _ => super.transformTerm(tree)(owner)
    val result = mapper.transformTerm(x.asTerm)(Symbol.spliceOwner).asExprOf[Int]
    println(s"times10: ${x.show} becomes ${result.show}")
    result

  def boomImpl()(using Quotes): Expr[Int] =
    throw new MacroBoom("boom from the extension")

  def abortImpl()(using Quotes): Expr[Int] =
    quotes.reflect.report.errorAndAbort("aborted by the extension")

  def +++()(using Quotes): Expr[Int] =
    throw new MacroBoom("boom from +++")

/** An exception class the extension defines, thrown to the compiler. */
final class MacroBoom(message: String) extends RuntimeException(message)
