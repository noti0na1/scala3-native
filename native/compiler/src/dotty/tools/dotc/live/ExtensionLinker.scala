package dotty.tools.dotc
package live

import java.nio.file.Path

/** Links code as an extension of the running compiler and loads it, without
 *  another process: the internal API for running compiled code inside `snc`.
 *
 *  A compiler that can do it is linked as a live-execution host: its manifest
 *  (`<executable>.snm`) says which classes and traits extensions may subclass
 *  and call (native/snc/host/boundary.txt). The macro executor
 *  (`MacroExtensions` in snc-host) links macro code with it, and the REPL
 *  sends it the link requests of its lines (`request`). The host's main
 *  (`SncHostMain`) installs an implementation; a compiler that is not a host,
 *  such as the plain `snc`, has none.
 *
 *  Extensions are never unloaded, and a class may be defined by one loaded
 *  extension only.
 */
trait ExtensionLinker:

  /** Links the classes of `roots` (directories or jars of NIR) into a bundle
   *  in `workDir`, against this compiler's manifest; the bundle's path.
   *
   *  @throws ExtensionLinker.LinkError
   *    if they do not link: they subclass a type the host does not declare
   *    open, need what the host lacks, or clang fails
   */
  def link(roots: Seq[Path], workDir: Path, name: String, options: ExtensionLinker.Options = ExtensionLinker.Options()): Path

  /** Loads a bundle that `link` wrote into this process, or returns the one
   *  loaded from that path before.
   *
   *  @throws ExtensionLinker.LoadError
   *    if it was linked for another host, or defines a class another
   *    extension defines already
   */
  def load(bundle: Path): ExtensionLinker.Loaded

  /** Links as `snlink` with the arguments of an extension link would
   *  (`--extension --host <manifest> --ext <entry> ... <class path>`), in this
   *  process, against any host, such as the REPL's evaluator: the bundle, or
   *  none, and the link's log, timings included. The REPL's
   *  `EvaluatorBackend` links its lines with it.
   */
  def request(args: Seq[String]): ExtensionLinker.Response

  /** Reads what a link request needs before it is made, one step per element
   *  (the manifests, each jar of the class path, a link of the request), so
   *  that a single-threaded caller can take the steps while it waits for its
   *  user. A step reads one input, so all but the last are short next to a
   *  link; how short depends on the input's size and the machine. Each
   *  element names its step.
   */
  def prepare(args: Seq[String]): Iterator[String] = Iterator.empty

end ExtensionLinker

object ExtensionLinker:

  /** How to link.
   *
   *  @param classPath
   *    NIR that the extension's classes need besides the host's
   *  @param debug
   *    debug mode instead of the host's mode (no optimizer)
   *  @param log
   *    where the linker's messages go; dropped by default
   */
  final case class Options(
      classPath: Seq[Path] = Nil,
      debug: Boolean = false,
      log: String => Unit = _ => ())

  /** A loaded extension. */
  trait Loaded:
    /** The bundle's path. */
    def path: Path

    /** The instance of a module the extension defines, by its binary name
     *  (`pkg.Entry$`), initialized on first access.
     */
    def module(name: String): AnyRef

  /** What a link request gives: the bundle, or none, and the log. */
  final case class Response(artifact: Option[Path], log: Seq[String])

  final class LinkError(message: String, cause: Throwable | Null = null) extends Exception(message, cause)
  final class LoadError(message: String) extends Exception(message)

  @volatile private var installed: ExtensionLinker | Null = null

  /** Makes `linker` this process's extension linker. */
  def install(linker: ExtensionLinker): Unit = installed = linker

  /** This process's extension linker, if it is a host. */
  def get: Option[ExtensionLinker] = Option(installed)

end ExtensionLinker
