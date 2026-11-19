package dotty.tools.dotc.interfaces

// Scala version of the Java interface in interfaces/src, for the Scala Native
// build of the compiler, which cannot compile Java sources. Keep the two in sync.

trait DiagnosticRelatedInformation:
  def position: SourcePosition
  def message: String
