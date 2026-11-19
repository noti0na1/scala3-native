object Text:
  def main(args: Array[String]): Unit =
    println(java.text.NumberFormat.getInstance(java.util.Locale.GERMANY).format(1234.5))
