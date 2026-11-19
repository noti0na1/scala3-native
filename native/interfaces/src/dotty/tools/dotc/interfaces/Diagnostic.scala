package dotty.tools.dotc.interfaces

// Scala version of the Java interface in interfaces/src, for the Scala Native
// build of the compiler, which cannot compile Java sources. Keep the two in sync.

import java.util.{List => JList, Optional}

trait Diagnostic:
  def message: String
  def level: Int
  def position: Optional[SourcePosition]
  def diagnosticRelatedInformation: JList[DiagnosticRelatedInformation]

object Diagnostic:
  final val ERROR = 2
  final val WARNING = 1
  final val INFO = 0
