package dotty.tools.repl

import java.io.File
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

import dotty.tools.dotc.config.SncDistribution

/** Dependency resolution in the native REPL (`:dep`, `:repo`, `:toolkit`
 *  and `//> using dep`), with coursier's native launcher (`Coursier`): the
 *  JVM REPL resolves with coursier's Java interface, which `snc` does not
 *  link. This file replaces repl/src/dotty/tools/repl/DependencyResolver.scala
 *  in `snc`.
 *
 *  - `org::name:version` and `org::name::version` name the Scala Native
 *    artifact `name_native<binary version>_3`; `org:name:version` names the
 *    artifact as written.
 *  - The session's Scala and Scala Native versions win: the libraries every
 *    session has (Scala Native's, the Scala library and its NIR) are left out
 *    of the resolution, and Scala Native's other artifacts (its test
 *    interfaces) take the session's version when the distribution's
 *    repository has it.
 *  - Repositories: the distribution's, then those `:repo` added, then the
 *    local Ivy repository and Maven Central.
 *  - The jars that are on the class path already, or were added before, are
 *    left out of the result.
 */
object DependencyResolver:

  /** A repository, as coursier's command line takes it. */
  type Repository = String

  /** What resolution needs from the session (`NativeRepl` sets it): the
   *  versions of `snc`, its distribution and directory (to find coursier),
   *  the session's class path, and a directory for coursier's output.
   */
  final case class Session(
    scalaVersion: String,
    scalaNativeVersion: String,
    distribution: Option[SncDistribution],
    sncDir: Option[Path],
    classpath: List[Path],
    workDir: Path)

  private var session: Option[Session] = None
  /** The jars resolved for the current session's compiler. */
  private var added = Set.empty[Path]

  def configure(s: Session): Unit =
    session = Some(s)
    added = Set.empty

  /** A new session starts (`:reset`, `:replay`): its compiler has none of
   *  the jars resolved before.
   */
  def newSession(): Unit = added = Set.empty

  /** The Scala Native binary version: `0.5` for `0.5.13`. */
  private def nativeBinaryVersion: String =
    session.map(_.scalaNativeVersion).getOrElse("0.5").split('.').take(2).mkString(".")

  /** The suffix of Scala Native artifacts for Scala 3: `_native0.5_3`. */
  def nativeSuffix: String = s"_native${nativeBinaryVersion}_3"

  /** The libraries every session has on its class path, as `org:name`. */
  private def sessionLibraries: List[String] =
    List("nativelib", "clib", "posixlib", "windowslib", "javalib", "auxlib", "scalalib", "scala3lib")
      .map(m => s"org.scala-native:$m$nativeSuffix") ++
      List("org.scala-lang:scala-library", "org.scala-lang:scala3-library_3")

  private val aliases: Map[String, Repository] =
    val m2Local = Paths.get(System.getProperty("user.home"), ".m2", "repository").toUri.toString
    Map(
      "central" -> "central",
      "ivy2Local" -> "ivy2Local",
      "ivy2local" -> "ivy2Local",
      "m2Local" -> m2Local,
      "m2local" -> m2Local)

  /** A repository given as an alias (`central`, `ivy2Local`, `m2Local`), an
   *  `ivy:<pattern>`, a URL or a local directory.
   */
  def parseRepository(repository: String): Option[Repository] =
    aliases.get(repository).orElse:
      repository match
        case s"ivy:$pattern" if pattern.nonEmpty => Some(repository)
        case url if url.contains("://") => Some(url)
        case path if path.contains(File.separatorChar) && Files.isDirectory(Paths.get(path)) =>
          Some(Paths.get(path).toAbsolutePath.toUri.toString)
        case _ => None

  /** The organization, artifact and version of a dependency: see above. */
  def parseDependency(dep: String): Option[(String, String, String)] =
    def valid(parts: String*) = parts.forall(p => p.nonEmpty && !p.contains(':') && !p.exists(_.isWhitespace))
    dep.trim match
      case s"$org::$artifact::$version" if valid(org, artifact, version) =>
        Some((org, artifact + nativeSuffix, version))
      case s"$org::$artifact:$version" if valid(org, artifact, version) =>
        Some((org, artifact + nativeSuffix, version))
      case s"$org:$artifact:$version" if valid(org, artifact, version) =>
        Some((org, artifact, version))
      case _ =>
        System.err.println("Unable to parse dependency \"" + dep + "\"")
        None

  /** Resolves `dependencies` with coursier's native launcher: the jars they
   *  add to the session, or why they cannot be resolved.
   */
  def resolveDependencies(
    dependencies: List[(String, String, String)],
    repositories: List[Repository] = Nil
  ): Either[String, List[File]] =
    if dependencies.isEmpty then Right(Nil)
    else session match
      case None => Left("this session cannot resolve dependencies")
      case Some(s) =>
        Coursier.find(s.sncDir, s.distribution).flatMap: launcher =>
          val local = s.distribution.flatMap(_.repository)
          val repos = local.map(_.toUri.toString).toList ++ repositories ++ List("ivy2Local", "central")
          val args =
            List("fetch", "--no-default")
              ++ repos.distinct.flatMap(r => List("-r", r))
              ++ sessionLibraries.flatMap(m => List("--exclude", m))
              ++ local.toList.flatMap(forcedVersions(_, s.scalaNativeVersion)).flatMap(m => List("--force-version", m))
              ++ dependencies.map((org, artifact, version) => s"$org:$artifact:$version")
          val (exit, out, err) = Coursier.run(launcher, args, s.workDir)
          if exit != 0 then Left(Coursier.errorLines(err).mkString(System.lineSeparator))
          else
            val files = out.map(_.trim).filter(_.nonEmpty).map(Paths.get(_).toAbsolutePath.normalize)
            def name(p: Path) = p.getFileName.toString
            val present = s.classpath.map(_.toAbsolutePath.normalize).toSet
            val presentNames = present.map(name)
            val fresh = files.filterNot(f => present(f) || presentNames(name(f)) || added(f))
            if fresh.isEmpty && files.nonEmpty then
              println("The dependencies' jars are on the class path already.")
            added ++= fresh
            Right(fresh.map(_.toFile))

  /** Scala Native's artifacts in the distribution's repository `repo` at
   *  the session's version `version`, other than the session's libraries:
   *  `org:name:version` for coursier's `--force-version`.
   */
  private def forcedVersions(repo: Path, version: String): List[String] =
    val dir = repo.resolve("org").resolve("scala-native")
    val modules =
      if !Files.isDirectory(dir) then Nil
      else
        val stream = Files.list(dir)
        try stream.iterator.asScala.map(_.getFileName.toString).toList.sorted
        finally stream.close()
    modules
      .filter(m => m.endsWith(nativeSuffix) && Files.isDirectory(dir.resolve(m).resolve(version)))
      .map(m => s"org.scala-native:$m")
      .filterNot(sessionLibraries.contains)
      .map(m => s"$m:$version")

  /** Adds `files` to the compiler's class path, as `:jar` does. */
  def addToCompilerClasspath(files: List[File])(using ctx: dotty.tools.dotc.core.Contexts.Context): Unit =
    import dotty.tools.dotc.classpath.{ClassPath, ClassPathFactory}
    import dotty.tools.dotc.core.SymbolLoaders
    import dotty.tools.dotc.core.Symbols.defn
    import dotty.tools.io.AbstractFile

    for file <- files do
      val jarFile = AbstractFile.getDirectory(file.getAbsolutePath, ctx.settings.javaOutputVersion.value)
      if jarFile != null then
        val jarClassPath = ClassPathFactory.newClassPath(jarFile)
        ctx.platform.addToClassPath(jarClassPath)
        SymbolLoaders.mergeNewEntries(defn.RootClass, ClassPath.RootPackage, jarClassPath, ctx.platform.classPath)

end DependencyResolver
