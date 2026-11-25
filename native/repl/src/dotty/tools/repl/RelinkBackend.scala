package dotty.tools
package repl

import java.io.{File, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import dotc.core.Contexts.*
import dotc.util.NativeFiles
import dotty.tools.io.{AbstractFile, Directory, PlainDirectory}
import dotty.vendored.fansi

/** How `snc repl` runs lines (see README.md, "REPL").
 *
 *  @param snlink        the Scala Native linker
 *  @param linkClasspath what lines are linked with besides the session's
 *                       output: the class path, whose Scala Native libraries
 *                       and Scala library NIR (scalalib, scala3lib) it needs,
 *                       and the REPL runtime
 *  @param linkOptions   more options for `snlink` (`--mode`, `--gc`, ...)
 *  @param workDir       where sessions keep their code and links
 *  @param timings       where to append the time each run takes, if anywhere
 *  @param firstLine     the number of the session's first wrapper (`rs$line$N`)
 *  @param commands      the only commands a driven REPL runs, by name, if
 *                       it runs only some
 */
final case class NativeReplConfig(
  snlink: String,
  linkClasspath: List[Path],
  linkOptions: List[String],
  workDir: Path,
  timings: Option[Path],
  backend: NativeReplConfig.Backend = NativeReplConfig.Backend.Evaluator,
  hostBinary: Option[Path] = None,
  hostLinkOptions: List[String] = Nil,
  captureOutput: Boolean = false,
  driven: Boolean = false,
  firstLine: Int = 1,
  commands: Option[Set[String]] = None)

object NativeReplConfig:
  /** How lines run: in a long-lived evaluator (`EvaluatorBackend`, the
   *  default), or by relinking and replaying the session (`RelinkBackend`).
   */
  enum Backend:
    case Evaluator, Relink

/** The relink-and-replay backend of the native REPL. Each line that has
 *  values to show is linked by `snlink`, with every earlier line, into a
 *  program whose main (`NativeReplCompiler.GenRunner`) runs the earlier lines
 *  again with their output discarded, then the new line, and writes what its
 *  `$show` gives to a file. Values do not survive between programs: an
 *  earlier line's side effects run again, unseen, and its values are computed
 *  again.
 *
 *  Linking reuses `snlink`'s incremental build in `workDir/link`, shared by
 *  the sessions of one process, but every new class renumbers the class ids
 *  and so changes every IR file, which `snlink` then compiles again.
 *  `EvaluatorBackend` neither relinks nor replays: it loads each line as an
 *  extension into a long-lived evaluator, which keeps the values.
 */
class RelinkBackend(config: NativeReplConfig) extends NativeReplBackend:

  private val sessionDir = Files.createTempDirectory(Files.createDirectories(config.workDir), "session")
  private val classesDir = Files.createDirectories(sessionDir.resolve("classes"))
  private val linkDir = config.workDir.resolve("link")
  private val resultsFile = sessionDir.resolve("results")
  private var extraClasspath = Vector.empty[Path]
  /** The output directories of the session's lines (`:settings -d` changes it). */
  private var outputDirs = Vector(classesDir)

  private val compiler = new NativeReplCompiler

  def newCompiler(): ReplCompiler = compiler

  def defaultOutputDir(): AbstractFile = new PlainDirectory(Directory(classesDir))

  def addToClasspath(entries: Seq[Path])(using Context): Unit =
    extraClasspath ++= entries

  override def close(): Unit = NativeFiles.deleteRecursively(sessionDir)

  def run(line: ReplLine)(using Context): ReplResult =
    // A line whose wrapper's initialization does nothing need not run
    if line.values.isEmpty && !compiler.initializes(line.index) then ReplResult.Values(Nil)
    else
      val start = System.nanoTime()
      val result =
        link(line).flatMap: binary =>
          val linked = System.nanoTime()
          val result = execute(binary, line)
          record(line, start, linked, System.nanoTime())
          result
      result.fold(message => ReplResult.Failed(fansi.Str(message)), identity)

  /** The linked program for `line`, or why there is none. */
  private def link(line: ReplLine)(using Context): Either[String, Path] =
    ctx.settings.outputDir.value.jpath match
      case null =>
      case path => if !outputDirs.contains(path) then outputDirs :+= path
    val command =
      List(config.snlink, "--main", NativeReplCompiler.runnerName(line.index), "--base-dir", linkDir.toString)
      ++ config.linkOptions
      ++ (outputDirs ++ extraClasspath ++ config.linkClasspath).map(_.toString)
    val log = sessionDir.resolve("link.log")
    val process = new ProcessBuilder(command.asJava)
      .redirectErrorStream(true)
      .redirectOutput(log.toFile)
      .start()
    val exit = process.waitFor()
    val binary = linkDir.resolve("out")
    if exit == 0 && Files.isRegularFile(binary) then Right(binary)
    else if exit == 130 || exit == 128 + 2 then Left("Interrupted.")
    else
      Left(NativeReplBackend.linkError(s"Linking the line failed (snlink exited with $exit):",
        Files.readAllLines(log, UTF_8).asScala.toList))

  /** Runs `binary`, copying its output to standard output, and reads the results. */
  private def execute(binary: Path, line: ReplLine)(using Context): Either[String, ReplResult] =
    Files.deleteIfExists(resultsFile)
    val command = List(
      binary.toString,
      resultsFile.toString,
      ctx.settings.pageWidth.value.toString,
      ctx.settings.XreplPrintHeight.value.toString,
      (ctx.settings.color.value != "never").toString,
      ReplShow.offsets(line).mkString(","))
    val process = new ProcessBuilder(command.asJava)
      .redirectErrorStream(true)
      .redirectInput(ProcessBuilder.Redirect.INHERIT)
      .start()
    copy(process.getInputStream)
    val exit = process.waitFor()
    if !Files.isRegularFile(resultsFile) then
      if exit == 130 || exit == 128 + 2 then Left("Interrupted.")
      else Left(s"The line ended without results (exit code $exit).")
    else parse(Files.readAllBytes(resultsFile), line)

  /** Copies the program's output to `System.out` (the REPL's output while it
   *  interprets an input) as it arrives.
   */
  private def copy(in: InputStream): Unit =
    val out = System.out
    val buffer = new Array[Byte](8192)
    var n = in.read(buffer)
    while n >= 0 do
      out.write(buffer, 0, n)
      out.flush()
      n = in.read(buffer)
    in.close()

  /** Reads the results file that `dotty.tools.repl.runtime.Runner` writes. */
  private def parse(bytes: Array[Byte], line: ReplLine)(using Context): Either[String, ReplResult] =
    var pos = 0
    def readLine(): String =
      val end = bytes.indexOf('\n'.toByte, pos)
      val s = new String(bytes, pos, end - pos, UTF_8)
      pos = end + 1
      s
    def readText(): String | Null =
      val length = readLine().toInt
      if length < 0 then null
      else
        val s = new String(bytes, pos, length, UTF_8)
        pos += length + 1
        s
    try
      readLine() match
        case "ok" =>
          val count = readLine().toInt
          val texts = List.fill(count)(readText())
          Right(ReplShow.results(line, texts))
        case "error" =>
          Right(ReplResult.Failed(fansi.Str(String.valueOf(readText()))))
        case "replay-error" =>
          val index = readLine()
          Right(ReplResult.Failed(fansi.Str(
            s"""Running the earlier line $index again failed (the native REPL replays the earlier lines for each new one):
               |${readText()}""".stripMargin)))
        case other => Left(s"Unexpected results: $other")
    catch case NonFatal(e) => Left(s"Unreadable results: $e")

  private def record(line: ReplLine, start: Long, linked: Long, end: Long): Unit =
    for file <- config.timings do
      val entry = s"line ${line.index}: link ${(linked - start) / 1000000} ms, run ${(end - linked) / 1000000} ms\n"
      Files.write(file, entry.getBytes(UTF_8),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
end RelinkBackend
