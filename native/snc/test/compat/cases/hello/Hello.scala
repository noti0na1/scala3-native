object Hello:
  def main(args: Array[String]): Unit =
    val xs = List(1, 2, 3).map(_ * 2)
    println(s"Hello, ${xs.sum}")
