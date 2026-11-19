package snchost.shadow

import scala.quoted.*

/** A macro whose code takes `Helper` from another class path entry, so that
 *  its cached bundle depends on that entry.
 */
object ShadowMacro:
  inline def helperValue: Int = ${ helperValueImpl }

  def helperValueImpl(using Quotes): Expr[Int] =
    println(s"shadow: Helper.value is ${Helper.value}")
    Expr(Helper.value)
