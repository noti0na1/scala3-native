// Macros whose expansion fails inside the extension: the compiler catches the
// extension's exception, and the error the extension reports.
object Fail:
  val a = snchost.Macros.boom()
  val b = snchost.Macros.abort()
  val c = snchost.Macros.boomOp()
