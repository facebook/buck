// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

/**
 * Instruction introducing an SSA value with attached local information.
 *
 * <p>All instructions may have attached local information (defined as the local information of
 * their outgoing value). This instruction is needed to mark a transition of an existing value (with
 * a possible local attached) to a new value that has a local (possibly the same one). If all
 * ingoing values end up having the same local this can be safely removed.
 *
 * <p>For valid debug info, this instruction should have at least one debug user, denoting the end
 * of its range, and thus it should be live.
 */
public class DebugLocalWrite extends Move {

  public DebugLocalWrite(Value dest, Value src) {
    super(dest, src);
    assert dest.hasLocalInfo();
    assert dest.getLocalInfo() != src.getLocalInfo() || src.isPhi();
  }

  @Override
  public boolean isDebugLocalWrite() {
    return true;
  }

  @Override
  public DebugLocalWrite asDebugLocalWrite() {
    return this;
  }

  @Override
  public boolean isOutConstant() {
    return false;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    assert other.isDebugLocalWrite();
    return true;
  }
}
