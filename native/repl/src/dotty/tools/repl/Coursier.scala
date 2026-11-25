package dotty.tools
package repl

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import dotty.tools.dotc.config.SncDistribution

/** Coursier's native launcher (`cs`), which resolves the native REPL's
 *  dependencies (`DependencyResolver`). Coursier publishes it, built with
 *  GraalVM's native-image, as a release asset per platform; it needs no JVM.
 *  Coursier's JVM launchers (a script that runs `java`, a jar) are refused.
 */
object Coursier:

  /** Where to get the native launcher, for messages. */
  val Download = "https://github.com/coursier/coursier/releases (cs-<platform>.gz)"

  /** The launcher: `$SNC_COURSIER`, else the first native `cs` or `coursier`
   *  on the `PATH`, else `cs` in the distribution's `bin/` (next to `snc`).
   *  A JVM launcher on the `PATH` is passed over; one in `SNC_COURSIER` is an
   *  error.
   */
  def find(sncDir: Option[Path], distribution: Option[SncDistribution]): Either[String, Path] =
    sys.env.get("SNC_COURSIER").filter(_.nonEmpty) match
      case Some(named) =>
        val path = Paths.get(named)
        if !Files.isRegularFile(path) then Left(s"SNC_COURSIER names $named, which does not exist.")
        else jvmMarker(path) match
          case Some(why) => Left(jvmMessage(path, why, "SNC_COURSIER names"))
          case None => Right(path)
      case None =>
        val onPath =
          for
            name <- List("cs", "coursier")
            dir <- sys.env.getOrElse("PATH", "").split(File.pathSeparatorChar).toList if dir.nonEmpty
            file = Paths.get(dir).resolve(name)
            if Files.isRegularFile(file) && Files.isExecutable(file)
          yield file
        val bundled =
          (sncDir.map(_.resolve("cs")).toList ++
            distribution.map(_.file.toAbsolutePath.getParent.getParent.resolve("bin").resolve("cs")).toList)
            .filter(f => Files.isRegularFile(f) && Files.isExecutable(f))
        val candidates = (onPath ++ bundled).distinct.map(p => (p, jvmMarker(p)))
        candidates.collectFirst { case (p, None) => p } match
          case Some(native) => Right(native)
          case None =>
            candidates.headOption match
              case Some((jvm, Some(why))) => Left(jvmMessage(jvm, why, "found"))
              case _ =>
                Left("dependencies are resolved with coursier's native launcher (cs), which runs without a JVM, " +
                  s"and none was found: put it on the PATH, or set SNC_COURSIER to it. It is at $Download.")

  private def jvmMessage(path: Path, why: String, how: String): String =
    s"$how $path, a JVM launcher of coursier ($why). snc repl resolves dependencies with coursier's native " +
      s"launcher (cs), which runs without a JVM: put it on the PATH, or set SNC_COURSIER to it. It is at $Download."

  /** Why `launcher` is a JVM launcher, if it is one: a jar, or a script
   *  that runs `java` or a jar. A native executable is not; a script without
   *  those signs is a wrapper, and what it runs, given by an absolute path, is
   *  checked in turn.
   */
  def jvmMarker(launcher: Path, depth: Int = 0): Option[String] =
    val file =
      try launcher.toRealPath()
      catch case NonFatal(_) => launcher
    val head = readHead(file, 1 << 16)
    def magic(bytes: Int*) = head.length >= bytes.length && bytes.indices.forall(i => (head(i) & 0xff) == bytes(i))
    val native =
      magic(0xcf, 0xfa, 0xed, 0xfe) || magic(0xce, 0xfa, 0xed, 0xfe) || // Mach-O
        magic(0xca, 0xfe, 0xba, 0xbe) && head.length > 8 && (head(7) & 0xff) < 0x20 || // Mach-O universal
        magic(0x7f, 'E', 'L', 'F') // ELF
    if native then None
    else if magic('P', 'K', 3, 4) then Some("a jar")
    else if magic('#', '!') then
      // The script itself, before any archive appended to it
      val text = new String(head.takeWhile(_ != 0), UTF_8)
      val Jvm = """(?m)(\bjava\b|JAVA_HOME|\.jar\b|-jar\b)""".r
      Jvm.findFirstIn(text) match
        case Some(sign) => Some(s"a script that mentions $sign")
        case None =>
          val Exec = """(?m)^\s*exec\s+"?(/[^"\s]+)"?""".r
          Exec.findFirstMatchIn(text).map(m => Paths.get(m.group(1)))
            .filter(p => depth < 3 && Files.isRegularFile(p))
            .flatMap(jvmMarker(_, depth + 1))
    else None

  private def readHead(file: Path, max: Int): Array[Byte] =
    try
      val in = Files.newInputStream(file)
      try in.readNBytes(max)
      finally in.close()
    catch case NonFatal(_) => Array.emptyByteArray

  /** Runs `launcher` with `args`, with its output in files under `workDir`,
   *  and waits for it; Ctrl-C stops it. Its exit code, standard output and
   *  standard error.
   */
  def run(launcher: Path, args: List[String], workDir: Path): (Int, List[String], List[String]) =
    Files.createDirectories(workDir)
    val out = Files.createTempFile(workDir, "coursier", ".out")
    val err = Files.createTempFile(workDir, "coursier", ".err")
    try
      val process = new ProcessBuilder((launcher.toString :: args).asJava)
        .redirectOutput(out.toFile)
        .redirectError(err.toFile)
        .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
        .start()
      EvaluatorBackend.Interrupts.install()
      EvaluatorBackend.Interrupts.clear()
      // With a timeout, waitFor reaps the process in a single-threaded snc
      while !process.waitFor(50, TimeUnit.MILLISECONDS) do
        if EvaluatorBackend.Interrupts.pending then process.destroy()
      val interrupted = EvaluatorBackend.Interrupts.pending
      EvaluatorBackend.Interrupts.clear()
      def lines(p: Path) = Files.readAllLines(p, UTF_8).asScala.toList
      if interrupted then (130, Nil, List("Interrupted."))
      else (process.exitValue(), lines(out), lines(err))
    finally
      Files.deleteIfExists(out)
      Files.deleteIfExists(err)

  /** What a failed resolution printed that says why: not its progress, nor
   *  the warnings of the launcher's runtime.
   */
  def errorLines(stderr: List[String]): List[String] =
    val noise = List("Downloading ", "Downloaded ", "Failed to download ", "WARNING: ")
    val relevant = stderr.filterNot(l => l.trim.isEmpty || noise.exists(l.startsWith))
    if relevant.nonEmpty then relevant else stderr.filter(_.trim.nonEmpty).takeRight(10)
end Coursier
