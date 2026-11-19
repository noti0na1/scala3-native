package b

import a.{*, given}

class Square(side: Double) extends Shape:
  def area: Double = side * side

object Main:
  def main(args: Array[String]): Unit =
    Registry.register(Circle(1.0))
    Registry.register(Square(2.0))
    Registry.all.foreach(s => println(s.describe))
    println(Color.values.mkString(","))
    println(Meters(3.5).value)
    println(square(7) + nativeInline(1))
    println(topLevel(42))
    println(List(Circle(3), Circle(1), Circle(2)).sorted.map(_.r).mkString(" "))
    println(Box(20).map(_ + 1).value)
    println(Expr.eval(Expr.Add(Expr.Num(1), Expr.Num(2))))
    val c: Circle = Circle(2).copy(r = 5)
    println(c match { case Circle(r) => r })
