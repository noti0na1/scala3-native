package dotty.tools.repl.runtime

/** What the evaluator calls in a REPL line linked as an extension: the
 *  compiler generates an object `rs$runner$N` implementing it for line N
 *  (`NativeReplCompiler.GenRunner`, evaluator mode).
 */
trait EvalLine:
  /** Initializes the line's wrapper `rs$line$N`, which runs the line. */
  def init(): Any

  /** The texts of the line's values: its wrapper's `$show`. */
  def show(width: Int, height: Int, color: Boolean, offsets: Array[Int]): Array[String]
