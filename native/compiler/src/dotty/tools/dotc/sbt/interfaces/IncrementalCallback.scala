package dotty.tools.dotc.sbt.interfaces

import dotty.tools.dotc.util.SourceFile

import java.nio.file.Path
import java.util.EnumSet

/** Scala version of `IncrementalCallback.java` for the Scala Native build of
 *  the compiler, which cannot compile Java sources. Keep the two in sync.
 *  Reference parameters accept `null`, as Java ones do under explicit nulls.
 */
trait IncrementalCallback:
  def api(sourceFile: SourceFile | Null, classApi: xsbti.api.ClassLike | Null): Unit = ()
  def startSource(sourceFile: SourceFile | Null): Unit = ()
  def mainClass(sourceFile: SourceFile | Null, className: String | Null): Unit = ()
  def enabled(): Boolean = false
  def usedName(className: String | Null, name: String | Null,
      useScopes: EnumSet[xsbti.UseScope] | Null): Unit = ()
  def binaryDependency(onBinaryEntry: Path | Null, onBinaryClassName: String | Null,
      fromClassName: String | Null, fromSourceFile: SourceFile | Null,
      context: xsbti.api.DependencyContext | Null): Unit = ()
  def classDependency(onClassName: String | Null, sourceClassName: String | Null,
      context: xsbti.api.DependencyContext | Null): Unit = ()
  def generatedLocalClass(source: SourceFile | Null, classFile: Path | Null): Unit = ()
  def generatedNonLocalClass(source: SourceFile | Null, classFile: Path | Null,
      binaryClassName: String | Null, srcClassName: String | Null): Unit = ()
  def apiPhaseCompleted(): Unit = ()
  def dependencyPhaseCompleted(): Unit = ()
