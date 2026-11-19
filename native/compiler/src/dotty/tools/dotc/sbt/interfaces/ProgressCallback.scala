package dotty.tools.dotc.sbt.interfaces

import dotty.tools.dotc.CompilationUnit

/** Scala version of `ProgressCallback.java` for the Scala Native build of the
 *  compiler, which cannot compile Java sources. Keep the two in sync.
 */
trait ProgressCallback:
  def cancel(): Unit = ()
  def isCancelled(): Boolean = false
  def informUnitStarting(phase: String | Null, unit: CompilationUnit | Null): Unit = ()
  def progress(current: Int, total: Int, currPhase: String | Null, nextPhase: String | Null): Boolean = true
