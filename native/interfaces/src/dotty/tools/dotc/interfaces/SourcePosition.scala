package dotty.tools.dotc.interfaces

// Scala version of the Java interface in interfaces/src, for the Scala Native
// build of the compiler, which cannot compile Java sources. Keep the two in sync.

trait SourcePosition:
  def lineContent: String
  def point: Int
  def line: Int
  def column: Int
  def start: Int
  def startLine: Int
  def startColumn: Int
  def end: Int
  def endLine: Int
  def endColumn: Int
  def source: SourceFile
