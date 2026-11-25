package dotty.tools
package repl

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

import scala.collection.mutable
import scala.scalanative.libc.stdlib
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.{poll, termios, unistd}
import scala.scalanative.posix.pollOps.*
import scala.scalanative.posix.sys.ioctl
import scala.scalanative.posix.termiosOps.*
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.util.control.NonFatal

/** What `LineEditor.readLine` gives. */
enum EditorInput:
  /** An accepted input, which may span several lines. */
  case Line(text: String)
  /** Ctrl-C: the input so far is dropped. */
  case Interrupted
  /** Ctrl-D on an empty input, or the end of standard input. */
  case EndOfInput

/** The native REPL's line editor, on a terminal in raw mode (`termios`), in
 *  place of JLine.
 *
 *  - Editing: printable characters, Backspace, Delete, Left, Right, Home,
 *    End, Ctrl-A/E/B/F/K/U/W/Y/L/T, Alt-B/F/D/Backspace, Ctrl- or Alt-Left
 *    and -Right by words.
 *  - Multi-line input: Enter accepts the input when the cursor is on its last
 *    line and `accept` holds, and else starts a new line; Up and Down
 *    move between lines, and through the history from the first or last
 *    line. Pasted text (bracketed paste) is inserted as it is.
 *  - History, kept in `historyFile`; Ctrl-R searches it backwards.
 *  - Tab completes with `complete`, and lists the candidates when pressed
 *    twice; at the start of a line it indents.
 *  - Ctrl-C drops the input, Ctrl-D on an empty input ends the session.
 *
 *  When standard input is not a terminal, it reads lines as they come and
 *  joins them until `accept` holds, without editing.
 *
 *  While it waits for a key on a terminal, it calls `idle` about every
 *  200 ms, which the REPL uses for work that should not wait for an input.
 *
 *  Inspired by JLine (BSD license) and linenoise; no code is taken from them.
 */
class LineEditor(out: PrintStream, historyFile: Option[Path], idle: () => Unit = () => ()):
  import LineEditor.*

  /** Whether standard input and output are a terminal that can be edited on. */
  val interactive: Boolean =
    unistd.isatty(0) == 1 && unistd.isatty(1) == 1 && !sys.env.get("TERM").contains("dumb")

  private val history = mutable.ArrayBuffer.empty[String]
  loadHistory()

  private lazy val plainInput = new BufferedReader(new InputStreamReader(System.in, UTF_8))

  /** The completions at a cursor: where the word they replace starts, and
   *  the candidates.
   */
  type Completer = (String, Int) => (Int, List[String])

  /** Reads one input. `prompt` and `continuation` start its first and its
   *  other lines, and have `promptWidth` columns.
   */
  def readLine(
    prompt: String,
    continuation: String,
    promptWidth: Int,
    accept: (String, Boolean) => Boolean,
    highlight: String => String,
    complete: Completer
  ): EditorInput =
    if !interactive then readPlain(prompt, continuation, accept)
    else withRawMode(new Session(prompt, continuation, promptWidth, accept, highlight, complete).run())

  /** Does nothing: each `readLine` restores the terminal before it returns. */
  def close(): Unit = ()

  private def readPlain(prompt: String, continuation: String, accept: (String, Boolean) => Boolean): EditorInput =
    out.print(prompt)
    out.flush()
    val text = new StringBuilder
    var result: EditorInput | Null = null
    while result == null do
      val line = plainInput.readLine()
      if line == null then
        result = if text.isEmpty then EditorInput.EndOfInput else EditorInput.Line(text.toString)
      else
        if text.nonEmpty then text.append('\n')
        text.append(line)
        if accept(text.toString, false) then result = EditorInput.Line(text.toString)
        else
          out.print(continuation)
          out.flush()
    result

  // ---- the terminal ----

  private def withRawMode[T](body: => T): T =
    val saved = stackalloc[termios.termios]()
    if termios.tcgetattr(0, saved) != 0 then body
    else
      val raw = stackalloc[termios.termios]()
      !raw = !saved
      raw.c_iflag = raw.c_iflag & ~(termios.BRKINT | termios.ICRNL | termios.INPCK | termios.ISTRIP | termios.IXON).toUInt
      raw.c_lflag = raw.c_lflag & ~(termios.ECHO | termios.ICANON | termios.IEXTEN | termios.ISIG).toUInt
      raw.c_cc(termios.VMIN) = 1.toUByte
      raw.c_cc(termios.VTIME) = 0.toUByte
      // Not TCSAFLUSH, which drops what was typed ahead while a line ran
      termios.tcsetattr(0, termios.TCSANOW, raw)
      write(BracketedPasteOn)
      try body
      finally
        write(BracketedPasteOff)
        termios.tcsetattr(0, termios.TCSADRAIN, saved)

  private def write(s: String): Unit =
    out.print(s)
    out.flush()

  /** The next byte of standard input, or -1 at its end. */
  private def readByte(): Int =
    awaitInput()
    val b = stackalloc[Byte]()
    var n = unistd.read(0, b, 1.toCSize)
    while n < 0 && scala.scalanative.libc.errno.errno == scala.scalanative.posix.errno.EINTR do
      n = unistd.read(0, b, 1.toCSize)
    if n <= 0 then -1 else (!b) & 0xff

  /** Waits until standard input can be read (or reports an error), calling
   *  `idle` every `IdleMillis` meanwhile.
   */
  private def awaitInput(): Unit =
    val fd = stackalloc[poll.struct_pollfd]()
    var ready = false
    while !ready do
      fd.fd = 0
      fd.events = poll.POLLIN.toShort
      fd.revents = 0.toShort
      if poll.poll(fd, 1.toUInt, IdleMillis) == 0 then
        try idle()
        catch case NonFatal(_) => ()
      else ready = true // input, its end, an error or a signal: read tells

  /** Whether more input has already arrived. */
  private def pendingInput: Boolean =
    val count = stackalloc[CInt]()
    !count = 0
    ioctl.ioctl(0, ioctl.FIONREAD, count.asInstanceOf[Ptr[Byte]]) == 0 && !count > 0

  /** The terminal's width in columns. */
  private def columns: Int =
    val ws = stackalloc[CStruct4[CUnsignedShort, CUnsignedShort, CUnsignedShort, CUnsignedShort]]()
    val request: CLongInt =
      (if LinktimeInfo.isMac || LinktimeInfo.isFreeBSD then 0x40087468L else 0x5413L).toSize // TIOCGWINSZ
    val supported = LinktimeInfo.isMac || LinktimeInfo.isFreeBSD || LinktimeInfo.isLinux
    val fromTerminal =
      if !supported then 0
      else if ioctl.ioctl(1, request, ws.asInstanceOf[Ptr[Byte]]) == 0 then ws._2.toInt
      else 0
    if fromTerminal > 0 then fromTerminal
    else sys.env.get("COLUMNS").flatMap(_.toIntOption).getOrElse(80)

  // ---- history ----

  private def loadHistory(): Unit =
    for file <- historyFile if Files.isRegularFile(file) do
      try
        val entries = Files.readAllLines(file, UTF_8)
        var i = 0
        while i < entries.size do
          history += unescape(entries.get(i))
          i += 1
      catch case NonFatal(_) => ()

  private def addHistory(entry: String): Unit =
    if entry.trim.nonEmpty && !history.lastOption.contains(entry) then
      history += entry
      if history.length > MaxHistory then history.remove(0, history.length - MaxHistory)
      for file <- historyFile do
        try
          if history.length == MaxHistory then
            Files.write(file, history.map(escape).mkString("", "\n", "\n").getBytes(UTF_8))
          else
            Files.write(file, (escape(entry) + "\n").getBytes(UTF_8),
              StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        catch case NonFatal(_) => ()

  // ---- one input ----

  private final class Session(
    prompt: String,
    continuation: String,
    promptWidth: Int,
    accept: (String, Boolean) => Boolean,
    highlight: String => String,
    complete: Completer
  ):
    private val buf = new StringBuilder
    private var cursor = 0
    /** The row of the cursor, counted from the prompt's, after the last drawing. */
    private var cursorRow = 0
    private var historyIndex = history.length
    private var draft = ""
    private var killed = ""
    private var lastKeyWasTab = false

    def run(): EditorInput =
      write(prompt)
      cursorRow = 0
      var result: EditorInput | Null = null
      while result == null do
        val key = readKey()
        val wasTab = lastKeyWasTab
        lastKeyWasTab = false
        key match
          case Key.Eof => result = EditorInput.EndOfInput
          case Key.Char(c) => insert(c.toString)
          case Key.Paste(text) => insert(text.replace("\r\n", "\n").replace('\r', '\n'))
          case Key.Enter =>
            val text = buf.toString
            val onLastLine = text.indexOf('\n', cursor) < 0
            if onLastLine && accept(text, pendingInput) then
              cursor = buf.length
              redraw()
              write("\r\n")
              addHistory(text)
              result = EditorInput.Line(text)
            else insert("\n")
          case Key.Ctrl('C') =>
            cursor = buf.length
            redraw()
            write("\r\n")
            result = EditorInput.Interrupted
          case Key.Ctrl('D') =>
            if buf.isEmpty then
              write("\r\n")
              result = EditorInput.EndOfInput
            else if cursor < buf.length then delete(cursor, cursor + 1)
          case Key.Backspace => if cursor > 0 then delete(cursor - 1, cursor)
          case Key.Delete => if cursor < buf.length then delete(cursor, cursor + 1)
          case Key.Left | Key.Ctrl('B') => if cursor > 0 then move(cursor - 1)
          case Key.Right | Key.Ctrl('F') => if cursor < buf.length then move(cursor + 1)
          case Key.Home | Key.Ctrl('A') => move(lineStart(cursor))
          case Key.End | Key.Ctrl('E') => move(lineEnd(cursor))
          case Key.WordLeft => move(wordStart(cursor))
          case Key.WordRight => move(wordEnd(cursor))
          case Key.Up | Key.Ctrl('P') => up()
          case Key.Down | Key.Ctrl('N') => down()
          case Key.Ctrl('K') =>
            val end = lineEnd(cursor)
            if end == cursor && cursor < buf.length then kill(cursor, cursor + 1) else kill(cursor, end)
          case Key.Ctrl('U') => kill(lineStart(cursor), cursor)
          case Key.Ctrl('W') | Key.AltBackspace => kill(wordStart(cursor), cursor)
          case Key.AltD => kill(cursor, wordEnd(cursor))
          case Key.Ctrl('Y') => insert(killed)
          case Key.Ctrl('T') =>
            if cursor > 0 && buf.length >= 2 then
              val at = if cursor == buf.length then cursor - 1 else cursor
              val c = buf.charAt(at - 1)
              buf.setCharAt(at - 1, buf.charAt(at))
              buf.setCharAt(at, c)
              cursor = at + 1
              redraw()
          case Key.Ctrl('L') =>
            write("\u001b[H\u001b[2J")
            write(prompt)
            cursorRow = 0
            redraw()
          case Key.Ctrl('R') => search()
          case Key.Tab =>
            if buf.substring(lineStart(cursor), cursor).forall(_.isWhitespace) then insert("  ")
            else
              completeWord(list = wasTab)
              lastKeyWasTab = true
          case _ =>
      result

    // ---- keys ----

    private def readKey(): Key =
      readByte() match
        case -1 => Key.Eof
        case 27 => readEscape()
        case 13 | 10 => Key.Enter
        case 127 | 8 => Key.Backspace
        case 9 => Key.Tab
        case b if b < 32 => Key.Ctrl(('A' + b - 1).toChar)
        case b => Key.Char(readUtf8(b))

    /** The rest of a UTF-8 sequence that starts with `first`. */
    private def readUtf8(first: Int): Char =
      val extra = if first >= 0xf0 then 3 else if first >= 0xe0 then 2 else if first >= 0xc0 then 1 else 0
      if extra == 0 then first.toChar
      else
        val bytes = Array.newBuilder[Byte]
        bytes += first.toByte
        for _ <- 1 to extra do bytes += readByte().toByte
        val s = new String(bytes.result(), UTF_8)
        if s.length == 1 then s.charAt(0)
        else
          // outside the BMP: insert the low surrogate as a separate key
          pendingChars += s.charAt(1)
          s.charAt(0)

    private val pendingChars = mutable.Queue.empty[Char]

    private def readEscape(): Key =
      readByte() match
        case '[' =>
          val params = new StringBuilder
          var b = readByte()
          while b >= 0 && (b < 0x40 || b > 0x7e) do
            params.append(b.toChar)
            b = readByte()
          (params.toString, b.toChar) match
            case (_, 'A') => Key.Up
            case (_, 'B') => Key.Down
            case ("1;5" | "1;3" | "5" | "3", 'C') => Key.WordRight
            case ("1;5" | "1;3" | "5" | "3", 'D') => Key.WordLeft
            case (_, 'C') => Key.Right
            case (_, 'D') => Key.Left
            case (_, 'H') => Key.Home
            case (_, 'F') => Key.End
            case ("1" | "7", '~') => Key.Home
            case ("4" | "8", '~') => Key.End
            case ("3", '~') => Key.Delete
            case ("200", '~') => Key.Paste(readPaste())
            case _ => Key.Ignored
        case 'O' =>
          readByte().toChar match
            case 'A' => Key.Up
            case 'B' => Key.Down
            case 'C' => Key.Right
            case 'D' => Key.Left
            case 'H' => Key.Home
            case 'F' => Key.End
            case _ => Key.Ignored
        case 'b' => Key.WordLeft
        case 'f' => Key.WordRight
        case 'd' => Key.AltD
        case 127 | 8 => Key.AltBackspace
        case _ => Key.Ignored

    /** Pasted text, up to the end of the bracketed paste. */
    private def readPaste(): String =
      val bytes = new java.io.ByteArrayOutputStream
      val end = "\u001b[201~".getBytes(UTF_8)
      var matched = 0
      var b = readByte()
      while b >= 0 && matched < end.length do
        if b == end(matched) then matched += 1
        else
          if matched > 0 then bytes.write(end, 0, matched)
          matched = if b == end(0) then 1 else 0
          if matched == 0 then bytes.write(b)
        if matched < end.length then b = readByte()
      new String(bytes.toByteArray, UTF_8)

    // ---- editing ----

    private def insert(s: String): Unit =
      val text = s + pendingChars.mkString
      pendingChars.clear()
      buf.insert(cursor, text)
      cursor += text.length
      redraw()

    private def delete(from: Int, to: Int): Unit =
      buf.delete(from, to)
      cursor = from
      redraw()

    private def kill(from: Int, to: Int): Unit =
      if from < to then
        killed = buf.substring(from, to)
        delete(from, to)

    private def move(to: Int): Unit =
      cursor = to
      redraw()

    private def lineStart(at: Int): Int = buf.lastIndexOf("\n", at - 1) + 1

    private def lineEnd(at: Int): Int =
      val nl = buf.indexOf("\n", at)
      if nl < 0 then buf.length else nl

    private def isWordChar(c: Char) = c.isLetterOrDigit || c == '_'

    private def wordStart(at: Int): Int =
      var i = at
      while i > 0 && !isWordChar(buf.charAt(i - 1)) do i -= 1
      while i > 0 && isWordChar(buf.charAt(i - 1)) do i -= 1
      i

    private def wordEnd(at: Int): Int =
      var i = at
      while i < buf.length && !isWordChar(buf.charAt(i)) do i += 1
      while i < buf.length && isWordChar(buf.charAt(i)) do i += 1
      i

    private def up(): Unit =
      val start = lineStart(cursor)
      if start > 0 then
        val column = cursor - start
        val previous = lineStart(start - 1)
        move(math.min(previous + column, start - 1))
      else if historyIndex > 0 then
        if historyIndex == history.length then draft = buf.toString
        historyIndex -= 1
        replace(history(historyIndex), atEnd = false)

    private def down(): Unit =
      val end = lineEnd(cursor)
      if end < buf.length then
        val column = cursor - lineStart(cursor)
        move(math.min(end + 1 + column, lineEnd(end + 1)))
      else if historyIndex < history.length then
        historyIndex += 1
        replace(if historyIndex == history.length then draft else history(historyIndex), atEnd = true)

    /** Replaces the whole input, the cursor at the end of its first line or
     *  at its end.
     */
    private def replace(text: String, atEnd: Boolean): Unit =
      buf.setLength(0)
      buf.append(text)
      cursor = if atEnd then buf.length else lineEnd(0)
      redraw()

    private def completeWord(list: Boolean): Unit =
      val (start, candidates) =
        try complete(buf.toString, cursor)
        catch case NonFatal(_) => (cursor, Nil)
      val word = buf.substring(start, cursor)
      candidates.distinct match
        case Nil =>
        case only :: Nil =>
          buf.replace(start, cursor, only)
          cursor = start + only.length
          redraw()
        case several =>
          val common = several.reduce((a, b) => a.zip(b).takeWhile(_ == _).map(_._1).mkString)
          if common.length > word.length then
            buf.replace(start, cursor, common)
            cursor = start + common.length
            redraw()
          else if list then
            showCandidates(several.sorted)

    /** Lists `candidates` in columns below the input, then draws it again. */
    private def showCandidates(candidates: List[String]): Unit =
      move(buf.length)
      write("\r\n")
      val width = columns
      val cell = candidates.map(_.length).max + 2
      val perRow = math.max(1, width / cell)
      for row <- candidates.grouped(perRow) do
        write(row.map(c => c + " " * (cell - c.length)).mkString.stripTrailing + "\r\n")
      write(prompt)
      cursorRow = 0
      redraw()

    /** Ctrl-R: searches the history backwards while a query is typed. Enter
     *  or an editing key keeps the match; Ctrl-C, Ctrl-G or Escape restores
     *  the input from before the search.
     */
    private def search(): Unit =
      val before = buf.toString
      var query = ""
      var from = history.length - 1
      var found = -1
      def show(): Unit =
        val shown = if found >= 0 then history(found) else ""
        write(s"\r\u001b[J(reverse-i-search)`$query': ${shown.replace("\n", "\\n")}")
      def find(start: Int): Unit =
        var i = start
        while i >= 0 && !history(i).contains(query) do i -= 1
        if i >= 0 then
          found = i
          from = i
      clearInput()
      show()
      var searching = true
      while searching do
        readKey() match
          case Key.Char(c) =>
            query += c
            find(if found >= 0 then found else from)
            show()
          case Key.Backspace =>
            query = query.dropRight(1)
            show()
          case Key.Ctrl('R') =>
            if found > 0 then find(found - 1)
            show()
          case Key.Ctrl('C') | Key.Ctrl('G') | Key.Ignored =>
            write("\r\u001b[J")
            write(prompt)
            cursorRow = 0
            replace(before, atEnd = true)
            searching = false
          case _ =>
            write("\r\u001b[J")
            write(prompt)
            cursorRow = 0
            if found >= 0 then historyIndex = found
            replace(if found >= 0 then history(found) else before, atEnd = true)
            searching = false

    /** Erases the drawn input, with its prompt. */
    private def clearInput(): Unit =
      if cursorRow > 0 then write(s"\u001b[${cursorRow}A")
      write("\r\u001b[J")
      cursorRow = 0

    // ---- drawing ----

    /** Draws the input again after the prompt, which is on screen. */
    private def redraw(): Unit =
      val width = columns
      val text = buf.toString
      val lines = text.split("\n", -1)
      val highlighted =
        val h = try highlight(text) catch case NonFatal(_) => text
        val hs = h.split("\n", -1)
        if hs.length == lines.length then hs else lines
      // rows and columns: each line starts after a prompt `promptWidth` wide
      def position(offset: Int): (Int, Int) =
        var row = 0
        var lineIndex = 0
        var remaining = offset
        while lineIndex < lines.length && remaining > lines(lineIndex).length do
          row += (promptWidth + lines(lineIndex).length) / width + 1
          remaining -= lines(lineIndex).length + 1
          lineIndex += 1
        val col = promptWidth + remaining
        (row + col / width, col % width)
      val sb = new StringBuilder
      // back to the start of the first line, after the prompt
      if cursorRow > 0 then sb.append(s"\u001b[${cursorRow}A")
      sb.append('\r')
      if promptWidth > 0 then sb.append(s"\u001b[${promptWidth}C")
      sb.append("\u001b[J")
      for (line, i) <- highlighted.zipWithIndex do
        if i > 0 then sb.append("\r\n").append(continuation)
        sb.append(line).append("\u001b[0m")
        // at the right margin, the terminal waits before wrapping: wrap now
        if lines(i).nonEmpty && (promptWidth + lines(i).length) % width == 0 then sb.append(" \r")
      val (endRow, _) = position(text.length)
      val (row, col) = position(cursor)
      if endRow > row then sb.append(s"\u001b[${endRow - row}A")
      sb.append('\r')
      if col > 0 then sb.append(s"\u001b[${col}C")
      write(sb.toString)
      cursorRow = row
  end Session
end LineEditor

object LineEditor:
  private val MaxHistory = 1000
  /** How long a wait for a key lasts before `idle` is called. */
  private val IdleMillis = 200
  private val BracketedPasteOn = "\u001b[?2004h"
  private val BracketedPasteOff = "\u001b[?2004l"

  /** The default history file, `~/.snc_repl_history`. */
  def defaultHistoryFile: Option[Path] =
    sys.env.get("HOME").map(home => Paths.get(home, ".snc_repl_history"))

  private def escape(entry: String): String =
    entry.replace("\\", "\\\\").replace("\n", "\\n")

  private def unescape(line: String): String =
    val sb = new StringBuilder
    var i = 0
    while i < line.length do
      val c = line.charAt(i)
      if c == '\\' && i + 1 < line.length then
        sb.append(if line.charAt(i + 1) == 'n' then '\n' else line.charAt(i + 1))
        i += 2
      else
        sb.append(c)
        i += 1
    sb.toString

  private enum Key:
    case Char(c: scala.Char)
    case Ctrl(c: scala.Char)
    case Paste(text: String)
    case Enter, Tab, Backspace, Delete, Left, Right, Up, Down, Home, End, WordLeft, WordRight,
      AltD, AltBackspace, Ignored, Eof
end LineEditor
