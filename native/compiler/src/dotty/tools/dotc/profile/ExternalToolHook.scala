package dotty.tools.dotc.profile

/** Scala version of `ExternalToolHook.java` for the Scala Native build of the
 *  compiler, which cannot compile Java sources. Keep the two in sync.
 */
object ExternalToolHook:
  def before(): Unit = ()
  def after(): Unit = ()
