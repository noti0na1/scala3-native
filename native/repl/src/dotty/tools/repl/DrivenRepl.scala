package dotty.tools
package repl

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8

import scala.scalanative.libc.signal
import scala.scalanative.posix.{poll, unistd}
import scala.scalanative.posix.pollOps.*
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.util.control.NonFatal

import dotc.printing.SyntaxHighlighting
import EvaluatorBackend.{EvaluatorChannel, Response}

/** `snc repl --driven --evaluator-host <binary>`: the REPL as the compiler of
 *  a program that runs the lines itself (see README.md, "REPL").
 *  The program, the driver, is linked as a live-execution host and started
 *  this REPL; the REPL compiles each line it is given, links it against the
 *  driver's manifest (`<binary>.snm`) and the earlier lines, and asks the
 *  driver to run it, as it asks its evaluator (`EvaluatorBackend`). The
 *  driver answers with `dotty.tools.repl.runtime.Evaluator.answer`.
 *
 *  The protocol is on this process's standard input and output, in the
 *  format of the evaluator's (`dotty.tools.repl.runtime.Evaluator`): lines of
 *  tab-separated fields, the last field of a message giving how many texts
 *  follow it; a text is its length in UTF-8 bytes (-1 for none) on a line,
 *  the bytes and a newline. The evaluator's requests and responses keep
 *  their own format.
 *
 *  From the driver:
 *  - `RUN 1` and the line's source: compiles, links and runs the line;
 *  - `HIGHLIGHT 1` and Scala source: the source with the compiler's colours;
 *  - the responses to the evaluator's requests;
 *  - `QUIT 0`, or the end of the input, ends the session.
 *
 *  From the REPL:
 *  - `READY 0`, once it reads lines;
 *  - the evaluator's requests (`EVAL`), while a line runs;
 *  - `DONE <status> 1` and what the REPL printed for the line (its compiler
 *    messages, its values, its error), after each `RUN`. The status is `OK`,
 *    `ERROR` (the line did not compile, link or load), `FAILED` (it threw),
 *    `INTERRUPTED`, or `REFUSED` (it runs a command that `--commands` does
 *    not list);
 *  - `HIGHLIGHTED 1` and the source of a `HIGHLIGHT` with ANSI colours, as
 *    `-color:always` paints it whatever the session's `-color`; as it was
 *    when it cannot be highlighted.
 *
 *  Whatever else this process writes goes to its standard error. The driver
 *  interrupts a line itself (`Evaluator.interrupt`); this process ignores
 *  SIGINT. The driver keeps the classes of every line it loaded, and loads a
 *  class name once: a second session of the same driver numbers its wrappers
 *  past the first one's (`--first-line`).
 */
object DrivenRepl:

  /** What the REPL prints for the line that runs. */
  private val printed = new ByteArrayOutputStream

  /** The protocol's input, standard input. */
  private val in = new ChannelReader(0)

  /** The protocol's output: standard output as this process started; standard
   *  output is standard error from then on.
   */
  private var out: Int = -1

  /** The only commands the REPL runs, if it runs only some (`--commands`). */
  private var commands: Option[Set[String]] = None

  /** Runs the REPL for its driver; the exit code. */
  def run(settings: Array[String], config: NativeReplConfig): Int =
    commands = config.commands
    out = unistd.dup(1)
    unistd.dup2(2, 1)
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    val driver = new NativeReplDriver(settings, new PrintStream(printed, true, UTF_8.name), config)
    try
      driver.tryRunning
      0
    finally driver.close()

  /** Reads the driver's lines and runs them until `QUIT` or the end of the
   *  input; `NativeReplDriver.runUntilQuit` when driven.
   */
  def serve(driver: NativeReplDriver)(using initial: State): State =
    printed.reset()
    send("READY", Nil)
    var state = initial
    var done = false
    while !done do
      while !in.buffered && !readable(20) do driver.idle()
      in.readLine() match
        case null => done = true
        case header: String =>
          val fields = header.split("\t", -1)
          val texts = List.fill(fields.last.toIntOption.getOrElse(0))(in.readText())
          fields(0) match
            case "RUN" =>
              val (status, next) = runLine(driver, texts.headOption.flatten.getOrElse(""))(using state)
              state = next
              val output = printed.toString(UTF_8.name)
              printed.reset()
              send(s"DONE\t$status", List(output))
            case "HIGHLIGHT" =>
              send("HIGHLIGHTED", List(highlighted(texts.headOption.flatten.getOrElse(""))(using state)))
            case "QUIT" => done = true
            case other => send("DONE\tERROR", List(s"unknown message $other"))
    state

  /** `code` with the compiler's colours, in the session's context but with
   *  `-color:always`; as it is when it cannot be highlighted.
   */
  private def highlighted(code: String)(using state: State): String =
    try
      val ctx = state.context
      SyntaxHighlighting.highlight(code)(using ctx.fresh.setSetting(ctx.settings.color, "always"))
    catch case NonFatal(_) => code

  /** Runs `code` as a line; its status and the next state. */
  private def runLine(driver: NativeReplDriver, code: String)(using before: State): (String, State) =
    commands match
      case Some(allowed) if runsOther(code, allowed) =>
        val listed = allowed.toList.sorted
        val message =
          if listed.isEmpty then "This REPL runs no commands."
          else s"This REPL runs only the commands ${listed.mkString(", ")}."
        printed.write((message + "\n").getBytes(UTF_8))
        ("REFUSED", before)
      case _ => run(driver, code)

  /** Whether `code` runs a command that `allowed` does not list: one whose
   *  name it does not list (an unknown or an ambiguous command has none), a
   *  command whose code after it runs one, or commands mixed with directives.
   *  The REPL parses it as `ReplDriver.run` does.
   */
  private def runsOther(code: String, allowed: Set[String])(using State): Boolean =
    def listed(command: Command) = command.replayLine.map(_.trim.takeWhile(!_.isWhitespace)).exists(allowed)
    ParseResult(code) match
      case MixedCommandsAndDirectives => true
      case CommandThenCode(command, rest) => !listed(command) || runsOther(rest, allowed)
      case command: Command => !listed(command)
      case _ => false

  /** Compiles and runs `code`; its status and the next state. */
  private def run(driver: NativeReplDriver, code: String)(using before: State): (String, State) =
    val backend = driver.evaluatorBackend
    backend.foreach(_.clearOutcome())
    val syntaxError = ParseResult.complete(code) match
      case _: SyntaxErrors => true
      case _ => false
    val after = driver.run(code)
    // A line that did not compile, or whose wrapper failed to initialize,
    // leaves its wrapper's index invalid
    val invalid = after.objectIndex > before.objectIndex && after.invalidObjectIndexes.contains(after.objectIndex)
    val status = backend.flatMap(_.lastOutcome) match
      case Some("OK") => if invalid then "ERROR" else "OK"
      case Some("FAILED") => "FAILED"
      case Some("INTERRUPTED" | "KILLED") => "INTERRUPTED"
      case Some(_) => "ERROR"
      case None => if syntaxError || invalid then "ERROR" else "OK"
    (status, after)

  /** The evaluator of a driven REPL: the driver, over the protocol. */
  object evaluator extends EvaluatorChannel:
    def isAlive: Boolean = true
    def call(request: String, interruptible: Boolean): Response =
      write(request + "\n")
      in.readLine() match
        case null => Response("CRASHED", 0, 0, List("the driver closed the REPL's input"))
        case header: String =>
          val fields = header.split("\t", -1)
          if fields(0) == "PONG" then Response("PONG", 0, 0, Nil)
          else
            val texts = List.fill(fields(3).toInt)(in.readText().getOrElse(null))
            Response(fields(0), fields(1).toLong, fields(2).toLong, texts)
    def stop(): Unit = ()

  /** Whether standard input can be read within `timeoutMs`. */
  private def readable(timeoutMs: Int): Boolean =
    val fd = stackalloc[poll.struct_pollfd]()
    fd.fd = 0
    fd.events = poll.POLLIN.toShort
    fd.revents = 0.toShort
    poll.poll(fd, 1.toUInt, timeoutMs) > 0

  private def send(header: String, texts: List[String]): Unit =
    val sb = new StringBuilder
    sb.append(header).append('\t').append(texts.length).append('\n')
    texts.foreach(t => sb.append(t.getBytes(UTF_8).length).append('\n').append(t).append('\n'))
    write(sb.toString)

  private def write(s: String): Unit =
    val bytes = s.getBytes(UTF_8)
    var off = 0
    while off < bytes.length do
      val n = unistd.write(out, bytes.at(off), (bytes.length - off).toCSize)
      if n <= 0 then throw new java.io.IOException("the driver does not read the REPL's output")
      off += n

  /** Lines and texts of the protocol from a file descriptor, read with
   *  blocking reads.
   */
  private final class ChannelReader(fd: Int):
    private val buffer = new Array[Byte](65536)
    private var start = 0
    private var end = 0

    /** Whether bytes were read that nothing has taken yet. */
    def buffered: Boolean = start < end

    /** Reads more; false at the end of the input. */
    private def fill(): Boolean =
      if start == end then
        start = 0
        end = 0
      val n = unistd.read(fd, buffer.at(end), (buffer.length - end).toCSize)
      if n <= 0 then false
      else
        end += n
        true

    def readLine(): String | Null =
      val line = new ByteArrayOutputStream
      var result: String | Null = null
      var eof = false
      while result == null && !eof do
        var i = start
        while i < end && buffer(i) != '\n' do i += 1
        line.write(buffer, start, i - start)
        if i < end then
          start = i + 1
          result = line.toString(UTF_8.name)
        else
          start = end
          if !fill() then eof = true
      result

    /** A text: its length on a line, then its bytes and a newline. */
    def readText(): Option[String] =
      readLine() match
        case null => None
        case length: String =>
          val n = length.trim.toInt
          if n < 0 then None
          else
            val bytes = new Array[Byte](n)
            var got = 0
            var eof = false
            while got < n && !eof do
              if start == end && !fill() then eof = true
              else
                val take = math.min(n - got, end - start)
                System.arraycopy(buffer, start, bytes, got, take)
                start += take
                got += take
            // The newline after the bytes
            if !eof then
              if start == end then fill()
              if start < end then start += 1
            Some(new String(bytes, UTF_8))
end DrivenRepl
