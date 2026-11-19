/*
 * Scala version of xsbti/api/SafeLazy.java from Zinc's compiler-interface
 * 1.12.1 (https://github.com/sbt/zinc), for the Scala Native build of the
 * compiler.
 *
 * Zinc - The incremental compiler for Scala.
 * Copyright Scala Center, Lightbend, and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 */

package xsbti.api

import java.util.function.Supplier

/** Implements a Scala `lazy val` for the sbt interface: a thunk evaluated
 *  once, whose reference is then cleared to avoid memory leaks.
 */
final class SafeLazy

object SafeLazy:
  /** A `Lazy` from a parameterless function. */
  def apply[T](sbtThunk: Supplier[T]): Lazy[T] = new Impl[T](sbtThunk)

  /** A `Lazy` from a strict value. */
  def strict[T](value: T): Lazy[T] = new StrictImpl[T](value)

  private final class Thunky[T](val thunk: Supplier[T] | Null, val result: T | Null)

  private final class Impl[T](sbtThunk: Supplier[T]) extends AbstractLazy[T]:
    private var thunky = new Thunky[T](sbtThunk, null)

    def get(): T =
      var t = thunky
      if t.result == null then
        val r = t.thunk.nn.get()
        t = new Thunky[T](null, r)
        thunky = t
      t.result.asInstanceOf[T]

  private final class StrictImpl[T](value: T) extends AbstractLazy[T]:
    def get(): T = value
