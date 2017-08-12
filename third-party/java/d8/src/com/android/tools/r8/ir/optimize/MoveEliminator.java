// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize;

import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.Move;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.regalloc.RegisterAllocator;
import java.util.HashSet;

class MoveEliminator {
  private final HashSet<Move> activeMoves = new HashSet<>();
  private final RegisterAllocator allocator;

  MoveEliminator(RegisterAllocator allocator) {
    this.allocator = allocator;
  }

  public boolean shouldBeEliminated(Instruction instruction) {
    if (instruction.isMove()) {
      Move move = instruction.asMove();
      int moveSrcRegister = allocator.getRegisterForValue(move.src(), move.getNumber());
      int moveDstRegister = allocator.getRegisterForValue(move.dest(), move.getNumber());
      if (moveSrcRegister == moveDstRegister) {
        return true;
      }
      for (Move activeMove : activeMoves) {
        int activeMoveSrcRegister =
            allocator.getRegisterForValue(activeMove.src(), activeMove.getNumber());
        int activeMoveDstRegister =
            allocator.getRegisterForValue(activeMove.dest(), activeMove.getNumber());
        if (activeMoveSrcRegister == moveSrcRegister && activeMoveDstRegister == moveDstRegister) {
          return true;
        }
        if (activeMoveDstRegister == moveSrcRegister && activeMoveSrcRegister == moveDstRegister) {
          if (!move.outType().isWide()) {
            return true;
          }
          // If the move is wide make sure the register pair is non-overlapping.
          if (moveSrcRegister != moveDstRegister + 1 && moveSrcRegister + 1 != moveDstRegister) {
            return true;
          }
        }
      }
    }
    if (instruction.outValue() != null && instruction.outValue().needsRegister()) {
      Value defined = instruction.outValue();
      int definedRegister = allocator.getRegisterForValue(defined, instruction.getNumber());
      activeMoves.removeIf((m) -> {
        int moveSrcRegister = allocator.getRegisterForValue(m.src(), m.getNumber());
        int moveDstRegister = allocator.getRegisterForValue(m.dest(), m.getNumber());
        for (int i = 0; i < defined.requiredRegisters(); i++) {
          for (int j = 0; j < m.outValue().requiredRegisters(); j++) {
            if (definedRegister + i == moveDstRegister + j
                || definedRegister + i == moveSrcRegister + j) {
              return true;
            }
          }
        }
        return false;
      });
    }
    if (instruction.isMove()) {
      activeMoves.add(instruction.asMove());
    }
    return false;
  }
}
