package snchost

import scala.quoted.*

/** A macro whose state lives across expansions in `StatefulState`, a module
 *  that only the extension's code uses (the compiler loads `Stateful`, the
 *  macro's module, but never this one): it is reachable only from its slot in
 *  the extension's static storage, so `seen` survives the collections between
 *  expansions only if the host registered that storage as roots. Each
 *  expansion allocates garbage, so that a capped heap collects meanwhile.
 */
private object StatefulState:
  val seen = scala.collection.mutable.ArrayBuffer.empty[String]

object Stateful:
  import StatefulState.seen

  inline def count(inline tag: String): String = ${ countImpl('tag) }

  def countImpl(tag: Expr[String])(using Quotes): Expr[String] =
    // About 256 MB of garbage that dies at once: with a capped heap, the
    // collector runs several times during each expansion
    var last: Array[Byte] = null
    var i = 0
    while i < 1000000 do
      last = new Array[Byte](256)
      i += 1
    seen += tag.valueOrAbort
    val result = s"${seen.size}: ${seen.mkString(",")}"
    println("count: " + result)
    Expr(result)
