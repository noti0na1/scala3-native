package j
object S:
  val cb: J.Callback = s => println(s)
  def main(args: Array[String]): Unit = cb.call(J.twice(21).toString)
