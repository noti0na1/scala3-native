package dotty.tools.repl.runtime

import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.mutable
import scala.scalanative.libc.signal
import scala.scalanative.posix.unistd
import scala.scalanative.runtime.{Intrinsics, toRawPtr}
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import LineSupport.{flush, isRunnerFrame, withoutOutput}

/** The main of `snrepl-host`, the native REPL's evaluator: a long-lived,
 *  multithreaded process that keeps the values of a session. Each REPL line
 *  is linked as an extension against this program's manifest and the lines
 *  before it, and loaded here; its runner (`EvalLine`) initializes the
 *  line's wrapper on a thread of its own, then renders its values.
 *
 *  `snc repl` (`EvaluatorBackend`) talks to it over file descriptors 3
 *  (requests) and 4 (responses). Requests are lines of tab-separated fields:
 *
 *  - `EVAL <bundle> <runner> <width> <height> <color> <offsets>`: loads the
 *    bundle if it is new, runs the line and renders its values;
 *  - `REPLAY <bundle> <runner or empty>`: the same with standard output and
 *    error on /dev/null and no values (after a restart);
 *  - `INTERRUPT`: stops the running line (Ctrl-C);
 *  - `PING`, `QUIT`.
 *
 *  A response starts with a line of tab-separated fields `<status> <load us>
 *  <run us> <n>`, followed by n texts; the status is `OK` (the texts of the
 *  values), `FAILED` (the line threw; the trace), `INTERRUPTED` or
 *  `LOADERROR` (why). `PING` gets `PONG`. A text is its length in UTF-8
 *  bytes (-1 for none) on a line, the bytes and a newline.
 *
 *  With `--capture`, the front end shows the line's output: after each line,
 *  a marker on standard output tells it that the output before the marker is
 *  complete.
 *
 *  A program linked as a live-execution host can be the evaluator of the
 *  lines it runs itself, with `snc repl --driven`: it answers the requests
 *  with `answer` on a thread of its own and stops a line with `interrupt`.
 */
object Evaluator:

  /** Written to standard output after each line in `--capture` mode. */
  val Marker: Array[Byte] = "\u0000SNREPL\u0000".getBytes(UTF_8)

  private val requests = 3
  private val responses = 4
  private val MaxFrames = 1024

  def main(args: Array[String]): Unit =
    val capture = args.contains("--capture")
    // Ctrl-C reaches the front end, which asks for an interrupt
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    val in = new LineReader(requests)
    var done = false
    while !done do
      in.readLine() match
        case null => done = true
        case line =>
          val fields = line.split("\t", -1)
          fields(0) match
            case "EVAL" =>
              start(capture, silent = false, fields(1), fields(2),
                fields(3).toInt, fields(4).toInt, fields(5) == "true",
                if fields(6).isEmpty then Array.emptyIntArray else fields(6).split(',').map(_.toInt))
            case "REPLAY" =>
              start(capture, silent = true, fields(1), fields(2), 80, 50, false, Array.emptyIntArray)
            case "INTERRUPT" => interrupt()
            case "PING" => respond("PONG\n".getBytes(UTF_8))
            case "QUIT" => done = true
            case other => respond(response("FAILED", 0, 0, List(s"unknown request $other")).getBytes(UTF_8))
    unistd._exit(0)

  // The running line, if any
  @volatile private var running: Thread | Null = null
  @volatile private var interruptRequested = false

  /** Stops the running line: its code throws `ReplInterruption` at its next
   *  method entry or loop back-edge, and a blocking call in it throws
   *  `InterruptedException`.
   */
  def interrupt(): Unit =
    val thread = running
    if thread != null then
      interruptRequested = true
      EvalNative.scalanative_ext_set_interrupt(1)
      thread.interrupt()

  /** The response to `request`, an `EVAL` or `REPLAY` request, or `PING`, of
   *  a program that is its own evaluator: the line runs on the calling thread,
   *  with its output where that thread's goes, one line at a time.
   */
  def answer(request: String): String =
    val fields = request.split("\t", -1)
    fields(0) match
      case "EVAL" | "REPLAY" =>
        val silent = fields(0) == "REPLAY"
        interruptRequested = false
        EvalNative.scalanative_ext_set_interrupt(0)
        running = Thread.currentThread()
        try
          if silent then evaluate(silent = true, fields(1), fields(2), 80, 50, false, Array.emptyIntArray)
          else
            evaluate(silent = false, fields(1), fields(2), fields(3).toInt, fields(4).toInt, fields(5) == "true",
              if fields(6).isEmpty then Array.emptyIntArray else fields(6).split(',').map(_.toInt))
        catch case t: Throwable => response("FAILED", 0, 0, List(s"The evaluator failed: $t"))
        finally
          running = null
          interruptRequested = false
          EvalNative.scalanative_ext_set_interrupt(0)
          // An interrupt that arrived as the line ended is not this thread's
          Thread.interrupted()
      case "PING" => "PONG\n"
      case other => response("FAILED", 0, 0, List(s"unknown request $other"))

  /** A response: its status, the times of loading and running in
   *  microseconds, and its texts.
   */
  private def response(status: String, loadMicros: Long, runMicros: Long, texts: Seq[String | Null]): String =
    val sb = new StringBuilder
    sb.append(status).append('\t').append(loadMicros).append('\t').append(runMicros)
      .append('\t').append(texts.length).append('\n')
    texts.foreach(t => sb.append(text(t)))
    sb.toString

  /** Runs a line on a thread of its own, which responds when it is done. */
  private def start(
      capture: Boolean, silent: Boolean, bundle: String, runner: String,
      width: Int, height: Int, color: Boolean, offsets: Array[Int]): Unit =
    // An `INTERRUPT` read while the previous line's thread ended may have set
    // the flag after that thread cleared it. The front end sends a line only
    // after it has read the previous line's response, so a set flag here is
    // always such a leftover
    interruptRequested = false
    EvalNative.scalanative_ext_set_interrupt(0)
    val thread = new Thread(null, () => {
      val answer =
        try evaluate(silent, bundle, runner, width, height, color, offsets)
        catch case t: Throwable => response("FAILED", 0, 0, List(s"The evaluator failed: $t"))
      running = null
      interruptRequested = false
      EvalNative.scalanative_ext_set_interrupt(0)
      flush()
      if capture then writeAll(1, Marker)
      respond(answer.getBytes(UTF_8))
    }, "repl-line", 64L << 20)
    running = thread
    thread.start()

  private def evaluate(
      silent: Boolean, bundle: String, runner: String,
      width: Int, height: Int, color: Boolean, offsets: Array[Int]): String =
    val t0 = System.nanoTime()
    val loaded = load(bundle)
    val t1 = System.nanoTime()
    val loadMicros = (t1 - t0) / 1000
    def micros = (System.nanoTime() - t1) / 1000
    loaded match
      case Left(error) => response("LOADERROR", loadMicros, 0, List(error))
      case Right(handle) =>
        if runner.isEmpty then response("OK", loadMicros, 0, Nil)
        else
          val line = module(handle, runner).asInstanceOf[EvalLine]
          def isInterruption(t: Throwable) =
            t.isInstanceOf[ReplInterruption] ||
            (interruptRequested && t.isInstanceOf[InterruptedException])
          try
            if silent then
              withoutOutput(line.init())
              response("OK", loadMicros, micros, Nil)
            else
              line.init()
              val texts = line.show(width, height, color, offsets)
              response("OK", loadMicros, micros, texts.toSeq)
          catch
            case t: Throwable if isInterruption(t) => response("INTERRUPTED", loadMicros, micros, Nil)
            case t: Throwable =>
              // Keep at most 1024 frames, as the JVM does (a
              // StackOverflowError has more)
              val trace = t.getStackTrace
              if trace.length > MaxFrames then t.setStackTrace(trace.take(MaxFrames))
              response("FAILED", loadMicros, micros, List(Traces.render(t, isRunnerFrame, color)))

  // The loaded bundles, by path
  private val loaded = mutable.HashMap.empty[String, Ptr[Byte]]

  private def load(path: String): Either[String, Ptr[Byte]] = loaded.synchronized {
    loaded.get(path) match
      case Some(handle) => Right(handle)
      case None =>
        val handle = Zone.acquire(implicit z => EvalNative.scalanative_ext_load_with(toCString(path), 1))
        if handle == null then Left(fromCString(EvalNative.scalanative_ext_error()))
        else
          val init = EvalNative.scalanative_ext_init_function(handle)
          if init != null then
            val run = CFuncPtr.fromPtr[CFuncPtr0[Unit]](init)
            run()
          loaded(path) = handle
          Right(handle)
  }

  private def module(handle: Ptr[Byte], name: String): AnyRef = Zone.acquire { implicit z =>
    val cname = toCString(name)
    val instance = EvalNative.scalanative_ext_module_instance(handle, cname)
    if instance != null then Intrinsics.castRawPtrToObject(toRawPtr(instance))
    else
      val accessor = EvalNative.scalanative_ext_module_accessor(handle, cname)
      if accessor == null then throw new NoSuchElementException(s"The line's extension defines no $name")
      val load = CFuncPtr.fromPtr[CFuncPtr0[Ptr[Byte]]](accessor)
      val instance = load()
      Intrinsics.castRawPtrToObject(toRawPtr(instance))
  }

  /** A text of the protocol: its length in bytes, or -1 for none. */
  private def text(s: String | Null): String =
    if s == null then "-1\n"
    else s"${s.getBytes(UTF_8).length}\n$s\n"

  private def respond(bytes: Array[Byte]): Unit = synchronized(writeAll(responses, bytes))

  private def writeAll(fd: Int, bytes: Array[Byte]): Unit =
    var off = 0
    while off < bytes.length do
      val n = unistd.write(fd, bytes.at(off), (bytes.length - off).toCSize)
      if n <= 0 then off = bytes.length else off += n

  /** Lines from a file descriptor, read with blocking reads. */
  private final class LineReader(fd: Int):
    private val buffer = new Array[Byte](65536)
    private var start = 0
    private var end = 0
    private val pending = new java.io.ByteArrayOutputStream

    def readLine(): String | Null =
      var result: String | Null = null
      var eof = false
      while result == null && !eof do
        var i = start
        while i < end && buffer(i) != '\n' do i += 1
        if i < end then
          pending.write(buffer, start, i - start)
          start = i + 1
          result = new String(pending.toByteArray, UTF_8)
          pending.reset()
        else
          pending.write(buffer, start, end - start)
          start = 0
          end = 0
          val n = unistd.read(fd, buffer.at(0), buffer.length.toCSize)
          if n <= 0 then eof = true else end = n
      result
end Evaluator

/** The functions of nativelib's extension loader (`ext_loader.c`) that the
 *  evaluator needs beyond `scala.scalanative.runtime.Extension`: loading with
 *  global symbols, so that later lines bind to a line's symbols, and the
 *  interrupt flag.
 */
@extern
@define("__SCALANATIVE_LIVE_EXECUTION")
private[runtime] object EvalNative:
  def scalanative_ext_load_with(path: CString, global: CInt): Ptr[Byte] = extern
  def scalanative_ext_error(): CString = extern
  def scalanative_ext_module_accessor(ext: Ptr[Byte], name: CString): Ptr[Byte] = extern
  def scalanative_ext_module_instance(ext: Ptr[Byte], name: CString): Ptr[Byte] = extern
  def scalanative_ext_init_function(ext: Ptr[Byte]): Ptr[Byte] = extern
  def scalanative_ext_set_interrupt(value: CInt): Unit = extern
