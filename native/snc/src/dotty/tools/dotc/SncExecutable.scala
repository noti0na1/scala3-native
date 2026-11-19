package dotty.tools.dotc

import java.nio.file.{Path, Paths}

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.util.Try

/** Where the running `snc` executable is. */
object SncExecutable:

  /** The directory of the running executable, with symbolic links resolved. */
  def executableDir(): Option[Path] =
    executablePath().flatMap(p => Try(Paths.get(p).toRealPath().getParent).toOption).flatMap(Option(_))

  /** The path of the running executable, as the system gives it. */
  def executablePath(): Option[String] =
    if LinktimeInfo.isMac then
      val size = stackalloc[CUnsignedInt]()
      !size = 4096.toUInt
      val buf = stackalloc[CChar](4096)
      if Dyld._NSGetExecutablePath(buf, size) == 0 then Some(fromCString(buf)) else None
    else if LinktimeInfo.isLinux || LinktimeInfo.isFreeBSD then
      Try(java.nio.file.Files.readSymbolicLink(Paths.get("/proc/self/exe")).toString).toOption
    else None

  @extern
  private object Dyld:
    def _NSGetExecutablePath(buf: CString, bufsize: Ptr[CUnsignedInt]): CInt = extern
end SncExecutable
