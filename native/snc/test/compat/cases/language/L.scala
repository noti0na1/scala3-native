object L:
  implicit def intToString(i: Int): String = i.toString
  def s: String = 42
  class D extends scala.Dynamic
  val ops = List(1, 2) map (_ + 1)
