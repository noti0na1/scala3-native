object N {
  def f(x: Int) = if (x > 0) "pos" else "neg"
  def g(xs: List[Int]) = for (x <- xs) println(x)
}
