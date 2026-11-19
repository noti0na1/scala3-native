import scala.quoted.*

object Outer:
  object Inner:
    inline def boom(inline x: Int): Int = ${ boomImpl('x) }

    def boomImpl(x: Expr[Int])(using Quotes): Expr[Int] = helper(x)

    private def helper(x: Expr[Int])(using Quotes): Expr[Int] =
      throw new IllegalStateException("boom")
