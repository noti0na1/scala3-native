import scala.language.implicitConversions
object W:
  @deprecated("use g", "1.0") def f = 1
  def g = f
  def h(xs: Any) = xs match
    case _: List[Int] => 1
    case _ => 2
  def k = 1.toString()
  enum Color { case Red, Green }
  def m(c: Color) = c match
    case Color.Red => 0
