// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.code;

import com.android.tools.r8.ir.conversion.IRBuilder;

public abstract class SwitchPayload extends Nop {
  SwitchPayload(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public SwitchPayload() {
  }

  public abstract int[] keys();
  public abstract int numberOfKeys();
  public abstract int[] switchTargetOffsets();

  @Override
  public boolean isSwitchPayload() {
    return true;
  }

  @Override
  public void buildIR(IRBuilder builder) {
    // Switch payloads are not represented in the IR.
  }
}
