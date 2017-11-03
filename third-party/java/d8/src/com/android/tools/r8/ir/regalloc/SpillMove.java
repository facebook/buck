// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import com.android.tools.r8.code.MoveType;

/**
 * A SpillMove represents either a phi move that transfers an SSA value to the SSA phi value or
 * a spill or restore move that transfers the same SSA value between different registers because
 * of spilling.
 */
class SpillMove {
  MoveType type;
  LiveIntervals from;
  LiveIntervals to;

  public SpillMove(MoveType type, LiveIntervals to, LiveIntervals from) {
    this.type = type;
    this.to = to;
    this.from = from;
    assert to.getRegister() != LinearScanRegisterAllocator.NO_REGISTER;
    assert from.getRegister() != LinearScanRegisterAllocator.NO_REGISTER;
  }

  @Override
  public int hashCode() {
    return type.hashCode() + 3 * from.getRegister() + 5 * to.getRegister();
  }

  public void updateMaxNonSpilled() {
    int maxFrom = from.getMaxNonSpilledRegister();
    int maxTo = to.getMaxNonSpilledRegister();
    if (maxFrom > maxTo) {
      to.setMaxNonSpilledRegister(maxFrom);
    } else {
      from.setMaxNonSpilledRegister(maxTo);
    }
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (!(other instanceof SpillMove)) {
      return false;
    }
    SpillMove o = (SpillMove) other;
    return type == o.type
        && from.getRegister() == o.from.getRegister()
        && to.getRegister() == o.to.getRegister()
        && from.getSplitParent() == o.from.getSplitParent()
        && to.getSplitParent() == o.to.getSplitParent();
  }

  @Override
  public String toString() {
    return to.getRegister() + " <- " + from.getRegister() + " (" + type + ")";
  }
}
