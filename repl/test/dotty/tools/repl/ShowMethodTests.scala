package dotty.tools
package repl

import java.io.File

import org.junit.Test

import dotc.core.Contexts.*

/** The JVM backend, but values are rendered by the generated `$show` methods
 *  (`ReplShow`), as backends without reflection render them.
 */
class ShowMethodBackend(rendering: Rendering) extends JvmReplBackend(rendering):
  override def newCompiler(): ReplCompiler = new ReplCompiler(generateShow = true)

  override def run(line: ReplLine)(using Context): ReplResult =
    if line.values.isEmpty then super.run(line)
    else
      try
        val wrapper = Class.forName(line.wrapper.fullName.encode.toString, true, rendering.classLoader())
        val module = wrapper.getField("MODULE$").get(null)
        val show = wrapper.getMethod(ReplShow.name.toString, classOf[Int], classOf[Int], classOf[Boolean], classOf[Array[Int]])
        val texts = show.invoke(module,
          ctx.settings.pageWidth.value, ctx.settings.XreplPrintHeight.value,
          ctx.settings.color.value != "never", ReplShow.offsets(line))
        ReplShow.results(line, texts.asInstanceOf[Array[String | Null]].toSeq)
      catch case e: ReflectiveOperationException =>
        ReplResult.Failed(rendering.errorText(e))

/** The scripted tests with values rendered by `$show`. */
final class ShowMethodScriptedTests extends ReplTest:
  override protected def newBackend(): ReplBackend = new ShowMethodBackend(rendering)

  private def scripts(path: String): Array[File] =
    val dir = new File(path)
    assert(dir.exists && dir.isDirectory, "Couldn't load scripts dir")
    dir.listFiles.filter: file =>
      val path = if file.isDirectory then file.getPath + "/" else file.getPath
      dotty.Properties.testsFilter.isEmpty || dotty.Properties.testsFilter.exists(path.contains)

  @Test def replTests = testFiles(scripts("test-resources/repl"))
