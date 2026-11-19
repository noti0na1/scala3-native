package b

import a.*

/** Uses A.scala: class dependencies by inheritance and member references,
 *  and pattern matching on a sealed class.
 */
class Square(side: Double) extends Shape[Int] {
  def area: Double = side * side
  def scaled(factor: Double): Shape[Int] = new Square(side * factor)
  protected def name: String = "square"
  type Unit = String
}

object Eval {
  def eval(e: Expr[Any]): Int = e match {
    case Lit(v) => v
    case Zero => 0
    case p: Plus[Any] @unchecked => eval(p.left) + eval(p.right)
  }
  def color: Color = Color.Red
  def meters: Geometry.Meters = Geometry.meters(1.0)
  def lits: List[Lit] = List(Lit(2), Lit(1)).sorted(using Geometry.ordering)
  def area(s: Shape[?]): Double = s.area
  def twice: Int = { import Geometry.twice; 3.twice }
}

trait Visitor {
  def visit(o: Outer): Unit = ()
}
