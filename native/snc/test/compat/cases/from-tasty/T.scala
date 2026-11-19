package p
case class Point(x: Int, y: Int):
  def +(o: Point) = Point(x + o.x, y + o.y)
object T:
  inline def twice(inline n: Int) = n * 2
  def main(args: Array[String]): Unit = println(Point(1, 2) + Point(twice(3), 4))
