package dotty.tools.dotc.util

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, Paths, StandardCopyOption}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Files and caches of the native compiler's tools (`snc`,
 *  `snc-host`): deleting trees, the content hashes and keys of cache entries,
 *  and putting entries in place in a cache that concurrent processes share.
 *
 *  A cache entry is a directory that is complete when it holds `done`. It is
 *  written in a directory of its own under the cache's `tmp/` and renamed
 *  into place in one step (`publish`): the first rename wins, and the others
 *  use the entry it put in place.
 */
object NativeFiles:

  /** Deletes `path` and everything under it, without following symbolic
   *  links; what cannot be deleted stays.
   */
  def deleteRecursively(path: Path): Unit =
    try
      if Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) then
        val children = Files.list(path)
        try children.iterator.asScala.toList.foreach(deleteRecursively)
        finally children.close()
      Files.deleteIfExists(path)
    catch case NonFatal(_) => ()

  def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  /** A cache key: the MD5 of `ingredients`, one per line, in hexadecimal. */
  def key(ingredients: Seq[String]): String = hex(Md5.digest(ingredients.mkString("\n").getBytes(UTF_8)))

  /** `$XDG_CACHE_HOME/snc/<name>`, or `~/.cache/snc/<name>`; none without
   *  either variable.
   */
  def cacheDir(name: String): Option[Path] =
    sys.env.get("XDG_CACHE_HOME").filter(_.nonEmpty).map(Paths.get(_))
      .orElse(sys.env.get("HOME").filter(_.nonEmpty).map(Paths.get(_, ".cache")))
      .map(_.resolve("snc").resolve(name))

  /** The clang that links, as `LLVM_BIN` and the `PATH` find it, for cache
   *  keys.
   */
  def toolchainId: String =
    sys.env.getOrElse("LLVM_BIN", "") + " " + sys.env.getOrElse("PATH", "").split(File.pathSeparatorChar)
      .map(Paths.get(_).resolve("clang")).find(Files.isExecutable(_)).fold("")(_.toString)

  /** The `.nir` files of a jar or directory, as relative paths with `/`. */
  def nirFiles(root: Path): Set[String] =
    if Files.isDirectory(root) then
      val stream = Files.walk(root)
      try
        stream.iterator.asScala.filter(p => p.toString.endsWith(".nir") && Files.isRegularFile(p))
          .map(p => root.relativize(p).toString.replace(File.separatorChar, '/')).toSet
      finally stream.close()
    else
      val zip = new java.util.zip.ZipFile(root.toFile)
      try zip.entries.asScala.map(_.getName).filter(_.endsWith(".nir")).toSet
      finally zip.close()

  /** The content hash of a directory's NIR files: their relative paths and
   *  contents, in path order.
   */
  def nirDirectoryHash(root: Path): String =
    val out = new java.io.ByteArrayOutputStream
    for f <- nirFiles(root).toSeq.sorted do
      out.write(f.getBytes(UTF_8))
      out.write(0)
      out.write(Files.readAllBytes(root.resolve(f)))
    hex(Md5.digest(out.toByteArray))

  /** A new directory under `<cache>/tmp` for an entry being written. */
  def newWorkDir(cache: Path, prefix: String): Path =
    Files.createTempDirectory(Files.createDirectories(cache.resolve("tmp")), prefix)

  /** Whether the cache entry `dir` is complete. */
  def isComplete(dir: Path): Boolean = Files.isRegularFile(dir.resolve("done"))

  /** Marks the finished entry `work` complete and renames it to `target`. If
   *  another process put a complete `target` in place first, `work` is
   *  deleted and `target` stays; otherwise a failed rename throws.
   */
  def publish(work: Path, target: Path): Unit =
    Files.write(work.resolve("done"), Array.emptyByteArray)
    try Files.move(work, target, StandardCopyOption.ATOMIC_MOVE)
    catch case NonFatal(e) =>
      if isComplete(target) then deleteRecursively(work)
      else throw e

  /** The content hashes of class path entries: of a jar's bytes, remembered
   *  by its path, size and modification time, in memory and, with `sums`, in
   *  that directory for later processes; of a directory's NIR files
   *  (`nirDirectoryHash`), computed each time, as directories change between
   *  runs; `absent` for an entry that does not exist.
   */
  final class ContentHashes(sums: Option[Path]):
    private val known = mutable.HashMap.empty[(Path, Long, Long), String]

    def apply(path: Path): String =
      if Files.isDirectory(path) then nirDirectoryHash(path)
      else if !Files.isRegularFile(path) then "absent"
      else
        val size = Files.size(path)
        val time = Files.getLastModifiedTime(path).toMillis
        known.getOrElseUpdate((path, size, time), {
          val sum = sums.map(_.resolve(hex(Md5.digest(path.toString.getBytes(UTF_8)))))
          val stored = sum.flatMap: file =>
            try
              new String(Files.readAllBytes(file), UTF_8).trim.split(' ') match
                case Array(s, t, h) if s.toLong == size && t.toLong == time => Some(h)
                case _ => None
            catch case NonFatal(_) => None
          stored.getOrElse {
            val h = hex(Md5.digest(Files.readAllBytes(path)))
            sum.foreach(writeAtomically(_, s"$size $time $h\n"))
            h
          }
        })

    private def writeAtomically(file: Path, content: String): Unit =
      try
        Files.createDirectories(file.getParent)
        val tmp = Files.createTempFile(file.getParent, file.getFileName.toString, ".tmp")
        Files.write(tmp, content.getBytes(UTF_8))
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      catch case NonFatal(_) => ()
  end ContentHashes

end NativeFiles
