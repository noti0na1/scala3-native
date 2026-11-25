package dotty.tools.repl.runtime

import scala.util.control.ControlThrowable

/** Thrown in REPL code when the user interrupts a line (Ctrl-C), where the
 *  JVM REPL throws `ThreadDeath`. Like `ThreadDeath`, `NonFatal` does not
 *  catch it. */
final class ReplInterruption extends ControlThrowable

/** The interrupt handler of REPL lines: their code is linked with polls of the
 *  evaluator's interrupt flag at method entries and loop back-edges
 *  (`snlink --interrupt-handler dotty.tools.repl.runtime.Interrupt$.check`),
 *  which call it while the flag is set.
 */
object Interrupt:
  def check(): Unit = throw new ReplInterruption
