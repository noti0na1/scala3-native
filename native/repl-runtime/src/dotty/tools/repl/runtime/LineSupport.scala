package dotty.tools.repl.runtime

import scala.scalanative.posix.{fcntl, unistd}
import scala.scalanative.unsafe.*

/** What the two ways of running a REPL line share, the evaluator
 *  (`Evaluator`) and the main of a relinked program (`Runner`): the line's
 *  output and the frames its traces leave out.
 */
private[runtime] object LineSupport:

  /** Whether `frame` is the REPL's own code (a wrapper's initialization or
   *  `$show`, a runner, the evaluator or `Runner`); the trace ends before the
   *  first such frame.
   */
  def isRunnerFrame(frame: StackTraceElement): Boolean =
    val cls = frame.getClassName
    val method = frame.getMethodName
    (cls.startsWith("rs$line$") && (method == "<init>" || method == "<clinit>" || method == "$show"))
    || cls.startsWith("rs$runner$") || cls.startsWith("dotty.tools.repl.runtime.Evaluator")
    || cls.startsWith("dotty.tools.repl.runtime.Runner")

  def flush(): Unit =
    Console.out.flush()
    Console.err.flush()
    System.out.flush()
    System.err.flush()

  /** Runs `body` with file descriptors 1 and 2 on /dev/null. */
  def withoutOutput[T](body: => T): T =
    flush()
    val savedOut = unistd.dup(1)
    val savedErr = unistd.dup(2)
    val devNull = Zone.acquire(implicit z => fcntl.open(toCString("/dev/null"), fcntl.O_WRONLY))
    if devNull >= 0 then
      unistd.dup2(devNull, 1)
      unistd.dup2(devNull, 2)
      unistd.close(devNull)
    try body
    finally
      flush()
      unistd.dup2(savedOut, 1)
      unistd.dup2(savedErr, 2)
      unistd.close(savedOut)
      unistd.close(savedErr)
end LineSupport
