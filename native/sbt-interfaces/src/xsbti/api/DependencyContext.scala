/*
 * Scala version of xsbti/api/DependencyContext.java from Zinc's
 * compiler-interface 1.12.1 (https://github.com/sbt/zinc), for the Scala
 * Native build of the compiler.
 *
 * Zinc - The incremental compiler for Scala.
 * Copyright Scala Center, Lightbend, and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 */

package xsbti.api

enum DependencyContext extends java.lang.Enum[DependencyContext]:
  case DependencyByMemberRef, DependencyByInheritance, LocalDependencyByInheritance,
    DependencyByMacroExpansion
