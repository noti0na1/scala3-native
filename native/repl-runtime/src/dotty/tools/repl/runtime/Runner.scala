package dotty.tools.repl.runtime

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

import LineSupport.{flush, isRunnerFrame, withoutOutput}

/** The main method of a REPL session relinked as a program, for the native
 *  REPL's relink backend (`RelinkBackend`): it runs the earlier lines again
 *  with their output discarded, then the new line, and writes what `$show`
 *  gives to a file for the REPL.
 *
 *  Arguments: the results file, the width, height and color for `$show`, and
 *  its offsets separated by commas. The results file holds `ok`, the number
 *  of texts and each text; or `error` and the exception's trace; or
 *  `replay-error`, the index of the line that failed, and the trace. A text
 *  is its length in bytes (-1 for none) on one line, then its UTF-8 bytes
 *  and a newline.
 */
object Runner:

  type Show = (Int, Int, Boolean, Array[Int]) => Array[String]

  def run(args: Array[String], earlierIndexes: Array[Int], earlier: Array[() => Any], show: Show): Unit =
    val Array(results, width, height, color, offsets) = args.padTo(5, ""): @unchecked
    val colored = color == "true"
    val offsetValues = if offsets.isEmpty then Array.emptyIntArray else offsets.split(',').map(_.toInt)
    val out = new StringBuilder

    def text(s: String | Null): Unit =
      if s == null then out.append("-1\n")
      else
        out.append(s.getBytes(UTF_8).length).append('\n').append(s).append('\n')

    // Replay, with standard output and error discarded
    var failed: Option[(Int, Throwable)] = None
    withoutOutput:
      var i = 0
      while failed.isEmpty && i < earlier.length do
        try earlier(i)()
        catch case t: Throwable => failed = Some((earlierIndexes(i), t))
        i += 1

    failed match
      case Some((index, t)) =>
        out.append("replay-error\n").append(index).append('\n')
        text(Traces.render(t, isRunnerFrame, colored))
      case None =>
        try
          val texts = show(width.toInt, height.toInt, colored, offsetValues)
          flush()
          out.append("ok\n").append(texts.length).append('\n')
          texts.foreach(text)
        catch case t: Throwable =>
          flush()
          out.setLength(0)
          out.append("error\n")
          text(Traces.render(t, isRunnerFrame, colored))
    Files.write(Paths.get(results), out.toString.getBytes(UTF_8))
end Runner
