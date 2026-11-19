package dotty.tools.dotc

import java.io.File
import java.nio.file.{Files, Path, Paths}

import scala.scalanative.meta.LinktimeInfo
import scala.util.Try

import dotty.tools.dotc.classpath.JdkClassPath

/** Finds the JDK whose Java API `snc` compiles against, the first of:
 *
 *  1. `--java-home <dir>`;
 *  2. `$JAVA_HOME`;
 *  3. `java` on the `PATH`, with symbolic links resolved, as `<home>/bin/java`;
 *  4. on macOS, what `/usr/libexec/java_home` prints (`-v N+` for `-release N`);
 *  5. the JDKs that coursier has installed (`cs java`, scala-cli's `--jvm`,
 *     Mill's `jvmId`): its archive cache (`$COURSIER_ARCHIVE_CACHE`, by
 *     default `~/Library/Caches/Coursier/arc` on macOS and
 *     `~/.cache/coursier/arc` elsewhere) and its older JVM cache
 *     (`$COURSIER_JVM_CACHE`, `.../Coursier/jvm`); the newest one.
 *
 *  A JDK is a directory with a `release` file and `jmods/` or `lib/ct.sym`
 *  (a JRE has neither). The first two must name one; the others are passed
 *  over when they do not, or when they are older than `-release`.
 */
object SncJdk:

  /** A JDK, and which of the five ways found it. */
  final case class Found(home: Path, from: String)

  /** The JDK to compile against, or why there is none. */
  def find(javaHomeOption: Option[String], env: Map[String, String], release: Option[Int]): Either[String, Found] =
    javaHomeOption match
      case Some(dir) => explicit(dir, "--java-home")
      case None =>
        env.get("JAVA_HOME").filter(_.nonEmpty) match
          case Some(dir) => explicit(dir, "JAVA_HOME")
          case None =>
            val passedOver = List.newBuilder[String]
            def usable(home: Path, from: String): Option[Found] =
              JdkClassPath.jdk(home) match
                case Right(jdk) if isJdk(home) && release.forall(_ <= jdk.feature) => Some(Found(home, from))
                case Right(jdk) if isJdk(home) =>
                  passedOver += s"$home ($from) is JDK ${jdk.feature}, older than -release ${release.get}"
                  None
                case _ =>
                  passedOver += s"$home ($from) is not a JDK"
                  None
            onPath(env).flatMap(usable(_, "java on the PATH"))
              .orElse(macJavaHome(release).flatMap(usable(_, "/usr/libexec/java_home")))
              .orElse(coursier(env).iterator.flatMap(usable(_, "coursier")).nextOption()) match
              case Some(found) => Right(found)
              case None =>
                val notes = passedOver.result().distinct
                Left(
                  "snc needs a JDK to compile against (it reads the JDK's Java API and never runs it), " +
                  "and found none: " +
                  "pass --java-home <dir> or set JAVA_HOME" +
                  (if notes.isEmpty then
                    " (there is no java on the PATH" +
                    (if LinktimeInfo.isMac then ", /usr/libexec/java_home finds none" else "") +
                    ", and coursier has installed none)"
                  else notes.mkString(" (passed over: ", "; ", ")")))

  /** `--java-home` and `JAVA_HOME` must name a JDK. */
  private def explicit(dir: String, from: String): Either[String, Found] =
    val home = Paths.get(dir).toAbsolutePath.normalize
    if !Files.isDirectory(home) then Left(s"$from: $dir is not a directory")
    else if !Files.isRegularFile(home.resolve("release")) then
      Left(s"$from: $dir is not a JDK: it has no release file")
    else if !isJdk(home) then
      Left(s"$from: $dir has neither jmods/ nor lib/ct.sym, which snc reads the Java API from (a JRE?)")
    else Right(Found(home, from))

  private def isJdk(home: Path): Boolean =
    Files.isRegularFile(home.resolve("release")) &&
      (Files.isDirectory(home.resolve("jmods")) || Files.isRegularFile(home.resolve("lib").resolve("ct.sym")))

  /** The home of the first `java` on the `PATH`, links resolved. */
  private def onPath(env: Map[String, String]): Option[Path] =
    env.getOrElse("PATH", "").split(File.pathSeparatorChar).iterator
      .filter(_.nonEmpty)
      .map(dir => Paths.get(dir, "java"))
      .find(p => Files.isRegularFile(p) && Files.isExecutable(p))
      .flatMap(p => Try(p.toRealPath()).toOption)
      .flatMap(java => Option(java.getParent).filter(_.getFileName.toString == "bin").flatMap(b => Option(b.getParent)))

  /** What `/usr/libexec/java_home` prints, on macOS. */
  private def macJavaHome(release: Option[Int]): Option[Path] =
    if LinktimeInfo.isMac then javaHomeTool(release) else None

  private def javaHomeTool(release: Option[Int]): Option[Path] =
    val tool = Paths.get("/usr/libexec/java_home")
    if !Files.isExecutable(tool) then None
    else
      Try {
        val command = tool.toString :: release.toList.flatMap(n => List("-v", s"$n+"))
        val process = new ProcessBuilder(command*).start()
        process.getOutputStream.close()
        val out = new String(process.getInputStream.readAllBytes(), "UTF-8").trim
        process.getErrorStream.readAllBytes()
        if process.waitFor() == 0 && out.nonEmpty then Some(Paths.get(out)) else None
      }.toOption.flatten

  /** The JDKs in coursier's caches, newest first. */
  private def coursier(env: Map[String, String]): List[Path] =
    val home = Paths.get(System.getProperty("user.home", env.getOrElse("HOME", "/")))
    val cacheBase =
      if LinktimeInfo.isMac then home.resolve("Library").resolve("Caches").resolve("Coursier")
      else env.get("XDG_CACHE_HOME").filter(_.nonEmpty).map(Paths.get(_)).getOrElse(home.resolve(".cache"))
          .resolve("coursier")
    val roots =
      env.get("COURSIER_ARCHIVE_CACHE").filter(_.nonEmpty).map(Paths.get(_)).getOrElse(cacheBase.resolve("arc")) ::
      env.get("COURSIER_JVM_CACHE").filter(_.nonEmpty).map(Paths.get(_)).getOrElse(cacheBase.resolve("jvm")) :: Nil
    val found = List.newBuilder[Path]
    // A JDK is at most nine levels down: https/<host>/<path>/<archive>/<dir>/Contents/Home
    def walk(dir: File, depth: Int): Unit =
      if new File(dir, "release").isFile && new File(dir, "bin").isDirectory then found += dir.toPath
      else if depth > 0 then
        val children = dir.listFiles()
        if children != null then
          for child <- children.sortBy(_.getName) if child.isDirectory do walk(child, depth - 1)
    for root <- roots if Files.isDirectory(root) do walk(root.toFile, 10)
    found.result()
      .flatMap(h => JdkClassPath.jdk(h).toOption.map(jdk => (jdk.feature, h)))
      .sortBy((feature, h) => (-feature, h.toString))
      .map(_._2)
end SncJdk
