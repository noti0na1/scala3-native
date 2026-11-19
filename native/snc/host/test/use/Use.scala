// Compiled by a host snc with the test extension loaded: the macros expand
// with the extension's code.
object Use:
  def main(args: Array[String]): Unit =
    println(snchost.Macros.describe(List(1, 2).map(_ + 1)))
    println(snchost.Macros.times10(args.length * 3 + 4))
