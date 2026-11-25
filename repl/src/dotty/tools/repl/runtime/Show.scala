package dotty.tools.repl.runtime

import scala.util.control.NonFatal

import dotty.vendored.fansi
import dotty.vendored.pprint.PPrinter

/** Renders the values of REPL lines in the process that holds them: the
 *  `$show` method that the REPL generates in each line's wrapper
 *  (`dotty.tools.repl.ReplShow`) calls it. It uses no reflection, so it also
 *  runs on Scala Native.
 */
object Show:

  /** Classes rendered with their `toString` instead of by pprint. */
  private val forcedToStringClasses = Set(
    "scala.collection.mutable.StringBuilder", // not technically needed but quite ugly to print as an iterable of characters
    "scala.xml.Elem" // is a Seq that contains itself, https://github.com/scala/scala3/issues/25691
  )

  private val wrapperPrefix = "rs$line$"

  /** Renders `value` as the REPL shows it, `width` columns wide and at most
   *  `height` lines high, its first line starting at column `initialOffset`,
   *  with ANSI colors if `color`.
   */
  def apply(value: Any, width: Int, height: Int, color: Boolean, initialOffset: Int): String =
    val rendered =
      if value != null && forcedToStringClasses(value.getClass.getName) then fansi.Str(value.toString)
      else
        PPrinter.Color.applyWithProductToString(
          value,
          width = width,
          height = height,
          indent = 2,
          initialOffset = initialOffset,
          escapeUnicode = false,
          showFieldNames = true,
          useProductToString = hasUserToString
        )
    val text = withoutWrapperPrefix(if color then rendered else fansi.Str(rendered.plainText))
    if color then text.render else text.plainText

  /** Drops the name of a REPL wrapper at the start, as in `rs$line$2$Foo@1f`. */
  private def withoutWrapperPrefix(s: fansi.Str): fansi.Str =
    val plain = s.plainText
    if plain.startsWith(wrapperPrefix) then
      val dropLen = wrapperPrefix.length + plain.drop(wrapperPrefix.length).takeWhile(c => c.isDigit || c == '$').length
      s.substring(dropLen)
    else s

  /** Whether a product's class defines its own `toString` instead of the
   *  one synthesized for case classes (`ScalaRunTime._toString`). pprint shows
   *  the fields of products without one. Decided once per class, from the
   *  first value seen.
   */
  private val hasUserToString: Any => Boolean =
    val known = scala.collection.mutable.HashMap.empty[Class[?], Boolean]
    value => value match
      case p: Product =>
        known.synchronized:
          known.getOrElseUpdate(p.getClass,
            try p.toString != scala.runtime.ScalaRunTime._toString(p)
            catch case NonFatal(_) => false)
      case _ => false
end Show
