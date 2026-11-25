package dotty.tools
package repl

import dotc.core.Contexts.*
import dotc.core.Symbols.Symbol
import dotty.tools.io.AbstractFile
import dotty.vendored.fansi

/** Where the REPL runs the code it compiles.
 *
 *  The front end (`ReplDriver`, `ReplCompiler`) parses each input, compiles
 *  it and keeps the session's symbols and types in its `State`. A backend
 *  decides which code the compiler generates, runs the statements of each
 *  compiled line and renders the values the line defines. Values live with
 *  the backend; the front end only sees their rendered text.
 *
 *  - On the JVM (`JvmReplBackend`), lines run in class loaders of the
 *    compiler's own process and values are rendered through reflection.
 *  - With Scala Native, lines run in another process: they are compiled to
 *    NIR, and each line's wrapper object gets a generated `$show` method (see
 *    `ReplShow`) that renders its values, since there is no reflection.
 *
 *  A backend lasts one session: `ReplDriver` makes a new one at the start
 *  and on `:reset` and `:replay`, and closes the old one.
 */
trait ReplBackend:

  /** The compiler for this session's lines: its phases decide the code that
   *  is generated (JVM bytecode, NIR) and whether wrappers get `$show`.
   */
  def newCompiler(): ReplCompiler

  /** The output directory when the settings name none. */
  def defaultOutputDir(): AbstractFile

  /** Runs the statements of `line`, which was just compiled, by initializing
   *  its wrapper object, then renders `line.values` in order.
   */
  def run(line: ReplLine)(using Context): ReplResult

  /** Runs `body`, which interprets REPL input, in the context that compiled
   *  code needs (the JVM backend sets the thread's context class loader).
   */
  def withRunContext[T](body: => T)(using Context): T = body

  /** Asks the line that is running to stop (the first Ctrl-C), or clears that
   *  request before a line runs.
   */
  def setInterrupted(interrupted: Boolean)(using Context): Unit = ()

  /** Adds jars or directories to the class path that lines run with
   *  (`:jar`, `:dep`, `:resource` and the matching `//> using` directives).
   *  Jars and dependencies are on the compiler's class path already.
   */
  def addToClasspath(entries: Seq[java.nio.file.Path])(using Context): Unit

  /** The path of the first class file in `jar` (a jar or directory about to
   *  be added with `:jar`) whose class running code can already load, if any.
   */
  def existingClassIn(jar: AbstractFile)(using Context): Option[String] = None

  /** Releases what the session holds (processes, temporary files). */
  def close(): Unit = ()

end ReplBackend

/** A compiled line to run.
 *
 *  @param index   the line's object index: its wrapper is `rs$line$<index>`
 *  @param wrapper the wrapper's module class
 *  @param values  the values to render, in the order of `ReplShow.shownValues`
 *  @param earlier the object indexes of the earlier lines that compiled and
 *                 initialized, in order: a backend that cannot keep values
 *                 between lines replays them
 */
final case class ReplLine(index: Int, wrapper: Symbol, values: List[ReplValue], earlier: List[Int])

/** A value to render: `prefixLength` is the width of what precedes it on its
 *  line (`val x: Int = `), which the pretty-printer counts.
 */
final case class ReplValue(symbol: Symbol, prefixLength: Int)

/** What running a line gives. */
enum ReplResult:
  /** The line ran. One text per value, `None` for a value that is not shown
   *  (of type `Unit`).
   */
  case Values(texts: List[Option[fansi.Str]])
  /** The line's statements threw, so its wrapper did not initialize; the
   *  message is the rendered exception.
   */
  case Failed(message: fansi.Str)
