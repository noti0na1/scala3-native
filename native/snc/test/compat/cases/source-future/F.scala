object F:
  def f(xs: List[Int]) = xs match
    case x :: _ => x
    case Nil => 0
  val v = (1: Int) match { case 1 => "one"; case _ => "other" }
  import scala.collection.mutable._
