package dotty.tools
package repl

import dotc.core.Contexts.*
import dotty.tools.io.AbstractFile

/** What the native REPL's backends (`EvaluatorBackend`, `RelinkBackend`)
 *  share.
 */
trait NativeReplBackend extends ReplBackend:

  /** The first class of `jar` that the compiler's class path has already,
   *  as on the JVM, where its class loader would load it.
   */
  override def existingClassIn(jar: AbstractFile)(using Context): Option[String] =
    def classes(dir: AbstractFile, pkg: String): Iterator[(String, AbstractFile)] =
      dir.iterator.flatMap: f =>
        if f.isDirectory then classes(f, pkg + f.name + ".")
        else if f.name.endsWith(".class") then Iterator((pkg + f.name.stripSuffix(".class"), f))
        else Iterator.empty
    classes(jar, "")
      .find((className, _) => ctx.platform.classPath.findClassFile(className).isDefined)
      .map(_._2.toString)

object NativeReplBackend:

  /** A failed link of a line, as the backends show it: `heading`, then the
   *  errors of `snlink`'s log (or its last lines if it has none), and a hint
   *  when code the line uses is unreachable.
   */
  def linkError(heading: String, log: Seq[String]): String =
    val errors = log.filter(_.startsWith("[error]"))
      .map(_.stripPrefix("[error]").stripPrefix(" ").stripSuffix(", referenced from:"))
      .filter(_.trim.nonEmpty)
    val unreachable = errors.exists(_.startsWith("Found ")) && errors.exists(_.contains("unreachable"))
    val hint =
      if unreachable then
        List("(Code the line uses has no NIR or does not link: jars added with -classpath or :jar must be built for Scala Native.)")
      else Nil
    val shown = if errors.nonEmpty then errors.take(30) else log.takeRight(30)
    ((heading :: shown.toList) ::: hint).mkString(System.lineSeparator)
