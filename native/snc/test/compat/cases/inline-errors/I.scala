import scala.compiletime.error
object I:
  inline def check(inline b: Boolean): Unit = inline if b then () else error("check failed")
  inline def nested(x: Int): Int = x / 0 + "s".length
  val a = check(1 == 2)
  val b = nested(1)
  sealed trait T
  case class A() extends T
  case class B() extends T
  def m(t: T) = t match
    case A() => 1
  def n(o: Option[Int]) = o match
    case Some(x) => x
    case Some(1) => 2
    case None => 0
