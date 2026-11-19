package dotty.tools.io

/** Opens a zip archive whose name does not end in `.jar` or `.zip`, such as a
 *  JDK's `lib/ct.sym`, as an abstract directory (the native compiler's JDK
 *  reader, `classpath.JdkClassPath`).
 */
object JdkArchive:
  def open(path: java.nio.file.Path): AbstractFile = new FileZipArchive(path, "")
