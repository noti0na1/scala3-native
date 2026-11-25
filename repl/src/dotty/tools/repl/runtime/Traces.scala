package dotty.tools.repl.runtime

import dotty.vendored.fansi

/** Renders an exception that a REPL line threw, where the line ran, as the
 *  JVM REPL does (`StackTraceOps.formatStackTracePrefix`): like
 *  `Throwable.printStackTrace`, with the frames of the REPL's own code (the
 *  initialization of the line's wrapper and what runs it) left out. As
 *  there, causes come before suppressed exceptions, and a suppressed
 *  exception's "... n more" counts the frames it shares with the frames of
 *  its enclosing exception that are shown, where `printStackTrace` counts
 *  those it shares with that exception's whole trace.
 */
object Traces:

  /** `t` and its causes and suppressed exceptions, each with the frames above
   *  the first for which `stop` holds; ANSI colors if `color`.
   */
  def render(t: Throwable, stop: StackTraceElement => Boolean, color: Boolean): String =
    val seen = new java.util.IdentityHashMap[Throwable, Unit]
    val lines = scala.collection.mutable.ListBuffer.empty[fansi.Str]
    def dotted(s: String): fansi.Str = fansi.Str.join(s.split('.').toSeq.map(fansi.Color.LightRed(_)), ".")
    def header(e: Throwable): fansi.Str =
      val msg: fansi.Str | Null = e.getMessage match
        case null => e.getCause match { case null => null; case c => header(c) }
        case s => fansi.Str(s)
      dotted(e.getClass.getName) ++ (if msg == null then fansi.Str("") else fansi.Str(": ") ++ msg)
    def location(frame: StackTraceElement): fansi.Str =
      frame.getFileName match
        case null => fansi.Color.LightRed("Unknown Source")
        case file => fansi.Color.LightRed(file) ++ ":" ++ fansi.Color.LightRed(frame.getLineNumber.toString)
    def print(e: Throwable, relation: String, shared: Array[StackTraceElement], indents: Int): Unit =
      if e != null && !seen.containsKey(e) then
        seen.put(e, ())
        val trace = withoutConstruction(e)
        val frames =
          if shared.isEmpty then trace
          else
            val spare = shared.reverseIterator
            trace.reverse.dropWhile(spare.hasNext && spare.next() == _).reverse
        val prefix = frames.takeWhile(!stop(_))
        val margin = "  " * indents
        lines += fansi.Str(margin) ++ fansi.Color.Red(relation) ++ header(e)
        for frame <- prefix do
          lines += fansi.Str(s"$margin  ") ++ fansi.Color.Red("at") ++ " " ++
            dotted(frame.getClassName) ++ "." ++ fansi.Color.LightRed(frame.getMethodName) ++ "(" ++
            location(frame) ++ ")"
        val common = trace.length - frames.length
        val elided = frames.length - prefix.length
        if common > 0 then
          if elided > 0 then lines += fansi.Color.Red(s"$margin  ... $elided elided and $common more")
          else lines += fansi.Color.Red(s"$margin  ... $common more")
        else if elided > 0 then lines += fansi.Color.Red(s"$margin  ... $elided elided")
        print(e.getCause, "Caused by: ", trace, indents)
        for s <- e.getSuppressed do print(s, "Suppressed: ", frames, indents + 1)
    print(t, "", Array.empty, 0)
    val text = fansi.Str.join(lines.toSeq, fansi.Str(System.lineSeparator))
    if color then text.render else text.plainText

  /** The stack trace of `e` without the frames that construct it, which
   *  Scala Native records (`fillInStackTrace`, the constructors of its class
   *  and superclasses, the runtime's helpers that throw) and the JVM does not.
   */
  private def withoutConstruction(e: Throwable): Array[StackTraceElement] =
    val trace = e.getStackTrace
    val classes = Iterator.iterate[Class[?] | Null](e.getClass)(c => c.nn.getSuperclass)
      .takeWhile(_ != null).map(_.nn.getName).toSet
    val construction = trace.segmentLength: frame =>
      frame.getMethodName == "fillInStackTrace" || classes(frame.getClassName)
      || frame.getClassName.startsWith("scala.scalanative.runtime.")
    if construction == trace.length then trace else trace.drop(construction)
end Traces
