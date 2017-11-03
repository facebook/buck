// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.conversion.DexBuilder;

public abstract class LogicalBinop extends Binop {

  public LogicalBinop(NumericType type, Value dest, Value left, Value right) {
    super(type, dest, left, right);
  }

  public abstract com.android.tools.r8.code.Instruction CreateInt(int dest, int left, int right);

  public abstract Instruction CreateLong(int dest, int left, int right);

  public abstract Instruction CreateInt2Addr(int left, int right);

  public abstract Instruction CreateLong2Addr(int left, int right);

  public abstract Instruction CreateIntLit8(int dest, int left, int constant);

  public abstract Instruction CreateIntLit16(int dest, int left, int constant);

  @Override
  public boolean canBeFolded() {
    return leftValue().isConstant() && rightValue().isConstant();
  }

  @Override
  public ConstInstruction fold(IRCode code) {
    assert canBeFolded();
    if (type == NumericType.INT) {
      int left = leftValue().getConstInstruction().asConstNumber().getIntValue();
      int right = rightValue().getConstInstruction().asConstNumber().getIntValue();
      int result = foldIntegers(left, right);
      Value value = code.createValue(ValueType.INT, getLocalInfo());
      return new ConstNumber(value, result);
    } else {
      assert type == NumericType.LONG;
      long left = leftValue().getConstInstruction().asConstNumber().getLongValue();
      long right;
      if (isShl() || isShr() || isUshr()) {
        // Right argument for shl, shr and ushr is always of type single.
        right = rightValue().getConstInstruction().asConstNumber().getIntValue();
      } else {
        right = rightValue().getConstInstruction().asConstNumber().getLongValue();
      }
      long result = foldLongs(left, right);
      Value value = code.createValue(ValueType.LONG, getLocalInfo());
      return new ConstNumber(value, result);
    }
  }

  @Override
  public boolean needsValueInRegister(Value value) {
    // Always require the left value in a register. If left and right are the same value, then
    // both will use its register.
    if (value == leftValue()) {
      return true;
    }
    assert value == rightValue();
    return !fitsInDexInstruction(value);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    // TODO(ager, sgjesse): For now the left operand is always a value. With constant propagation
    // that will change.
    int left = builder.allocatedRegister(leftValue(), getNumber());
    int dest = builder.allocatedRegister(outValue, getNumber());
    Instruction instruction;
    if (isTwoAddr(builder)) {
      int right = builder.allocatedRegister(rightValue(), getNumber());
      if (left != dest) {
        assert isCommutative();
        assert right == dest;
        right = left;
      }
      switch (type) {
        case INT:
          instruction = CreateInt2Addr(dest, right);
          break;
        case LONG:
          instruction = CreateLong2Addr(dest, right);
          break;
        default:
          throw new Unreachable("Unexpected type " + type);
      }
    } else if (!rightValue().needsRegister()) {
      assert fitsInDexInstruction(rightValue());
      ConstNumber right = rightValue().getConstInstruction().asConstNumber();
      if (right.is8Bit()) {
        instruction = CreateIntLit8(dest, left, right.getIntValue());
      } else {
        assert right.is16Bit();
        instruction = CreateIntLit16(dest, left, right.getIntValue());
      }
    } else {
      int right = builder.allocatedRegister(rightValue(), getNumber());
      switch (type) {
        case INT:
          instruction = CreateInt(dest, left, right);
          break;
        case LONG:
          instruction = CreateLong(dest, left, right);
          break;
        default:
          throw new Unreachable("Unexpected type " + type);
      }
    }
    builder.add(this, instruction);
  }

  @Override
  public boolean isLogicalBinop() {
    return true;
  }

  @Override
  public LogicalBinop asLogicalBinop() {
    return this;
  }
}
