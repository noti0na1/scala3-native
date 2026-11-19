object M {
  def f(x: Int) = x
  val g = f _
  def proc { println("procedure syntax") }
  val sym = 'oldSymbol
  for (i <- 1 to 2) yield i
}
