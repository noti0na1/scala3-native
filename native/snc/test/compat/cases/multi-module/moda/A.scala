package a

/** A library module: its users see only its TASTy and NIR. */
trait Shape:
  def area: Double
  def describe: String = s"${getClass.getSimpleName} with area $area"

final case class Circle(r: Double) extends Shape:
  def area: Double = math.Pi * r * r

enum Color:
  case Red, Green, Blue

opaque type Meters = Double
object Meters:
  def apply(d: Double): Meters = d
  extension (m: Meters) def value: Double = m

inline def square(x: Int): Int = x * x

def topLevel(n: Int): String = "n=" + n

given Ordering[Circle] = Ordering.by(_.r)

object Registry:
  private var shapes = List.empty[Shape]
  def register(s: Shape): Unit = shapes ::= s
  def all: List[Shape] = shapes.reverse

class Box[+T](val value: T):
  def map[U](f: T => U): Box[U] = Box(f(value))

sealed trait Expr
object Expr:
  case class Num(n: Int) extends Expr
  case class Add(l: Expr, r: Expr) extends Expr
  def eval(e: Expr): Int = e match
    case Num(n) => n
    case Add(l, r) => eval(l) + eval(r)

@scala.scalanative.annotation.alwaysinline def nativeInline(x: Int): Int = x + 1
