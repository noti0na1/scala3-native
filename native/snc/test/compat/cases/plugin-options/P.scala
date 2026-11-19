object Outer:
  object Inner:
    def main(args: Array[String]): Unit = println("inner")
  def main(args: Array[String]): Unit = Inner.main(args)
