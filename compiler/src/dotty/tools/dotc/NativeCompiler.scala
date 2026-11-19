package dotty.tools
package dotc

import core.*
import Contexts.*
import typer.TyperPhase
import parsing.Parser
import Phases.Phase
import Symbols.ClassSymbol
import StdNames.str
import transform.*
import dotty.tools.io.FileWriters

/** The compiler as built with Scala Native (`scala3-compiler-native`).
 *
 *  It leaves out the JVM and Scala.js backends. For now it emits TASTy only,
 *  with `WriteTasty` (and NIR with the Scala Native plugin). The sbt phases
 *  run when there is an incremental callback, as in `Compiler`. The phase
 *  lists copy `Compiler`'s; `NativeCompilerTest` checks that they only differ
 *  by the phases left out and added.
 */
class NativeCompiler extends Compiler {

  override protected def frontendPhases: List[List[Phase]] =
    List(new Parser) ::
    List(new TyperPhase) ::
    List(new WInferUnion,
         CheckUnused.PostTyper(),
         CheckShadowing()) ::
    List(new YCheckPositions) ::
    List(new sbt.ExtractDependencies) ::
    List(new semanticdb.ExtractSemanticInfo) ::
    List(new PostTyper) ::
    List(new UnrollDefinitions) ::
    List(new sjs.PrepJSInterop) ::
    List(new SetRootTree) ::
    List(new CheckInlineTraits,
         new DesugarSpecializedTraits,
         new SpecializeInlineTraits) ::
    List(new PostSpecializeInlineTraits) ::
    Nil

  override protected def picklerPhases: List[List[Phase]] =
    List(new Pickler) ::
    List(new sbt.ExtractAPI) ::
    List(new Inlining) ::
    List(new PostInlining) ::
    List(new Staging) ::
    List(new Splicing) ::
    List(new PickleQuotes) ::
    Nil

  override protected def backendPhases: List[List[Phase]] =
    List(new WriteTasty) ::
    Nil
}

object NativeCompiler:
  /** Phases of `Compiler` that `NativeCompiler` leaves out. */
  val leftOutPhases: Set[String] =
    Set("genSJSIR", "genBCode")

  /** Phases of `NativeCompiler` that `Compiler` does not have. */
  val addedPhases: List[String] = List(WriteTasty.name)

/** Writes the TASTy of each top-level class to the output, under the names
 *  the JVM backend gives the `.tasty` files it writes next to class files.
 */
class WriteTasty extends Phase:
  override def phaseName: String = WriteTasty.name
  override def description: String = WriteTasty.description

  override def runOn(units: List[CompilationUnit])(using Context): List[CompilationUnit] =
    val writer = FileWriters.TastyWriter(ctx.settings.outputDir.value)
    try
      // Like the JVM backend, write no TASTy for Java sources (-Xjava-tasty).
      // A unit compiled again after a suspension still holds the pickles of
      // its earlier run's classes, whose symbols are stale: skip them. The JVM
      // backend looks pickles up by the classes it generates, so it never
      // sees them
      for unit <- units if !unit.isJava; (cls, pickled) <- unit.pickled if cls.defRunId == ctx.runId do
        writer.writeTasty(internalName(cls), pickled())
    finally writer.close()
    units

  protected def run(using Context): Unit = ()

  /** A module's TASTy goes with its mirror class, the module name without `$`. */
  private def internalName(cls: ClassSymbol)(using Context): String =
    if cls.is(Flags.Module) then cls.binaryClassName.stripSuffix(str.MODULE_SUFFIX)
    else cls.binaryClassName

object WriteTasty:
  val name: String = "writeTasty"
  val description: String = "write .tasty files"

/** `NativeCompiler` with the frontend of `TASTYCompiler`, for `-from-tasty`. */
class NativeTASTYCompiler extends NativeCompiler:
  override protected def frontendPhases: List[List[Phase]] =
    List(new fromtasty.ReadTasty) :: Nil

  override def newRun(using Context): Run =
    reset()
    new fromtasty.TASTYRun(this, ctx.addMode(Mode.ReadPositions))

/** A driver for the compiler as built with Scala Native. */
class NativeDriver extends Driver:
  override protected def newCompiler(using Context): Compiler =
    if ctx.settings.fromTasty.value then new NativeTASTYCompiler
    else new NativeCompiler

/** Main class of the native `dotc` (`scala3-compiler-native`). */
object NativeMain extends NativeDriver
