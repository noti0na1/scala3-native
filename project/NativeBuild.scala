import sbt.*
import sbt.Keys.*

import dotty.tools.sbtplugin.Versions
import scala.scalanative.build.NativeConfig
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.*

/** Scala Native builds of the compiler. They live apart from `Build.scala` to
 *  keep merges from upstream simple.
 */
object NativeBuild {

  /** The Scala Native compiler plugin is built into the native compiler, which
   *  cannot load plugins: from its published sources, which include the nir
   *  and util sources it is built with, for the Scala 3 nightly that this
   *  compiler matches. They are published locally from the companion Scala
   *  Native fork (README.md, "Building"), into the Ivy home that this build
   *  is run with (`-Dsbt.ivy.home=<dir>`, by default `~/.ivy2`).
   */
  val scalaNativeVersion = "0.5.13-SNAPSHOT"
  /** The Scala 3 nightly that this branch is based on, which snc reports as
   *  its Scala version: projects compiled with snc use its standard library.
   */
  val nscpluginScalaVersion = "3.11.0-RC1-bin-20261119-90f3daf-NIGHTLY"
  val NscPluginSources = config("nscplugin-sources").hide
  /** The same plugin's classes, for its miniphases' hook table */
  val NscPluginJvm = config("nscplugin-jvm").hide

  /** Scala Native's junit-plugin, built into snc the same way. It is only
   *  published for the reference compiler, whose sources build against this
   *  compiler too.
   */
  val JUnitPluginSources = config("junit-plugin-sources").hide
  val JUnitPluginJvm = config("junit-plugin-jvm").hide

  /** The JVM build of the same compiler sources */
  private val jvmCompiler = LocalProject("scala3-compiler-nonbootstrapped")

  /** NIR for the Java interfaces the native compiler uses, from Scala
   *  versions of them: those in interfaces/src, and the enums of sbt's
   *  compiler-interface that dependency tracking uses (native/sbt-interfaces).
   *  Only NIR is exported: the compiler still compiles against the Java
   *  interfaces, whose rules for `()` calls and nulls its code relies on, and
   *  NIR names do not depend on either.
   */
  lazy val `scala3-interfaces-native` =
    Project("scala3-interfaces-native", file("native/interfaces"))
      .enablePlugins(ScalaNativePlugin)
      .settings(
        scalaVersion := Versions.referenceVersion,
        scalacOptions ~= (_.filterNot(_ == "-Werror")),
        Compile / unmanagedSourceDirectories := Seq(
          baseDirectory.value / "src",
          (ThisBuild / baseDirectory).value / "native" / "sbt-interfaces" / "src"
        ),
        exportJars := true,
        Compile / packageBin / mappings ~= (_.filter(_._2.endsWith(".nir")))
      )

  /** tasty-core and the compiler compiled to NIR and linked into a native
   *  `dotc`, with the reference compiler like the non-bootstrapped artifacts.
   *
   *  Scala Native cannot compile Java, so the Java sources that the native
   *  compiler needs have Scala versions under native/: the interfaces in
   *  `scala3-interfaces-native`, the compiler's own in native/compiler/src.
   *  The other Java sources go through javac only and must stay unreachable
   *  from `NativeMain`, like the JVM-only libraries (ASM, compiler-interface).
   *  Code that needs the JVM is guarded by `PlatformHooks.isJvm`.
   */
  lazy val `scala3-compiler-native` =
    Project("scala3-compiler-native", file("native/compiler"))
      .enablePlugins(ScalaNativePlugin)
      .dependsOn(Build.`scala3-interfaces`, `scala3-interfaces-native`)
      .settings(
        scalaVersion := Versions.referenceVersion,
        scalacOptions ~= (_.filterNot(_ == "-Werror")),
        Compile / unmanagedSourceDirectories := {
          val root = (ThisBuild / baseDirectory).value
          Seq(
            root / "tasty" / "src",
            root / "compiler" / "src",
            root / "compiler" / "src-non-bootstrapped",
            root / "native" / "compiler" / "src"
          )
        },
        // A file under native/compiler/src replaces the compiler file at the
        // same path; a Scala file can replace a Java one
        Compile / unmanagedSources := {
          val root = (ThisBuild / baseDirectory).value
          val overlay = root / "native" / "compiler" / "src"
          val compilerSrc = root / "compiler" / "src"
          def withoutExtension(path: String) = path.replaceAll("\\.(scala|java)$", "")
          val replaced = (overlay ** ("*.scala" || "*.java")).get
            .flatMap(IO.relativize(overlay, _))
            .map(withoutExtension)
            .toSet
          (Compile / unmanagedSources).value.filterNot { file =>
            IO.relativize(compilerSrc, file).exists(p => replaced(withoutExtension(p)))
          }
        },
        // The Scala.js IR sources that the JVM build unpacks and shades
        Compile / sourceGenerators += Def.task {
          (LocalProject("scala3-compiler-nonbootstrapped") / Compile / managedSources).value
        }.taskValue,
        libraryDependencies ++= Seq(
          Dependencies.asmUtil,
          Dependencies.asmCommons,
          Dependencies.sbtCompilerInterface
        ),
        // The non-bootstrapped compiler's compiler.properties and its standard
        // library's library.properties, so that both report the same versions:
        // in TASTy, and to plugins through ScalaVersion.current
        // MegaPhase's hook table, from the JVM compiler's classes
        Compile / sourceGenerators += Def.task {
          val roots = Seq((jvmCompiler / Compile / classDirectory).value)
          val classpath = (jvmCompiler / Runtime / fullClasspath).value.files
          val _ = (jvmCompiler / Compile / compile).value
          Seq(writeMiniPhaseHooks(
            (Compile / sourceManaged).value / "MiniPhaseHooks.scala",
            "dotty.tools.dotc.transform", "MiniPhaseHooks",
            miniPhaseHooks(classpath, roots)))
        }.taskValue,
        Compile / resourceGenerators += Def.task {
          (LocalProject("scala3-compiler-nonbootstrapped") / Compile / managedResources).value ++
            (LocalProject("scala-library-nonbootstrapped") / Compile / managedResources).value
        }.taskValue,
        Compile / mainClass := Some("dotty.tools.dotc.NativeMain"),
        nativeConfig ~= nativeCompilerConfig
      )

  /** `snc`: the native compiler with the Scala Native compiler plugin built in.
   *  `-Xplugin` paths to the plugin's jar select it, as on the JVM.
   */
  lazy val snc =
    Project("snc", file("native/snc"))
      .enablePlugins(ScalaNativePlugin)
      .dependsOn(`scala3-compiler-native`)
      .settings(
        scalaVersion := Versions.referenceVersion,
        // As in scala-native, where nscplugin is written without explicit nulls
        scalacOptions --= Seq("-Werror", "-Yexplicit-nulls", "-Wsafe-init"),
        Compile / unmanagedSourceDirectories := Seq(baseDirectory.value / "src"),
        ivyConfigurations ++= Seq(NscPluginSources, NscPluginJvm, JUnitPluginSources, JUnitPluginJvm),
        libraryDependencies ++= Seq(
          ("org.scala-native" % s"nscplugin_$nscpluginScalaVersion" % scalaNativeVersion % NscPluginSources)
            .classifier("sources").intransitive(),
          ("org.scala-native" % s"nscplugin_$nscpluginScalaVersion" % scalaNativeVersion % NscPluginJvm)
            .intransitive(),
          ("org.scala-native" % s"junit-plugin_${Versions.referenceVersion}" % scalaNativeVersion % JUnitPluginSources)
            .classifier("sources").intransitive(),
          ("org.scala-native" % s"junit-plugin_${Versions.referenceVersion}" % scalaNativeVersion % JUnitPluginJvm)
            .intransitive()
        ),
        Compile / sourceGenerators += Def.task {
          val report = update.value
          Seq(NscPluginSources -> "nscplugin", JUnitPluginSources -> "junit-plugin").flatMap { case (conf, dir) =>
            val out = (Compile / sourceManaged).value / dir
            IO.delete(out)
            val jars = report.select(configurationFilter(conf.name))
            jars.flatMap(IO.unzip(_, out, GlobFilter("*.scala")))
          }
        }.taskValue,
        // The plugins' miniphases, for MegaPhase's hook table
        Compile / sourceGenerators += Def.task {
          val report = update.value
          val roots = Seq(NscPluginJvm, JUnitPluginJvm).flatMap(c => report.select(configurationFilter(c.name)))
          val classpath = (jvmCompiler / Runtime / fullClasspath).value.files ++ roots
          Seq(writeMiniPhaseHooks(
            (Compile / sourceManaged).value / "NscPluginMiniPhaseHooks.scala",
            "dotty.tools.dotc", "NscPluginMiniPhaseHooks",
            miniPhaseHooks(classpath, roots)))
        }.taskValue,
        // The versions snc reports in --about
        Compile / sourceGenerators += Def.task {
          val file = (Compile / sourceManaged).value / "SncBuildInfo.scala"
          val content =
            s"""package dotty.tools.dotc
               |
               |// Generated by NativeBuild.
               |
               |/** The versions that snc is built from. */
               |object SncBuildInfo:
               |  /** The published Scala 3 nightly that the compiler corresponds to:
               |   *  the version of the standard library to compile and link with.
               |   */
               |  val scalaVersion: String = "$nscpluginScalaVersion"
               |  /** The Scala Native version of the built-in plugins. */
               |  val scalaNativeVersion: String = "$scalaNativeVersion"
               |""".stripMargin
          if (!file.exists || IO.read(file) != content) IO.write(file, content)
          Seq(file)
        }.taskValue,
        Compile / mainClass := Some("dotty.tools.dotc.SncMain"),
        nativeConfig ~= nativeCompilerConfig
      )

  /** For each concrete miniphase class among `roots` (class directories or
   *  jars), the `MiniPhase` methods that it or a superclass redefines, which
   *  `MegaPhase` finds by reflection on the JVM. The native compiler has no
   *  reflection, so it gets them as a table.
   */
  private def miniPhaseHooks(classpath: Seq[File], roots: Seq[File]): Seq[(String, Seq[String])] = {
    import java.lang.reflect.Modifier
    import scala.collection.JavaConverters._
    val loader = new java.net.URLClassLoader(
      classpath.map(_.toURI.toURL).toArray, ClassLoader.getPlatformClassLoader)
    try {
      val miniPhase = Class.forName("dotty.tools.dotc.transform.MegaPhase$MiniPhase", false, loader)
      val hooks = miniPhase.getDeclaredMethods.map(_.getName)
        .filter(n => n.startsWith("prepareFor") || n.startsWith("transform")).toSet
      val classNames = roots.flatMap { root =>
        val paths =
          if (root.isDirectory) (root ** "*.class").get.flatMap(IO.relativize(root, _))
          else {
            val jar = new java.util.jar.JarFile(root)
            try jar.entries.asScala.map(_.getName).filter(_.endsWith(".class")).toList
            finally jar.close()
          }
        paths.map(_.stripSuffix(".class").replace('/', '.'))
      }.sorted
      classNames.flatMap { name =>
        val cls =
          try Some(Class.forName(name, false, loader))
          catch { case _: ClassNotFoundException | _: LinkageError => None }
        cls.filter(c => c != miniPhase && miniPhase.isAssignableFrom(c) &&
            !c.isInterface && !Modifier.isAbstract(c.getModifiers)).map { c =>
          val classes = Iterator.iterate[Class[_]](c)(_.getSuperclass).takeWhile(_ != miniPhase)
          name -> classes.flatMap(_.getDeclaredMethods.map(_.getName)).filter(hooks).toSeq.distinct.sorted
        }
      }
    } finally loader.close()
  }

  /** Writes a hook table as a Scala object, unless the file already says the same. */
  private def writeMiniPhaseHooks(
      file: File, pkg: String, name: String, table: Seq[(String, Seq[String])]): File = {
    def quote(s: String) = "\"" + s + "\""
    val entries = table.map { case (cls, hooks) =>
      s"    ${quote(cls)} -> Set(${hooks.map(quote).mkString(", ")})"
    }
    val content =
      s"""package $pkg
         |
         |// Generated by NativeBuild.miniPhaseHooks from the JVM build's classes.
         |
         |/** The `MiniPhase` methods that each miniphase class redefines. */
         |object $name:
         |  val table: Map[String, Set[String]] = Map(
         |${entries.mkString(",\n")}
         |  )
         |""".stripMargin
    if (!file.exists || IO.read(file) != content) IO.write(file, content)
    file
  }

  private def nativeCompilerConfig(config: NativeConfig): NativeConfig =
    config
      .withCheckFatalWarnings(false)
      // Single-threaded: no GC safepoint polls, which read a thread-local on
      // every call (see PlatformHooks.Timer and Pickler.ParallelPickling)
      .withMultithreading(false)
      .withEmbedResources(true)
      // "/library.properties" lifts the toolchain's default exclusion of it
      .withResourceIncludePatterns(Seq("compiler.properties", "library.properties", "/library.properties"))
}
