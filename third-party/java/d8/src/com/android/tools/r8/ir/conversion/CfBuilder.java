// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.cf.code.CfInstruction;
import com.android.tools.r8.errors.Unimplemented;
import com.android.tools.r8.graph.CfCode;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.ir.code.Argument;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.Load;
import com.android.tools.r8.ir.code.Pop;
import com.android.tools.r8.ir.code.StackValue;
import com.android.tools.r8.ir.code.Store;
import com.android.tools.r8.ir.code.Value;
import java.util.ArrayList;
import java.util.List;

public class CfBuilder {

  private final DexEncodedMethod method;
  private final IRCode code;
  private List<CfInstruction> instructions;

  public static class StackHelper {

    public final DexMethod method;

    public StackHelper(DexMethod method) {
      this.method = method;
    }

    public void loadInValues(Instruction instruction, InstructionListIterator it) {
      it.previous();
      for (int i = 0; i < instruction.inValues().size(); i++) {
        Value value = instruction.inValues().get(i);
        StackValue stackValue = new StackValue(value.outType());
        instruction.replaceValue(value, stackValue);
        add(new Load(stackValue, value), instruction, it);
      }
      it.next();
    }

    public void storeOutValue(Instruction instruction, InstructionListIterator it) {
      StackValue newOutValue = new StackValue(instruction.outType());
      Value oldOutValue = instruction.swapOutValue(newOutValue);
      add(new Store(oldOutValue, newOutValue), instruction, it);
    }

    public void popOutValue(Instruction instruction, InstructionListIterator it) {
      StackValue newOutValue = new StackValue(instruction.outType());
      instruction.swapOutValue(newOutValue);
      add(new Pop(newOutValue), instruction, it);
    }

    private static void add(
        Instruction newInstruction, Instruction existingInstruction, InstructionListIterator it) {
      newInstruction.setBlock(existingInstruction.getBlock());
      newInstruction.setPosition(existingInstruction.getPosition());
      it.add(newInstruction);
    }
  }

  public CfBuilder(DexEncodedMethod method, IRCode code) {
    this.method = method;
    this.code = code;
  }

  public Code build() {
    try {
      loadStoreInsertion();
      // TODO(zerny): Optimize load/store patterns.
      // TODO(zerny): Compute locals/register allocation.
      // TODO(zerny): Compute debug info.
      return buildCfCode();
    } catch (Unimplemented e) {
      System.out.println("Incomplete CF construction: " + e.getMessage());
      return method.getCode().asJarCode();
    }
  }

  private void loadStoreInsertion() {
    StackHelper stack = new StackHelper(method.method);
    for (BasicBlock block : code.blocks) {
      InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        Instruction current = it.next();
        current.insertLoadAndStores(it, stack);
      }
    }
  }

  private CfCode buildCfCode() {
    instructions = new ArrayList<>();
    InstructionIterator it = code.instructionIterator();
    while (it.hasNext()) {
      it.next().buildCf(this);
    }
    return new CfCode(instructions);
  }

  // Callbacks

  public void add(CfInstruction instruction) {
    instructions.add(instruction);
  }

  public void addArgument(Argument argument) {
    // Nothing so far.
  }
}
