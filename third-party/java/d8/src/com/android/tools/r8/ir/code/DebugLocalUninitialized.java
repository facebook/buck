// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

/**
 * Instruction representing the SSA value for an uninitialized local.
 *
 * This instruction allows the local information to not necessarily be properly block structured.
 * For example, Javac will emit local-end labels in blocks that are not dominated by the
 * introduction of the same local, causing our SSA graph to attempt to reference a local value that
 * does not exist in all predecessors (eg, for a local declared in an unscoped switch-case).
 * By emitting this "uninitialized" value in the method prelude, such a reference will instead
 * become a phi of the uninitialized value and the local.
 *
 * This instruction may (and almost always will) be considered dead code and removed.
 */
public class DebugLocalUninitialized extends ConstNumber {

  public DebugLocalUninitialized(Value value) {
    super(value, 0);
  }

  @Override
  public boolean isDebugLocalUninitialized() {
    return true;
  }

  @Override
  public DebugLocalUninitialized asDebugLocalUninitialized() {
    return this;
  }
}
