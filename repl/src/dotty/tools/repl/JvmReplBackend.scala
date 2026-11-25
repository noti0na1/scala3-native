package dotty.tools
package repl

import dotc.core.Contexts.*
import dotty.tools.io.AbstractFile
import dotty.tools.io.PlainFile.toPlainFile
import dotty.tools.repl.ScalaClassLoader.*

import org.objectweb.asm.ClassReader

/** The JVM backend: lines run in class loaders of the compiler's process
 *  (`Rendering.classLoader`), and their values are read and rendered
 *  through reflection.
 */
class JvmReplBackend(rendering: Rendering) extends ReplBackend:

  def newCompiler(): ReplCompiler = new ReplCompiler

  def defaultOutputDir(): AbstractFile = dotty.tools.io.virtualDirectory("<REPL compilation output>")

  def run(line: ReplLine)(using Context): ReplResult =
    try
      if line.values.isEmpty then
        rendering.forceModule(line.wrapper) match
          case None => ReplResult.Values(Nil)
          case Some(error) => ReplResult.Failed(error)
      else
        // The first value initializes the wrapper
        ReplResult.Values(line.values.map(v => rendering.valueOf(v.symbol, v.prefixLength)))
    catch case e: ReflectiveOperationException =>
      ReplResult.Failed(rendering.errorText(e))

  override def withRunContext[T](body: => T)(using Context): T =
    rendering.classLoader().asContext(body)

  override def setInterrupted(interrupted: Boolean)(using Context): Unit =
    ReplBytecodeInstrumentation.setStopFlag(rendering.classLoader(), interrupted)

  def addToClasspath(entries: Seq[java.nio.file.Path])(using Context): Unit =
    rendering.addToClasspath(entries.map(_.toPlainFile))

  override def existingClassIn(jar: AbstractFile)(using Context): Option[String] =
    def flatten(f: AbstractFile): Iterator[AbstractFile] =
      if (f.isDirectory || f.ext.isJarOrZip) f.iterator.flatMap(flatten)
      else Iterator(f)

    def tryClassLoad(classFile: AbstractFile): Option[String] = {
      val input = classFile.input
      try {
        val reader = new ClassReader(input)
        val clsName = reader.getClassName().replace('/', '.')
        rendering.myClassLoader.loadClass(clsName)
        Some(clsName)
      } catch
        case _: ClassNotFoundException => None
      finally {
        input.close()
      }
    }

    flatten(jar).filter(_.ext.isClass).find(tryClassLoad(_).isDefined).map(_.toString)
end JvmReplBackend
