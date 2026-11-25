package dotty.tools.dotc.config

import java.io.File
import java.nio.file.{Files, Path}
import java.util.Properties

import scala.util.Try

/** The distribution that an `snc` comes in: a bundle with `bin/snc`,
 *  `bin/snlink`, `lib/` and a Maven repository `repo/` that holds the
 *  libraries programs compile and link with. The bundle says so in
 *  `lib/snc-distribution.properties`:
 *
 *  - `version`: the distribution's version;
 *  - `repository`: the Maven repository, relative to the file;
 *  - `repl.classpath`: the class path of `snc repl` when none is given,
 *    entries relative to the file and separated by `:`;
 *  - `repl.evaluator-host`: an evaluator host linked for that class path,
 *    with its manifest next to it, relative to the file (optional).
 *
 *  An `snc` without the file (one built in place) has no distribution.
 */
final case class SncDistribution(
  file: Path,
  version: String,
  repository: Option[Path],
  replClasspath: List[Path],
  evaluatorHost: Option[Path]):

  /** The repository as a URI, as resolvers take it. */
  def repositoryUri: Option[String] = repository.map(_.toUri.toString)

object SncDistribution:
  val fileName = "snc-distribution.properties"

  /** The distribution of the `snc` in `executableDir`: the file next to the
   *  executable or in `../lib`.
   */
  def find(executableDir: Option[Path]): Option[SncDistribution] =
    executableDir.toList
      .flatMap(dir => List(dir.resolve(fileName), dir.resolve("..").resolve("lib").resolve(fileName).normalize()))
      .find(Files.isRegularFile(_))
      .flatMap(read)

  def read(file: Path): Option[SncDistribution] =
    Try {
      val properties = new Properties
      val in = Files.newInputStream(file)
      try properties.load(in) finally in.close()
      val dir = file.toAbsolutePath.getParent
      def value(key: String): Option[String] = Option(properties.getProperty(key)).map(_.trim).filter(_.nonEmpty)
      def resolve(relative: String): Path = dir.resolve(relative).normalize()
      SncDistribution(
        file = file,
        version = value("version").getOrElse("unknown"),
        repository = value("repository").map(resolve).filter(Files.isDirectory(_)),
        replClasspath = value("repl.classpath").toList
          .flatMap(_.split(File.pathSeparatorChar).toList)
          .filter(_.nonEmpty)
          .map(resolve),
        evaluatorHost = value("repl.evaluator-host").map(resolve).filter(Files.isRegularFile(_)))
    }.toOption
end SncDistribution
