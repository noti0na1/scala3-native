package dotty.tools.dotc

import java.io.{OutputStream, PrintWriter}
import java.nio.charset.StandardCharsets

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.interfaces.Diagnostic.{ERROR, INFO, WARNING}
import dotty.tools.dotc.reporting.*
import dotty.tools.dotc.reporting.Diagnostic.*
import dotty.tools.dotc.util.SourcePosition

/** The event stream of `snc --events` (binary) and `snc --events-text`
 *  (JSON Lines), written to standard output. See README.md,
 *  section "Events", for the format; this is protocol version
 *  `SncEvents.protocol`.
 */
object SncEvents:
  /** Major and minor version of the protocol. A reader accepts the major
   *  version it knows and any minor version: minor versions only add events,
   *  fields and frame kinds, which readers skip.
   */
  val major = 1
  /** 1.1 adds the events of Zinc's analysis (`--analysis`, see `SncAnalysis`). */
  val minor = 1
  def protocol: String = s"$major.$minor"

  /** The binary stream starts with these four bytes, then the major and minor
   *  versions as unsigned 16-bit big-endian integers.
   */
  val magic: Array[Byte] = "SNCE".getBytes(StandardCharsets.US_ASCII).nn

  /** The kind of a frame whose payload is one event as a UTF-8 JSON object. */
  val JsonFrame: Byte = 1

  /** Where events go. Each event is a JSON object with an `"event"` field.
   *  An event is flushed at once unless `flush` is false, as for the many
   *  small events of the analysis, which clients read after `done` anyway.
   */
  sealed abstract class Sink(out: OutputStream):
    def emit(event: String, flush: Boolean = true): Unit
    def close(): Unit = out.flush()

  /** Frames: a 32-bit big-endian length of what follows it, a kind byte
   *  (`JsonFrame`), and the payload.
   */
  final class BinarySink(out: OutputStream) extends Sink(out):
    out.write(magic)
    writeShort(major)
    writeShort(minor)

    private def writeShort(n: Int): Unit =
      out.write((n >>> 8) & 0xff)
      out.write(n & 0xff)

    def emit(event: String, flush: Boolean = true): Unit =
      val payload = event.getBytes(StandardCharsets.UTF_8).nn
      val length = payload.length + 1
      out.write((length >>> 24) & 0xff)
      out.write((length >>> 16) & 0xff)
      out.write((length >>> 8) & 0xff)
      out.write(length & 0xff)
      out.write(JsonFrame.toInt)
      out.write(payload)
      if flush then out.flush()

  /** One event per line. */
  final class TextSink(out: OutputStream) extends Sink(out):
    def emit(event: String, flush: Boolean = true): Unit =
      out.write(event.getBytes(StandardCharsets.UTF_8).nn)
      out.write('\n')
      if flush then out.flush()

  def startEvent: String =
    Json.obj(
      "event" -> Json.str("start"),
      "protocol" -> Json.str(protocol),
      "compilerVersion" -> Json.str(config.Properties.versionNumberString))

  def doneEvent(exitCode: Int, errors: Int, warnings: Int): String =
    Json.obj(
      "event" -> Json.str("done"),
      "exitCode" -> exitCode.toString,
      "errors" -> errors.toString,
      "warnings" -> warnings.toString)

  /** A reporter that sends diagnostics to `sink` as `diagnostic` events, with
   *  the same filtering as `ConsoleReporter`. Messages without a position
   *  that `ConsoleReporter` would print to standard output, such as `-Vprint`
   *  output, go to `echoer` (standard error) as text.
   */
  final class EventReporter(sink: Sink, echoer: PrintWriter) extends AbstractReporter:
    def doReport(dia: Diagnostic)(using Context): Unit =
      if dia.level == INFO && !dia.pos.exists then
        echoer.println(messageAndPos(dia))
        echoer.flush()
      else sink.emit(diagnosticEvent(dia))

    /** The summary goes in the `done` event. */
    override def printSummary()(using Context): Unit = ()

    private def diagnosticEvent(dia: Diagnostic)(using Context): String =
      import Json.*
      val msg = dia.msg
      val severity = dia.level match
        case ERROR => "error"
        case WARNING => "warning"
        case _ => "info"
      val code =
        if msg.errorId == ErrorMessageID.NoExplanationID then None
        else
          val n = msg.errorId.errorNumber.toString
          Some("E" + "0" * (3 - n.length) + n)
      val kind = dia match
        case _: DeprecationWarning => Some("deprecation")
        case _: FeatureWarning => Some("feature")
        case _: UncheckedWarning => Some("unchecked")
        case _: MigrationWarning => Some("migration")
        case _: ConfigurationWarning => Some("configuration")
        case _ => None
      val related = dia.relatedInformation.map: info =>
        obj("message" -> str(info.message), "position" -> position(info.pos))
      val actions = msg.actions.map: action =>
        obj(
          "title" -> str(action.title),
          "description" -> opt(action.description),
          "patches" -> arr(action.patches.map: patch =>
            obj(
              "file" -> str(patch.srcPos.source.path),
              "start" -> patch.srcPos.start.toString,
              "end" -> patch.srcPos.end.toString,
              "replacement" -> str(patch.replacement))))
      obj(
        "event" -> str("diagnostic"),
        "severity" -> str(severity),
        "code" -> opt(code),
        "kind" -> opt(kind),
        "message" -> str(dia.message),
        "position" -> position(dia.pos.nonInlined),
        "explanation" -> opt(Some(msg.explanation).filter(_.nonEmpty)),
        "related" -> arr(related),
        "actions" -> arr(actions),
        "rendered" -> str(messageAndPos(dia)))

    private def position(pos: SourcePosition): String =
      import Json.*
      if !pos.exists || !pos.source.exists then "null"
      else
        def at(offset: Int, line: Int, column: Int) =
          obj("offset" -> offset.toString, "line" -> (line + 1).toString, "column" -> (column + 1).toString)
        obj(
          "file" -> str(pos.source.path),
          "line" -> (pos.line + 1).toString,
          "column" -> (pos.column + 1).toString,
          "start" -> at(pos.start, pos.startLine, pos.startColumn),
          "end" -> at(pos.end, pos.endLine, pos.endColumn))
  end EventReporter
end SncEvents

/** Writing JSON: values are passed around as JSON text. */
object Json:
  def obj(fields: (String, String)*): String =
    fields.map((k, v) => str(k) + ":" + v).mkString("{", ",", "}")

  def arr(values: Seq[String]): String = values.mkString("[", ",", "]")

  def opt(value: Option[String]): String = value.fold("null")(str)

  /** A JSON string, in ASCII: other characters are `\\u` escapes. */
  def str(s: String): String =
    val sb = new java.lang.StringBuilder(s.length + 2)
    appendStr(sb, s)
    sb.toString

  /** Appends `s` to `sb` as `str` writes it. */
  def appendStr(sb: java.lang.StringBuilder, s: String): Unit =
    sb.append('"')
    var i = 0
    while i < s.length do
      val ch = s.charAt(i)
      ch match
        case '"' => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case _ if ch < ' ' || ch > '~' =>
          val hex = Integer.toHexString(ch)
          sb.append("\\u").append("0" * (4 - hex.length)).append(hex)
        case _ => sb.append(ch)
      i += 1
    sb.append('"')
end Json
