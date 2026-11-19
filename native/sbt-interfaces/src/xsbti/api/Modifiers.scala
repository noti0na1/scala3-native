/*
 * Scala version of xsbti/api/Modifiers.java from Zinc's compiler-interface
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

final class Modifiers private (flags: Byte) extends java.io.Serializable:
  import Modifiers.{AbstractBit, OverrideBit, FinalBit, SealedBit, ImplicitBit, LazyBit, MacroBit,
    SuperAccessorBit}

  def this(isAbstract: Boolean, isOverride: Boolean, isFinal: Boolean, isSealed: Boolean,
      isImplicit: Boolean, isLazy: Boolean, isMacro: Boolean, isSuperAccessor: Boolean) =
    this((
      Modifiers.flag(isAbstract, Modifiers.AbstractBit) |
      Modifiers.flag(isOverride, Modifiers.OverrideBit) |
      Modifiers.flag(isFinal, Modifiers.FinalBit) |
      Modifiers.flag(isSealed, Modifiers.SealedBit) |
      Modifiers.flag(isImplicit, Modifiers.ImplicitBit) |
      Modifiers.flag(isLazy, Modifiers.LazyBit) |
      Modifiers.flag(isMacro, Modifiers.MacroBit) |
      Modifiers.flag(isSuperAccessor, Modifiers.SuperAccessorBit)
    ).toByte)

  private def flag(bit: Int): Boolean = (flags & (1 << bit)) != 0

  final def raw(): Byte = flags

  final def isAbstract(): Boolean = flag(AbstractBit)
  final def isOverride(): Boolean = flag(OverrideBit)
  final def isFinal(): Boolean = flag(FinalBit)
  final def isSealed(): Boolean = flag(SealedBit)
  final def isImplicit(): Boolean = flag(ImplicitBit)
  final def isLazy(): Boolean = flag(LazyBit)
  final def isMacro(): Boolean = flag(MacroBit)
  final def isSuperAccessor(): Boolean = flag(SuperAccessorBit)

  override def equals(o: Any): Boolean = o match
    case m: Modifiers => flags == m.raw()
    case _ => false

  override def hashCode(): Int = flags

  override def toString(): String =
    "Modifiers(" + "isAbstract: " + isAbstract() + ", " + "isOverride: " + isOverride() + ", " +
      "isFinal: " + isFinal() + ", " + "isSealed: " + isSealed() + ", " + "isImplicit: " +
      isImplicit() + ", " + "isLazy: " + isLazy() + ", " + "isMacro: " + isMacro() +
      ", isSuperAccessor:" + isSuperAccessor() + ")"

object Modifiers:
  private final val AbstractBit = 0
  private final val OverrideBit = 1
  private final val FinalBit = 2
  private final val SealedBit = 3
  private final val ImplicitBit = 4
  private final val LazyBit = 5
  private final val MacroBit = 6
  private final val SuperAccessorBit = 7

  private def flag(set: Boolean, bit: Int): Int = if set then 1 << bit else 0
