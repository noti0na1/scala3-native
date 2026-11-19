/*
 * Scala version of xsbti/UseScope.java from Zinc's compiler-interface 1.12.1
 * (https://github.com/sbt/zinc), for the Scala Native build of the compiler.
 *
 * Zinc - The incremental compiler for Scala.
 * Copyright Scala Center, Lightbend, and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 */

package xsbti

enum UseScope extends java.lang.Enum[UseScope]:
  case Default, Implicit, PatMatTarget
