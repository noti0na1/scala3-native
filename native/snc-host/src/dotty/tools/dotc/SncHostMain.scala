package dotty.tools.dotc

import java.nio.file.{Path, Paths}

import dotty.tools.dotc.live.ExtensionLinker

/** Main class of `snc` linked as a live-execution host: the same compiler as
 *  `SncMain`, linked with the boundary of native/snc/host/boundary.txt and
 *  with the extension linker built in, which it installs as `ExtensionLinker`.
 *  It runs macros by linking and loading their code (`MacroExtensions`).
 *  Hidden options exercise the linker from the shell
 *  (README.md):
 *
 *  - `snc --link-extension <work dir> [--name <name>] [--debug]
 *    [--classpath <path>] <NIR directory or jar>...` links an extension
 *    against this `snc` and prints the bundle's path;
 *  - `snc --load-extension <bundle> <module> [<compiler arguments>]` loads a
 *    bundle into this `snc` before compiling, and runs the module if it is a
 *    `Function0` (repeatable);
 *  - `snc --host-info` prints the manifest, the binary and the class path it
 *    links extensions with.
 */
object SncHostMain:
  def main(args: Array[String]): Unit =
    HostExtensionLinker.find().foreach { host =>
      val linker = new HostExtensionLinker(host)
      ExtensionLinker.install(linker)
      // Macro code from the class path and from earlier runs is linked and
      // loaded when an expansion first needs it (MacroExtensions)
      if !sys.env.get("SNC_MACRO_LOADER").contains("off") then
        quoted.MacroRegistry.installLoader(new MacroExtensions(host, linker))
    }
    args.toList match
      case "--link-extension" :: rest => sys.exit(linkExtension(rest))
      case "--host-info" :: Nil => sys.exit(hostInfo())
      case rest =>
        loadExtensions(rest) match
          case Right(compilerArgs) => SncMain.main(compilerArgs.toArray)
          case Left(code) => sys.exit(code)

  private def linker(): Either[Int, ExtensionLinker] =
    ExtensionLinker.get.toRight {
      Console.err.println(s"snc: ${HostExtensionLinker.notAHost}")
      2
    }

  private def hostInfo(): Int =
    HostExtensionLinker.find() match
      case Some(host) =>
        println(s"manifest ${host.manifest}")
        println(s"binary ${host.binary}")
        host.classPath.foreach(p => println(s"classpath $p"))
        0
      case None =>
        Console.err.println(s"snc: ${HostExtensionLinker.notAHost}")
        2

  private def linkExtension(args: List[String]): Int =
    def usage(msg: String) =
      Console.err.println(s"snc --link-extension: $msg")
      Console.err.println("usage: snc --link-extension <work dir> [--name <name>] [--debug] [--classpath <path>] <NIR directory or jar>...")
      2
    def parse(args: List[String], name: String, debug: Boolean, cp: Seq[Path], roots: Seq[Path]): Either[String, (String, Boolean, Seq[Path], Seq[Path])] =
      args match
        case "--name" :: n :: rest => parse(rest, n, debug, cp, roots)
        case "--debug" :: rest => parse(rest, name, true, cp, roots)
        case "--classpath" :: p :: rest =>
          parse(rest, name, debug, cp ++ p.split(java.io.File.pathSeparatorChar).filter(_.nonEmpty).map(Paths.get(_)), roots)
        case opt :: _ if opt.startsWith("--") => Left(s"unknown option $opt")
        case root :: rest => parse(rest, name, debug, cp, roots :+ Paths.get(root))
        case Nil => Right((name, debug, cp, roots))
    args match
      case workDir :: rest =>
        parse(rest, "extension", false, Nil, Nil) match
          case Left(msg) => usage(msg)
          case Right((_, _, _, roots)) if roots.isEmpty => usage("no NIR directory or jar")
          case Right((name, debug, cp, roots)) =>
            linker() match
              case Left(code) => code
              case Right(linker) =>
                val start = System.nanoTime()
                try
                  val bundle = linker.link(roots, Paths.get(workDir), name,
                    ExtensionLinker.Options(classPath = cp, debug = debug,
                      log = msg => if sys.env.contains("SNC_LINK_VERBOSE") then Console.err.println(msg)))
                  Console.err.println(f"snc: linked $bundle in ${(System.nanoTime() - start) / 1e6}%.0f ms")
                  println(bundle)
                  0
                catch case e: ExtensionLinker.LinkError =>
                  Console.err.println(s"snc: the extension does not link: ${e.getMessage}")
                  1
      case Nil => usage("no work directory")

  /** Loads the extensions that leading `--load-extension` options name; the
   *  compiler's arguments that follow, or an exit code.
   */
  private def loadExtensions(args: List[String]): Either[Int, List[String]] =
    args match
      case "--load-extension" :: bundle :: module :: rest =>
        linker().flatMap { linker =>
          try
            val start = System.nanoTime()
            val ext = linker.load(Paths.get(bundle))
            val loadedAt = System.nanoTime()
            ext.module(module) match
              case run: Function0[?] => run()
              case _ => ()
            if sys.env.contains("SNC_LOAD_TIMINGS") then
              Console.err.println(f"snc: loaded $bundle in ${(loadedAt - start) / 1e6}%.1f ms")
            loadExtensions(rest)
          catch case e: ExtensionLinker.LoadError =>
            Console.err.println(s"snc: cannot load $bundle: ${e.getMessage}")
            Left(2)
        }
      case "--load-extension" :: _ =>
        Console.err.println("usage: snc --load-extension <bundle> <module> [<compiler arguments>]")
        Left(2)
      case rest => Right(rest)
