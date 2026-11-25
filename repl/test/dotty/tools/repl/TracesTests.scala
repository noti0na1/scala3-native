package dotty.tools
package repl

import org.junit.Assert.assertEquals
import org.junit.Test

import StackTraceOps.*
import runtime.Traces

/** `Traces.render`, which renders exceptions where the native REPL's lines
 *  run, gives what the JVM REPL gives (`formatStackTracePrefix`), for an
 *  exception with causes and suppressed exceptions thrown from nested calls.
 */
class TracesTests:
  private def inner(): Nothing = throw new IllegalStateException("inner")

  private def middle(): Nothing =
    try inner()
    catch case e: Exception => throw new RuntimeException("middle", e)

  private def suppressed(depth: Int): Exception =
    if depth == 0 then new IllegalArgumentException("suppressed") else suppressed(depth - 1)

  private def outer(): Nothing =
    try middle()
    catch case e: Exception =>
      val t = new Exception("outer", e)
      t.addSuppressed(suppressed(2))
      e.addSuppressed(new UnsupportedOperationException("suppressed of the cause", new ArithmeticException("its cause")))
      e.getCause.addSuppressed(suppressed(1))
      throw t

  private def thrown(): Throwable =
    try outer()
    catch case t: Exception => t

  @Test def causesAndSuppressedAsTheJvmRepl(): Unit =
    val t = thrown()
    assertEquals(t.formatStackTracePrefix(_ => true).plainText, Traces.render(t, _ => false, color = false))

  @Test def framesUpToTheStopAsTheJvmRepl(): Unit =
    val t = thrown()
    def stop(frame: StackTraceElement) = frame.getMethodName == "thrown"
    assertEquals(t.formatStackTracePrefix(!stop(_)).plainText, Traces.render(t, stop, color = false))
