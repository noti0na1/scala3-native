package dotty.tools
package repl

import java.io.{ByteArrayOutputStream, PrintStream}
import java.lang.System.lineSeparator as EOL
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import dotc.config.CommandLineParser

/** Runs the REPL's test transcripts (`repl/test-resources/repl`) against the
 *  native REPL, as `ScriptedTests` runs them against the JVM REPL
 *  (`ReplTest.testScript`): each `scala> ` line is one input, a first line
 *  `//> using options ...` gives compiler options, and the output must equal
 *  the transcript's lines, ignoring blank ones.
 *
 *  It prints PASS or FAIL for each transcript and the parity, writes the
 *  actual output of each failing transcript to `reportDir`, and the time of
 *  each input to `reportDir/inputs.tsv`.
 */
object TranscriptRunner:

  /** ReplTest's options that precede the class path, except `-Ydebug`: with
   *  it, reading the JDK's `ct.sym`, whose signatures name annotation classes
   *  it does not hold (`jdk.Profile+Annotation`), prints warnings.
   */
  val testOptions: List[String] = List("-color:never", "-pagewidth", "80")

  private val prompt = "scala> "

  private class Driver(settings: Array[String], buffer: ByteArrayOutputStream, config: NativeReplConfig)
  extends NativeReplDriver(settings, new PrintStream(buffer, true, UTF_8.name), config):

    def storedOutput(): String =
      val output = buffer.toString(UTF_8.name).replaceAll("\u001b\\[[0-9;]*m", "")
      buffer.reset()
      output

    /** The expected and the actual output of the transcript `lines`, and the
     *  time of each input in milliseconds.
     */
    def runScript(lines: List[String]): (List[String], List[String], List[Long]) =
      def nonBlank(line: String): Boolean = line.exists(!Character.isWhitespace(_))
      val expected = lines.filter(nonBlank)
      val opts = lines.headOption match
        case Some(s"//> using options $args") => CommandLineParser.tokenize(args)
        case _ => Nil
      val (optsLine, inputLines) = if opts.isEmpty then ("", lines) else (lines.head, lines.drop(1))
      resetToInitial(opts)
      storedOutput()
      val inputs = inputLines.filter(_.startsWith(prompt))
      val buf = new ArrayBuffer[String]
      val times = new ArrayBuffer[Long]
      inputs.foldLeft(initialState) { (state, input) =>
        val start = System.nanoTime()
        val nstate = run(input.drop(prompt.length))(using state)
        times += (System.nanoTime() - start) / 1000000
        (input + EOL + storedOutput()).linesIterator.foreach(buf.append)
        nstate
      }
      (expected, (optsLine :: buf.toList).filter(nonBlank), times.toList)
  end Driver

  /** Runs `files`; the exit code is 0 if all pass. */
  def runTranscripts(files: List[Path], settings: Array[String], config: NativeReplConfig, reportDir: Path): Int =
    Files.createDirectories(reportDir)
    val buffer = new ByteArrayOutputStream
    val driver = new Driver((testOptions ++ settings).toArray, buffer, config.copy(captureOutput = true))
    val inputsLog = reportDir.resolve("inputs.tsv")
    Files.write(inputsLog, "transcript\tinput\tms\n".getBytes(UTF_8))
    var passed = 0
    try
      for file <- files do
        val name = file.getFileName.toString
        val start = System.nanoTime()
        val outcome =
          try
            val lines = Files.readAllLines(file, UTF_8).asScala.toList
            val (expected, actual, times) = driver.runScript(lines)
            val log = times.zipWithIndex.map((ms, i) => s"$name\t${i + 1}\t$ms\n").mkString
            Files.write(inputsLog, log.getBytes(UTF_8), java.nio.file.StandardOpenOption.APPEND)
            if expected == actual then None
            else Some(actual.mkString("", "\n", "\n"))
          catch case NonFatal(e) =>
            val trace = new java.io.StringWriter
            e.printStackTrace(new java.io.PrintWriter(trace))
            Some(s"crashed: $trace")
        val seconds = (System.nanoTime() - start) / 1000000 / 1000.0
        outcome match
          case None =>
            passed += 1
            println(f"PASS $name ($seconds%.1f s)")
          case Some(actual) =>
            Files.write(reportDir.resolve(name + ".actual"), actual.getBytes(UTF_8))
            println(f"FAIL $name ($seconds%.1f s)")
    finally driver.close()
    println(s"parity: $passed/${files.length} transcripts")
    if passed == files.length then 0 else 1
end TranscriptRunner
