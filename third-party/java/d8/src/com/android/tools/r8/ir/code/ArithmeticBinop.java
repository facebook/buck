// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.conversion.DexBuilder;

public abstract class ArithmeticBinop extends Binop {

  public ArithmeticBinop(NumericType type, Value dest, Value left, Value right) {
    super(type, dest, left, right);
  }

  public abstract com.android.tools.r8.code.Instruction CreateInt(int dest, int left, int right);

  public abstract Instruction CreateLong(int dest, int left, int right);

  public abstract Instruction CreateFloat(int dest, int left, int right);

  public abstract Instruction CreateDouble(int dest, int left, int right);

  public abstract Instruction CreateInt2Addr(int left, int right);

  public abstract Instruction CreateLong2Addr(int left, int right);

  public abstract Instruction CreateFloat2Addr(int left, int right);

  public abstract Instruction CreateDouble2Addr(int left, int right);

  public abstract Instruction CreateIntLit8(int dest, int left, int constant);

  public abstract Instruction CreateIntLit16(int dest, int left, int constant);

  @Override
  public boolean canBeFolded() {
    return (type == NumericType.INT || type == NumericType.LONG || type == NumericType.FLOAT
            || type == NumericType.DOUBLE)
        && leftValue().isConstant() && rightValue().isConstant();
  }

  @Override
  public boolean needsValueInRegister(Value value) {
    assert !isSub();  // Constants in instructions for sub must be handled in subclass Sub.
    // Always require the left value in a register. If left and right are the same value, then
    // both will use its register.
    if (value == leftValue()) {
      return true;
    }
    assert value == rightValue();
    return !fitsInDexInstruction(value);
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
    } else if (type == NumericType.LONG) {
      long left = leftValue().getConstInstruction().asConstNumber().getLongValue();
      long right = rightValue().getConstInstruction().asConstNumber().getLongValue();
      long result = foldLongs(left, right);
      Value value = code.createValue(ValueType.LONG, getLocalInfo());
      return new ConstNumber(value, result);
    } else if (type == NumericType.FLOAT) {
      float left = leftValue().getConstInstruction().asConstNumber().getFloatValue();
      float right = rightValue().getConstInstruction().asConstNumber().getFloatValue();
      float result = foldFloat(left, right);
      Value value = code.createValue(ValueType.FLOAT, getLocalInfo());
      return new ConstNumber(value, Float.floatToIntBits(result));
    } else {
      assert type == NumericType.DOUBLE;
      double left = leftValue().getConstInstruction().asConstNumber().getDoubleValue();
      double right = rightValue().getConstInstruction().asConstNumber().getDoubleValue();
      double result = foldDouble(left, right);
      Value value = code.createValue(ValueType.DOUBLE, getLocalInfo());
      return new ConstNumber(value, Double.doubleToLongBits(result));
    }
  }

  @Override
  public void buildDex(DexBuilder builder) {
    // Method needsValueInRegister ensures that left value has an allocated register.
    int left = builder.allocatedRegister(leftValue(), getNumber());
    int dest = builder.allocatedRegister(outValue, getNumber());
    Instruction instruction = null;
    if (isTwoAddr(builder)) {
      int right = builder.allocatedRegister(rightValue(), getNumber());
      if (left != dest) {
        assert isCommutative();
        assert right == dest;
        right = left;
      }
      switch (type) {
        case DOUBLE:
          instruction = CreateDouble2Addr(dest, right);
          break;
        case FLOAT:
          instruction = CreateFloat2Addr(dest, right);
          break;
        case INT:
          instruction = CreateInt2Addr(dest, right);
          break;
        case LONG:
          instruction = CreateLong2Addr(dest, right);
          break;
        default:
          throw new Unreachable("Unexpected numeric type " + type.name());
      }
    } else if (!rightValue().needsRegister()) {
      assert !isSub();  // Constants in instructions for sub must be handled in subclass Sub.
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
        case DOUBLE:
          instruction = CreateDouble(dest, left, right);
          break;
        case FLOAT:
          instruction = CreateFloat(dest, left, right);
          break;
        case INT:
          instruction = CreateInt(dest, left, right);
          break;
        case LONG:
          instruction = CreateLong(dest, left, right);
          break;
        default:
          throw new Unreachable("Unexpected numeric type " + type.name());
      }
    }
    builder.add(this, instruction);
  }

  @Override
  public boolean isArithmeticBinop() {
    return true;
  }

  @Override
  public ArithmeticBinop asArithmeticBinop() {
    return this;
  }
}
