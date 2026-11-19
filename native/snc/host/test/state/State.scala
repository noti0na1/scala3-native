// Expands snchost.Stateful.count four times in one compile: the module's
// state must survive the collections in between.
object State:
  def main(args: Array[String]): Unit =
    println(snchost.Stateful.count("a"))
    println(snchost.Stateful.count("b"))
    println(snchost.Stateful.count("c"))
    println(snchost.Stateful.count("d"))
