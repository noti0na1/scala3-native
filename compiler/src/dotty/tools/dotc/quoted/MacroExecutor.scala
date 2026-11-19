package dotty.tools.dotc
package quoted

import dotty.tools.dotc.config.PlatformHooks
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.util.SrcPos

/** Runs the compiled code of macro implementations.
 *
 *  `Interpreter` evaluates top-level splices (`Splicer`) and the
 *  instantiations of macro annotations (`MacroAnnotations`) itself, and asks
 *  an executor for what needs compiled code: module instances, method calls,
 *  new instances and static fields. `MacroAnnotations` then calls the
 *  annotation's `transform` through the executor too. Arguments are passed
 *  as the interpreter represents them: primitives boxed, by-name arguments
 *  as `Function0`, varargs as a `Seq`, and the parameters of a context
 *  function result type after the method's own.
 *
 *  On the JVM, `ReflectiveMacroExecutor` loads the code with a class loader
 *  and calls it by reflection. A compiler without class loading supplies its
 *  own executor (see `PlatformHooks.macroExecutor`).
 *
 *  Exceptions thrown by the macro code propagate unwrapped, and the callers
 *  report them. An executor reports its own failures, such as code that it
 *  cannot find, as an `Interpreter.StopInterpretation` at `pos`, the
 *  position of the macro call, or it suspends the compilation unit
 *  (`Interpreter.suspendOnMissing`). When the code is missing because this
 *  run compiles it and the unit cannot be suspended (a REPL line), the
 *  inliner has already reported the macro: the executor then stops the
 *  expansion with a `StopMacroExpansion`, which adds no error.
 */
trait MacroExecutor:

  /** The instance of the module class `moduleClass`. */
  def loadModule(moduleClass: Symbol, pos: SrcPos)(using Context): Object

  /** Calls `method`, a method of the class of `receiver` that is declared
   *  there or inherited, on `receiver`.
   */
  def callMethod(receiver: Object, method: Symbol, args: List[Object], pos: SrcPos)(using Context): Object

  /** A new instance of the class of the constructor `constructor`. */
  def newInstance(constructor: Symbol, args: List[Object], pos: SrcPos)(using Context): Object

  /** The value of the static field `field`. */
  def staticField(field: Symbol, pos: SrcPos)(using Context): Object

  /** Runs `body`, a macro expansion: the interpretation of a splice or of a
   *  macro annotation, and the macro code that it runs.
   */
  def run[T](body: => T)(using Context): T = body

end MacroExecutor

object MacroExecutor:

  /** The executor for macros expanded in this context. */
  def fromContext(using Context): MacroExecutor = PlatformHooks.macroExecutor

end MacroExecutor
