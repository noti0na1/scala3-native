package a

import scala.annotation.{nowarn, tailrec}

/** Definitions of most kinds, for the API that the sbt phases extract. */
trait Shape[+T] {
  def area: Double
  def scaled(factor: Double = 1.0): Shape[T]
  protected def name: String
  type Unit <: AnyRef
  type Pair = (Int, String)
}

sealed abstract class Expr[-A]
final case class Lit(value: Int) extends Expr[Any]
case object Zero extends Expr[Any]
final class Plus[A](val left: Expr[A], val right: Expr[A]) extends Expr[A]

enum Color(val rgb: Int) {
  case Red extends Color(0xff0000)
  case Green extends Color(0x00ff00)
}

object Geometry {
  final val Pi = 3.14
  lazy val unit: Shape[Nothing] = ???
  var counter: Int = 0
  implicit val ordering: Ordering[Lit] = Ordering.by(_.value)
  given Conversion[Int, Lit] = Lit(_)

  def sum(xs: Int*): Int = xs.sum
  def byName(x: => Int): Int = x
  def poly[F[_], B <: AnyVal : Ordering](fb: F[B])(using ord: Ordering[B]): F[B] = fb
  def struct(x: { def close(): Unit }): Unit = ()
  def singleton(s: Geometry.type): s.type = s
  def tuple: (Int, String) = (1, "")
  def union(x: Int | String): Int & Any = 0
  def fun: Int => String = _.toString
  @deprecated("use sum", "1.0") def old(x: Int): Int = x
  @tailrec private def loop(n: Int): Int = if n == 0 then 0 else loop(n - 1)
  private[a] def packagePrivate: Int = loop(1)
  extension (x: Int) def twice: Int = x * 2
  inline def inlined(x: Int): Int = x + 1
  opaque type Meters = Double
  def meters(d: Double): Meters = d
}

class Outer {
  class Inner { def outer: Outer.this.type = Outer.this }
  object Nested
  def local: Int = {
    class Local { def x = 1 }
    new Local().x
  }
  val anon: Runnable = new Runnable { def run(): Unit = () }
}

@main def hello(args: String*): Unit = println(Geometry.sum(1, 2))

object Main {
  def main(args: Array[String]): Unit = ()
}
