// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.Aput;
import com.android.tools.r8.code.AputBoolean;
import com.android.tools.r8.code.AputByte;
import com.android.tools.r8.code.AputChar;
import com.android.tools.r8.code.AputObject;
import com.android.tools.r8.code.AputShort;
import com.android.tools.r8.code.AputWide;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;
import com.android.tools.r8.ir.regalloc.RegisterAllocator;
import com.android.tools.r8.utils.InternalOptions;
import java.util.List;

public class ArrayPut extends Instruction {

  private final MemberType type;

  public ArrayPut(MemberType type, List<Value> ins) {
    super(null, ins);
    assert type != null;
    this.type = type;
  }

  public Value source() {
    return inValues.get(0);
  }

  public Value array() {
    return inValues.get(1);
  }

  public Value index() {
    return inValues.get(2);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    int source = builder.allocatedRegister(source(), getNumber());
    int array = builder.allocatedRegister(array(), getNumber());
    int index = builder.allocatedRegister(index(), getNumber());
    com.android.tools.r8.code.Instruction instruction;
    switch (type) {
      case INT:
      case FLOAT:
      case INT_OR_FLOAT:
        instruction = new Aput(source, array, index);
        break;
      case LONG:
      case DOUBLE:
      case LONG_OR_DOUBLE:
        instruction = new AputWide(source, array, index);
        break;
      case OBJECT:
        instruction = new AputObject(source, array, index);
        break;
      case BOOLEAN:
        instruction = new AputBoolean(source, array, index);
        break;
      case BYTE:
        instruction = new AputByte(source, array, index);
        break;
      case CHAR:
        instruction = new AputChar(source, array, index);
        break;
      case SHORT:
        instruction = new AputShort(source, array, index);
        break;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
    builder.add(this, instruction);
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    assert false : "ArrayPut instructions define no values.";
    return 0;
  }

  @Override
  public boolean instructionTypeCanThrow() {
    return true;
  }

  @Override
  public boolean instructionInstanceCanThrow() {
    if (index().isConstant() && !array().isPhi() && array().definition.isNewArrayEmpty()) {
      Value newArraySizeValue = array().definition.asNewArrayEmpty().size();
      if (newArraySizeValue.isConstant()) {
        int newArraySize = newArraySizeValue.getConstInstruction().asConstNumber().getIntValue();
        int index = index().getConstInstruction().asConstNumber().getIntValue();
        return newArraySize <= 0 || index < 0 || newArraySize <= index;
      }
    }
    return true;
  }

  @Override
  public boolean canBeDeadCode(IRCode code, InternalOptions options) {
    // ArrayPut has side-effects on input values.
    return false;
  }

  @Override
  public boolean identicalAfterRegisterAllocation(Instruction other, RegisterAllocator allocator) {
    // We cannot share ArrayPut instructions without knowledge of the type of the array input.
    // If multiple primitive array types flow to the same ArrayPut instruction the art verifier
    // gets confused.
    return false;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asArrayPut().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.ordinal() - other.asArrayPut().type.ordinal();
  }

  @Override
  public boolean isArrayPut() {
    return true;
  }

  @Override
  public ArrayPut asArrayPut() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.ALWAYS;
  }
}
