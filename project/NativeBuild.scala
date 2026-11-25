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
   *  versions of them: those in interfaces/src, and the parts of sbt's
   *  compiler-interface that the sbt phases use, `xsbti.api` and `UseScope`
   *  (native/sbt-interfaces).
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

  /** The REPL of `snc` (`snc repl`, see README.md, "REPL"): the REPL's
   *  sources, where a file under native/repl/src replaces the one under
   *  repl/src at the same path, and the native REPL in native/repl/src. The
   *  JVM REPL's own code (JLine, its class loaders and bytecode
   *  instrumentation, coursier's Java API) is compiled against its libraries
   *  but must stay unreachable from `snc`'s main.
   */
  lazy val `scala3-repl-native` =
    Project("scala3-repl-native", file("native/repl"))
      .enablePlugins(ScalaNativePlugin)
      .dependsOn(`scala3-compiler-native`)
      .settings(
        scalaVersion := Versions.referenceVersion,
        scalacOptions ~= (_.filterNot(_ == "-Werror")),
        Compile / unmanagedSourceDirectories := {
          val root = (ThisBuild / baseDirectory).value
          Seq(
            root / "repl" / "src",
            root / "directives-parser" / "src" / "main" / "scala",
            baseDirectory.value / "src"
          )
        },
        Compile / unmanagedSources := withOverlay(
          (Compile / unmanagedSources).value,
          (ThisBuild / baseDirectory).value / "repl" / "src",
          baseDirectory.value / "src"),
        libraryDependencies ++= Seq(
          Dependencies.jlineReader,
          Dependencies.jlineTerminal,
          Dependencies.coursierInterface
        )
      )

  /** The library that REPL lines run with: `snc repl` compiles lines against
   *  its TASTy and links them with its NIR. It holds the pretty-printer and
   *  the runtime of repl/src (`$show`'s renderer, stack traces), and in
   *  native/repl-runtime/src the evaluator, the main of the evaluator host
   *  that `snc repl` links from this jar and the class path, and the relink
   *  backend's main. `snc`'s build copies its jar next to `snc` as
   *  `repl-runtime.jar`.
   */
  lazy val `scala3-repl-runtime-native` =
    Project("scala3-repl-runtime-native", file("native/repl-runtime"))
      .enablePlugins(ScalaNativePlugin)
      .settings(
        scalaVersion := Versions.referenceVersion,
        scalacOptions ~= (_.filterNot(o => o == "-Werror" || o == "-Yexplicit-nulls")),
        Compile / unmanagedSourceDirectories := {
          val repl = (ThisBuild / baseDirectory).value / "repl" / "src"
          Seq(
            repl / "pprint",
            repl / "fansi",
            repl / "sourcecode",
            repl / "dotty" / "tools" / "repl" / "runtime",
            baseDirectory.value / "src"
          )
        },
        exportJars := true
      )

  /** Replaces, among `sources`, each file under `base` by the file under
   *  `overlay` at the same path, if there is one; a Scala file can replace a
   *  Java one.
   */
  private def withOverlay(sources: Seq[File], base: File, overlay: File): Seq[File] = {
    def withoutExtension(path: String) = path.replaceAll("\\.(scala|java)$", "")
    val replaced = (overlay ** ("*.scala" || "*.java")).get
      .flatMap(IO.relativize(overlay, _))
      .map(withoutExtension)
      .toSet
    sources.filterNot { file =>
      IO.relativize(base, file).exists(p => replaced(withoutExtension(p)))
    }
  }

  /** `snc`: the native compiler with the Scala Native compiler plugin built in.
   *  `-Xplugin` paths to the plugin's jar select it, as on the JVM.
   */
  lazy val snc =
    Project("snc", file("native/snc"))
      .enablePlugins(ScalaNativePlugin)
      .dependsOn(`scala3-compiler-native`, `scala3-repl-native`)
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
        nativeConfig ~= nativeCompilerConfig,
        // snc finds repl-runtime.jar next to itself
        Compile / nativeLink := {
          val binary = (Compile / nativeLink).value
          IO.copyFile((`scala3-repl-runtime-native` / Compile / packageBin).value,
            binary.getParentFile / "repl-runtime.jar")
          binary
        }
      )

  /** `snc` linked as a live-execution host (README.md):
   *  the same compiler, with the boundary of native/snc/host/boundary.txt and
   *  the toolchain's extension linker built in, so that it links and loads
   *  code that it compiled (macro implementations) without another process.
   *  Its main is `SncHostMain`. The binary is
   *  `native/snc-host/target/scala-3.10.2-RC1/snc-host`, with its manifest
   *  (`snc-host.snm`) and the class path it was linked from
   *  (`snc-host.classpath`, which its extension links read) next to it.
   *
   *  The toolchain comes from scala-native's `tools_native0.5_3`; its `nir` and
   *  `util` are nscplugin's copies, which `snc` has already.
   */
  lazy val sncHost =
    Project("snc-host", file("native/snc-host"))
      .enablePlugins(ScalaNativePlugin)
      .dependsOn(snc)
      .settings(
        scalaVersion := Versions.referenceVersion,
        scalacOptions --= Seq("-Werror", "-Yexplicit-nulls", "-Wsafe-init"),
        Compile / unmanagedSourceDirectories := Seq(baseDirectory.value / "src"),
        libraryDependencies += ("org.scala-native" % "tools_native0.5_3" % scalaNativeVersion)
          .exclude("org.scala-native", "nir_native0.5_3")
          .exclude("org.scala-native", "util_native0.5_3"),
        Compile / mainClass := Some("dotty.tools.dotc.SncHostMain"),
        nativeConfig := {
          val boundary = sncHostBoundary((ThisBuild / baseDirectory).value / "native" / "snc" / "host" / "boundary.txt")
          nativeCompilerConfig(nativeConfig.value)
            .withLiveExecution(scala.scalanative.build.LiveExecution.Host(
              open = boundary._1, callable = boundary._2, stubUnlinkable = true))
        },
        Compile / nativeLink := {
          val binary = (Compile / nativeLink).value
          val dir = binary.getParentFile
          IO.copyFile((`scala3-repl-runtime-native` / Compile / packageBin).value, dir / "repl-runtime.jar")
          // The NIR the extension linker reads the host's classes from
          IO.writeLines(dir / (binary.getName + ".classpath"),
            (Compile / fullClasspath).value.files.map(_.getAbsolutePath).filterNot(_.endsWith("-sources.jar")))
          binary
        }
      )

  /** The open and callable patterns of a boundary file: lines `open <pattern>`
   *  and `callable <pattern>`, `#` comments.
   */
  def sncHostBoundary(file: File): (Seq[String], Seq[String]) = {
    val entries = IO.readLines(file).map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).map { line =>
      line.split("\\s+", 2) match {
        case Array(kind @ ("open" | "callable"), pattern) => kind -> pattern.trim
        case _ => sys.error(s"$file: bad line: $line")
      }
    }
    (entries.collect { case ("open", p) => p }, entries.collect { case ("callable", p) => p })
  }

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
