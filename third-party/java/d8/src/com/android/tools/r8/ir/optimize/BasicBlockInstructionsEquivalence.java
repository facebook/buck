// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize;

import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.CatchHandlers;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.regalloc.RegisterAllocator;
import com.google.common.base.Equivalence;
import java.util.Arrays;
import java.util.List;

class BasicBlockInstructionsEquivalence extends Equivalence<BasicBlock> {
  private static final int UNKNOW_HASH = -1;
  private static final int MAX_HASH_INSTRUCTIONS = 5;
  private final RegisterAllocator allocator;
  private final int[] hashes;

  BasicBlockInstructionsEquivalence(IRCode code, RegisterAllocator allocator) {
    this.allocator = allocator;
    hashes = new int[code.getHighestBlockNumber() + 1];
    Arrays.fill(hashes, UNKNOW_HASH);
  }

  private boolean hasIdenticalInstructions(BasicBlock first, BasicBlock second) {
    List<Instruction> instructions0 = first.getInstructions();
    List<Instruction> instructions1 = second.getInstructions();
    if (instructions0.size() != instructions1.size()) {
      return false;
    }
    for (int i = 0; i < instructions0.size(); i++) {
      Instruction i0 = instructions0.get(i);
      Instruction i1 = instructions1.get(i);
      if (!i0.identicalAfterRegisterAllocation(i1, allocator)) {
        return false;
      }
    }
    CatchHandlers<BasicBlock> handlers0 = first.getCatchHandlers();
    CatchHandlers<BasicBlock> handlers1 = second.getCatchHandlers();
    if (!handlers0.equals(handlers1)) {
      return false;
    }
    // Normal successors are equal based on equality of the instruction stream. Verify that here.
    assert verifyAllSuccessors(first.getSuccessors(), second.getSuccessors());
    return true;
  }

  private boolean verifyAllSuccessors(List<BasicBlock> successors0, List<BasicBlock> successors1) {
    if (successors0.size() != successors1.size()) {
      return false;
    }
    for (int i = 0; i < successors0.size(); i++) {
      if (successors0.get(i) != successors1.get(i)) {
        return false;
      }
    }
    return true;
  }

  @Override
  protected boolean doEquivalent(BasicBlock a, BasicBlock b) {
    return hasIdenticalInstructions(a, b);
  }

  void clearComputedHash(BasicBlock basicBlock) {
    hashes[basicBlock.getNumber()] = UNKNOW_HASH;
  }

  @Override
  protected int doHash(BasicBlock basicBlock) {
    int hash = hashes[basicBlock.getNumber()];
    if (hash != UNKNOW_HASH) {
      assert hash == computeHash(basicBlock);
      return hash;
    }
    hash = computeHash(basicBlock);
    hashes[basicBlock.getNumber()] = hash;
    return hash;
  }

  private int computeHash(BasicBlock basicBlock) {
    List<Instruction> instructions = basicBlock.getInstructions();
    int hash = instructions.size();
    for (int i = 0; i < instructions.size() && i < MAX_HASH_INSTRUCTIONS; i++) {
      Instruction instruction = instructions.get(i);
      int hashPart = 0;
      if (instruction.outValue() != null && instruction.outValue().needsRegister()) {
        hashPart += allocator.getRegisterForValue(instruction.outValue(), instruction.getNumber());
      }
      for (Value inValue : instruction.inValues()) {
        hashPart = hashPart << 4;
        if (inValue.needsRegister()) {
          hashPart += allocator.getRegisterForValue(inValue, instruction.getNumber());
        }
      }
      hash = hash * 3 + hashPart;
    }
    return hash;
  }
}
