package dotty.tools.dotc

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCompilerTest:

  /** `NativeCompiler` copies `Compiler`'s phase lists; they must stay in sync. */
  @Test def phasesDifferOnlyByLeftOutAndAddedOnes(): Unit =
    def names(compiler: Compiler) = compiler.phases.map(_.map(_.phaseName))
    val expected = names(new Compiler)
      .map(_.filterNot(NativeCompiler.leftOutPhases))
      .filter(_.nonEmpty)
      ++ NativeCompiler.addedPhases.map(List(_))
    assertEquals(expected, names(new NativeCompiler))
