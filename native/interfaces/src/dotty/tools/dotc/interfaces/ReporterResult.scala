package dotty.tools.dotc.interfaces

// Scala version of the Java interface in interfaces/src, for the Scala Native
// build of the compiler, which cannot compile Java sources. Keep the two in sync.

trait ReporterResult:
  def hasErrors: Boolean
  def errorCount: Int
  def hasWarnings: Boolean
  def warningCount: Int
