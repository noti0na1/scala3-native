package dotty.tools
package repl

import java.io.PrintStream

import scala.annotation.tailrec
import scala.util.control.NonFatal

import dotc.config.Properties.simpleVersionString
import dotc.core.Contexts.*
import dotc.parsing.Scanners.Scanner
import dotc.parsing.Tokens.*
import dotc.printing.SyntaxHighlighting
import dotc.reporting.Reporter
import dotc.util.SourceFile

/** The REPL of `snc`: `ReplDriver` with the evaluator backend (or the relink
 *  backend) and the `termios` line editor in place of JLine.
 */
class NativeReplDriver(settings: Array[String], out: PrintStream, config: NativeReplConfig)
extends ReplDriver(settings, out):

  override protected def newBackend(): ReplBackend =
    // The jars that `:dep` added are on the old session's class path only
    DependencyResolver.newSession()
    config.backend match
      case NativeReplConfig.Backend.Relink => new RelinkBackend(config)
      // The transcript runner captures the lines' output; else the evaluator
      // writes to this process's standard output and error itself
      case NativeReplConfig.Backend.Evaluator => new EvaluatorBackend(config, capture = config.captureOutput)

  /** The number of the next session's first wrapper: `--first-line`, and
   *  when driven, after every wrapper this REPL has made. A driven REPL's
   *  evaluator keeps the classes of its earlier lines loaded, and it loads a
   *  class name once, so `:reset` does not number from the start again.
   */
  override protected def firstObjectIndex: Int = math.max(config.firstLine, nextObjectIndex)

  private var nextObjectIndex = config.firstLine

  override protected def runBody(body: => State): State =
    val state = super.runBody(body)
    if config.driven then nextObjectIndex = math.max(nextObjectIndex, state.objectIndex + 1)
    state

  /** Ends the session: its backend stops the evaluator and deletes the
   *  session's files. `:reset` and `:replay` close the old backend
   *  themselves.
   */
  def close(): Unit = sessionBackend.close()

  /** The session's backend, if it is the evaluator backend. */
  def evaluatorBackend: Option[EvaluatorBackend] = sessionBackend match
    case backend: EvaluatorBackend => Some(backend)
    case _ => None

  /** Work to do while waiting for input: the evaluator backend's startup
   *  (the evaluator host's background link, the linker's warm-up).
   */
  def idle(): Unit = evaluatorBackend.foreach(_.idle())

  /** Interprets the inputs that `editor` reads until `:quit` or the end of
   *  the input; when driven, those of the driver (`DrivenRepl`).
   */
  override def runUntilQuit(using initialState: State = initialState)(): State =
    if config.driven then DrivenRepl.serve(this)
    else runEditor()

  private def runEditor()(using initialState: State): State =
    // While the editor waits for keys, the evaluator backend finishes its
    // startup work (the evaluator host's background link)
    val editor = new LineEditor(out, LineEditor.defaultHistoryFile, idle = () => idle())
    if editor.interactive then
      out.println(
        s"""Welcome to Scala $simpleVersionString (snc, Scala Native).
           |Type in expressions for evaluation. Or try :help.""".stripMargin)

    def readInput()(using state: State): EditorInput =
      given Context = state.context
      val color = ctx.settings.color.value != "never"
      def magenta(s: String) = if color then Console.MAGENTA + s + Console.RESET else s
      if editor.interactive then out.println()
      editor.readLine(
        prompt = magenta("scala> "),
        continuation = "       ",
        promptWidth = 7,
        accept = (text, pending) => ParseResult.shouldAcceptLine(text, pending),
        highlight = text => if color then SyntaxHighlighting.highlight(text) else text,
        complete = (text, cursor) => completionsAt(text, cursor))

    @tailrec def loop(using state: State)(): State =
      readInput() match
        case EditorInput.EndOfInput => state
        case EditorInput.Interrupted => loop()
        case EditorInput.Line(text) =>
          val res = ParseResult(text)
          if res == Quit then state
          else loop(using interpretSubmission(res))()

    try runBody { loop() }
    finally editor.close()
  end runEditor

  /** The start of the word at `cursor` and the completions that may replace
   *  it, as JLine's parser in `JLineTerminal` finds them.
   */
  private def completionsAt(text: String, cursor: Int)(using state: State): (Int, List[String]) =
    def stripBackTicks(label: String) =
      if label.startsWith("`") && label.endsWith("`") then label.drop(1).dropRight(1) else label
    val start =
      if ReplCommands.names.exists(_.startsWith(text)) then 0
      else tokenStart(text, cursor)(using state.context)
    val labels = completions(cursor, text, state).map(_.label).distinct
    (start, labels.map(l => if text.startsWith(":") then l else stripBackTicks(l)))

  /** Where the identifier or keyword at `cursor` starts, or `cursor`. */
  private def tokenStart(input: String, cursor: Int)(using Context): Int =
    val source = SourceFile.virtual("<completions>", input)
    val scanner = new Scanner(source)(using ctx.fresh.setReporter(Reporter.NoReporter))
    var lastBacktickErrorStart: Option[Int] = None
    var result = cursor
    var done = false
    while !done && scanner.token != EOF do
      val start = scanner.offset
      val token = scanner.token
      scanner.nextToken()
      val end = scanner.lastOffset
      if cursor >= start && cursor <= end then
        if isIdentifier(token) || isKeyword(token) then
          result = lastBacktickErrorStart.getOrElse(start)
        done = true
      else if token == ERROR && input(start) == '`' then lastBacktickErrorStart = Some(start)
      else lastBacktickErrorStart = None
    result
end NativeReplDriver
