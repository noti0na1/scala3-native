package dotty.tools.dotc.classpath

import java.io.{ByteArrayInputStream, InputStream, RandomAccessFile}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import dotty.tools.io.{AbstractFile, JdkArchive, PlainFile, VirtualFile}
import PackageNameUtils.{packageContains, separatePkgAndClassNames}

/** The Java platform classes that the native compiler compiles
 *  against, read from a JDK installation without running it.
 *
 *  - A JDK of feature version V has the API of each release N before V in
 *    `lib/ct.sym` (JEP 247), a zip archive of class files (`.sig`) under
 *    directories named by the releases they belong to (`G` for 16, `H` for
 *    17, `9ABCDEFGH` for 9 to 17), then by module and package; the JVM
 *    compiler's `CtSymClassPath` reads the same layout. Since JDK 22 or so,
 *    `ct.sym` also has release V itself.
 *  - V's own classes, which the JVM compiler reads from the running JDK's
 *    runtime image (`jrt:/`) without `-release`, are in `lib/modules`, a
 *    jimage, and in `jmods/`: a jmod is a zip archive after a 4-byte header,
 *    with the module's classes under `classes/`. JDKs up to 23 ship `jmods/`;
 *    later ones may not (JEP 493).
 *
 *  For `-release N` with N < V this reads `ct.sym`, as the JVM compiler does;
 *  for N = V (also without `-release`), `jmods/` when the JDK has it, which
 *  are the classes of the runtime image, or else `ct.sym` when it has V,
 *  which are V's exported API only. `lib/modules` is not read: a JDK with
 *  neither is an error that says so.
 */
object JdkClassPath:

  /** A JDK installation and its feature version (17 for 17.0.16). */
  final case class Jdk(home: Path, feature: Int)

  /** The JDK at `home`, from its `release` file. */
  def jdk(home: Path): Either[String, Jdk] =
    val releaseFile = home.resolve("release")
    if !Files.isRegularFile(releaseFile) then Left(s"$home is not a JDK: it has no release file")
    else
      val version = Files.readAllLines(releaseFile, StandardCharsets.UTF_8).asScala
        .collectFirst { case line if line.startsWith("JAVA_VERSION=") =>
          line.stripPrefix("JAVA_VERSION=").stripPrefix("\"").stripSuffix("\"")
        }
      version match
        case None => Left(s"$releaseFile has no JAVA_VERSION")
        case Some(v) =>
          // 1.8.0_402 is 8; 17.0.16 is 17; 25 is 25
          val parts = v.split("[._+-]").toList
          val feature = parts match
            case "1" :: minor :: _ => minor.toIntOption
            case major :: _ => major.toIntOption
            case Nil => None
          feature match
            case Some(f) => Right(Jdk(home, f))
            case None => Left(s"$releaseFile: cannot read the Java version $v")

  /** The class path of `jdk`'s API for `release`, by default the JDK's own. */
  def apply(jdk: Jdk, release: Option[Int]): Either[String, ClassPath] =
    val n = release.getOrElse(jdk.feature)
    val ctSym = jdk.home.resolve("lib").resolve("ct.sym")
    val jmodsDir = jdk.home.resolve("jmods")
    lazy val jmods =
      val stream = Files.list(jmodsDir)
      try stream.iterator.asScala.filter(_.toString.endsWith(".jmod")).toList.sorted
      finally stream.close()
    if n > jdk.feature then Left(s"release $n is newer than the JDK at ${jdk.home} (${jdk.feature})")
    else if n == jdk.feature && Files.isDirectory(jmodsDir) && jmods.nonEmpty then Right(new JmodsClassPath(jmods))
    else if !Files.isRegularFile(ctSym) then Left(s"the JDK at ${jdk.home} has no lib/ct.sym")
    else
      val cp = new CtSymArchiveClassPath(ctSym, n)
      if cp.isEmpty then
        Left(s"the JDK at ${jdk.home} has no jmods/, and its lib/ct.sym has no classes for release $n: " +
          s"snc does not read the runtime image (lib/modules) yet; use a JDK with jmods/, " +
          s"or a newer JDK (--java-home) with -release $n")
      else Right(cp)

  /** The letter or digit of release `n` in `ct.sym`'s directory names. */
  private def codeFor(n: Int): String = if n < 10 then n.toString else ('A' + (n - 10)).toChar.toString

  /** `ct.sym`'s classes for `release`, as `CtSymClassPath` reads them on a JVM. */
  final class CtSymArchiveClassPath(ctSym: Path, release: Int) extends ClassPath:
    private val archive = JdkArchive.open(ctSym)
    private val code = codeFor(release)

    /** Package name to its directories, one per module and release group. */
    private val packageIndex: collection.Map[String, List[AbstractFile]] =
      val index = mutable.HashMap.empty[String, List[AbstractFile]]
      def walk(dir: AbstractFile, pkg: String): Unit =
        for child <- dir.iterator if child.isDirectory do
          val name = if pkg.isEmpty then child.name else s"$pkg.${child.name}"
          index(name) = child :: index.getOrElse(name, Nil)
          walk(child, name)
      for
        root <- archive.iterator
        if root.isDirectory && !root.name.contains("-") && root.name.contains(code)
        module <- root.iterator
        if module.isDirectory
      do walk(module, "")
      index

    def isEmpty: Boolean = packageIndex.isEmpty

    override def searchLocations: Iterable[AbstractFile] =
      Iterable.empty // as for the JVM compiler's CtSymClassPath

    override def hasPackage(pkg: String): Boolean = pkg.isEmpty || packageIndex.contains(pkg)

    override def packages(inPackage: String): Iterable[String] =
      packageIndex.keys.filter(packageContains(inPackage, _))

    override def classes(inPackage: String): Iterable[BinaryFileEntry] =
      if inPackage.isEmpty then Nil
      else packageIndex.getOrElse(inPackage, Nil).flatMap(dir =>
        dir.iterator.filter(f => !f.isDirectory && f.name.endsWith(".sig")).map(BinaryFileEntry(_)))

    override def findClassFile(className: String): Option[AbstractFile] =
      if !className.contains(".") then None
      else
        val (inPackage, simpleName) = separatePkgAndClassNames(className)
        packageIndex.getOrElse(inPackage, Nil).iterator
          .flatMap(dir => Option(dir.lookupName(simpleName + ".sig", directory = false)))
          .nextOption()

  /** The classes of a JDK's `jmods/`: those of its runtime image. */
  final class JmodsClassPath(jmods: List[Path]) extends ClassPath:
    /** Package name to its class files by simple name, from all modules. */
    private val packageIndex: collection.Map[String, mutable.LinkedHashMap[String, Jmod#Entry]] =
      val index = mutable.HashMap.empty[String, mutable.LinkedHashMap[String, Jmod#Entry]]
      for jmod <- jmods.map(new Jmod(_)); entry <- jmod.entries do
        val name = entry.path.stripPrefix("classes/")
        val slash = name.lastIndexOf('/')
        if slash > 0 && name.endsWith(".class") then
          val pkg = name.substring(0, slash).replace('/', '.')
          index.getOrElseUpdate(pkg, mutable.LinkedHashMap.empty)(name.substring(slash + 1)) = entry
      // Packages without classes of their own, such as `java`
      for pkg <- index.keys.toList; i <- pkg.indices if pkg(i) == '.' do
        index.getOrElseUpdate(pkg.substring(0, i), mutable.LinkedHashMap.empty)
      index

    override def searchLocations: Iterable[AbstractFile] =
      Iterable.empty // as for the JVM compiler's JrtClassPath

    override def hasPackage(pkg: String): Boolean = pkg.isEmpty || packageIndex.contains(pkg)

    override def packages(inPackage: String): Iterable[String] =
      packageIndex.keys.filter(packageContains(inPackage, _))

    override def classes(inPackage: String): Iterable[BinaryFileEntry] =
      packageIndex.get(inPackage).map(_.values.map(BinaryFileEntry(_))).getOrElse(Nil)

    override def findClassFile(className: String): Option[AbstractFile] =
      val (inPackage, simpleName) = separatePkgAndClassNames(className)
      packageIndex.get(inPackage).flatMap(_.get(simpleName + ".class"))

  /** A jmod's `classes/` entries, read from its zip central directory
   *  (PKWARE's APPNOTE.TXT), whose offsets do not count the 4-byte header
   *  before the zip data. Stored and deflated entries; no ZIP64. The file is
   *  open only while it is read, so that a long-lived compiler keeps no
   *  descriptor per jmod.
   */
  final class Jmod(jmodFile: Path):
    private def reading[T](body: RandomAccessFile => T): T =
      val raf = new RandomAccessFile(jmodFile.toFile, "r")
      try body(raf) finally raf.close()

    /** The jmod as the archive its classes come from, as a jar is for its
     *  classes: what the sbt phases report a dependency on.
     */
    private lazy val archive: AbstractFile = new PlainFile(dotty.tools.io.Path(jmodFile))

    /** A class file in the jmod, read and inflated when its content is asked for. */
    final class Entry(path: String, method: Int, compressedSize: Int, size: Int, localHeader: Long)
        extends VirtualFile(path, Array.emptyByteArray):
      override def input: InputStream = new ByteArrayInputStream(read())
      override def toString: String = s"$jmodFile($path)"
      override def enclosing: Option[AbstractFile] = Some(archive)

      private def read(): Array[Byte] = {
        val data = reading: raf =>
          val header = new Array[Byte](30)
          raf.seek(localHeader + delta)
          raf.readFully(header)
          if u32(header, 0) != 0x04034b50L then throw new java.io.IOException(s"$this: bad local header")
          raf.seek(localHeader + delta + 30 + u16(header, 26) + u16(header, 28))
          val data = new Array[Byte](compressedSize)
          raf.readFully(data)
          data
        method match
          case 0 => data
          case 8 =>
            val inflater = new java.util.zip.Inflater(true)
            val out = new Array[Byte](size)
            try
              inflater.setInput(data)
              var n = 0
              while n < size && !inflater.finished() do
                val k = inflater.inflate(out, n, size - n)
                if k == 0 && (inflater.needsInput() || inflater.needsDictionary()) then
                  throw new java.io.IOException(s"$this: truncated deflated data")
                n += k
              out
            finally inflater.end()
          case m => throw new java.io.IOException(s"$this: compression method $m is not supported")
      }

    /** The offset of the zip data in the file: the end of the central directory
     *  minus its size and offset.
     */
    private var delta = 0L

    /** The `classes/` entries. */
    lazy val entries: List[Entry] = reading: raf =>
      val length = raf.length()
      val tailSize = math.min(length, 22L + 65535L).toInt
      val tail = new Array[Byte](tailSize)
      raf.seek(length - tailSize)
      raf.readFully(tail)
      var eocd = tailSize - 22
      while eocd >= 0 && u32(tail, eocd) != 0x06054b50L do eocd -= 1
      if eocd < 0 then throw new java.io.IOException(s"$jmodFile: not a zip archive (no end of central directory)")
      val count = u16(tail, eocd + 10)
      val cdSize = u32(tail, eocd + 12)
      val cdOffset = u32(tail, eocd + 16)
      if count == 0xffff || cdSize == 0xffffffffL || cdOffset == 0xffffffffL then
        throw new java.io.IOException(s"$jmodFile: ZIP64 archives are not supported")
      delta = (length - tailSize + eocd) - (cdOffset + cdSize)
      val cd = new Array[Byte](cdSize.toInt)
      raf.seek(cdOffset + delta)
      raf.readFully(cd)
      val result = List.newBuilder[Entry]
      var p = 0
      for _ <- 0 until count do
        if u32(cd, p) != 0x02014b50L then throw new java.io.IOException(s"$jmodFile: bad central directory")
        val method = u16(cd, p + 10)
        val compressedSize = u32(cd, p + 20).toInt
        val size = u32(cd, p + 24).toInt
        val nameLength = u16(cd, p + 28)
        val extraLength = u16(cd, p + 30)
        val commentLength = u16(cd, p + 32)
        val localHeader = u32(cd, p + 42)
        val name = new String(cd, p + 46, nameLength, java.nio.charset.StandardCharsets.UTF_8)
        if name.startsWith("classes/") && name.endsWith(".class") then
          result += new Entry(name, method, compressedSize, size, localHeader)
        p += 46 + nameLength + extraLength + commentLength
      result.result()

    private def u16(b: Array[Byte], i: Int): Int = (b(i) & 0xff) | ((b(i + 1) & 0xff) << 8)
    private def u32(b: Array[Byte], i: Int): Long = (u16(b, i) | (u16(b, i + 2).toLong << 16)) & 0xffffffffL
end JdkClassPath
