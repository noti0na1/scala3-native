package dotty.tools.dotc.interfaces

// Scala version of the Java interface in interfaces/src, for the Scala Native
// build of the compiler, which cannot compile Java sources. Keep the two in sync.

import java.io.File
import java.util.Optional

trait AbstractFile:
  def name: String
  def path: String
  def jfile: Optional[File]
