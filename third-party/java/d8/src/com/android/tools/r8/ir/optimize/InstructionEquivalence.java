// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize;

import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.regalloc.RegisterAllocator;
import com.google.common.base.Equivalence;

public class InstructionEquivalence extends Equivalence<Instruction> {
  private final RegisterAllocator allocator;

  InstructionEquivalence(RegisterAllocator allocator) {
    this.allocator = allocator;
  }

  @Override
  protected boolean doEquivalent(Instruction a, Instruction b) {
    return a.identicalAfterRegisterAllocation(b, allocator)
        && a.getBlock().getCatchHandlers().equals(b.getBlock().getCatchHandlers());
  }

  @Override
  protected int doHash(Instruction instruction) {
    int hash = 0;
    if (instruction.outValue() != null && instruction.outValue().needsRegister()) {
      hash += allocator.getRegisterForValue(instruction.outValue(), instruction.getNumber());
    }
    for (Value inValue : instruction.inValues()) {
      hash = hash<< 4;
      if (inValue.needsRegister()) {
        hash += allocator.getRegisterForValue(inValue, instruction.getNumber());
      }
    }
    hash = hash * 37 + instruction.getBlock().getCatchHandlers().hashCode();
    return hash;
  }
}
