// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.AddIntLit16;
import com.android.tools.r8.code.AddIntLit8;
import com.android.tools.r8.code.RsubInt;
import com.android.tools.r8.code.RsubIntLit8;
import com.android.tools.r8.code.SubDouble;
import com.android.tools.r8.code.SubDouble2Addr;
import com.android.tools.r8.code.SubFloat;
import com.android.tools.r8.code.SubFloat2Addr;
import com.android.tools.r8.code.SubInt;
import com.android.tools.r8.code.SubInt2Addr;
import com.android.tools.r8.code.SubLong;
import com.android.tools.r8.code.SubLong2Addr;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.conversion.DexBuilder;

public class Sub extends ArithmeticBinop {

  public Sub(NumericType type, Value dest, Value left, Value right) {
    super(type, dest, left, right);
  }

  @Override
  public boolean isCommutative() {
    return false;
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateInt(int dest, int left, int right) {
    return new SubInt(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateLong(int dest, int left, int right) {
    // The dalvik jit had a bug where the long operations add, sub, or, xor and and would write
    // the first part of the result long before reading the second part of the input longs.
    // Therefore, there can be no overlap of the second part of an input long and the first
    // part of the output long.
    assert dest != left + 1;
    assert dest != right + 1;
    return new SubLong(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateFloat(int dest, int left, int right) {
    return new SubFloat(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateDouble(int dest, int left, int right) {
    return new SubDouble(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateInt2Addr(int left, int right) {
    return new SubInt2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateLong2Addr(int left, int right) {
    return new SubLong2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateFloat2Addr(int left, int right) {
    return new SubFloat2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateDouble2Addr(int left, int right) {
    return new SubDouble2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateIntLit8(int dest, int left, int constant) {
    // The sub instructions with constants are rsub, and is handled below.
    throw new Unreachable("Unsupported instruction SubIntLit8");
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateIntLit16(int dest, int left, int constant) {
    // The sub instructions with constants are rsub, and is handled below.
    throw new Unreachable("Unsupported instruction SubIntLit16");
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asSub().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.ordinal() - other.asSub().type.ordinal();
  }

  @Override
  int foldIntegers(int left, int right) {
    return left - right;
  }

  @Override
  long foldLongs(long left, long right) {
    return left - right;
  }

  @Override
  float foldFloat(float left, float right) {
    return left - right;
  }

  @Override
  double foldDouble(double left, double right) {
    return left - right;
  }

  boolean negativeFitsInDexInstruction(Value value) {
    return type == NumericType.INT &&
        value.isConstant() &&
        value.getConstInstruction().asConstNumber().negativeIs16Bit();
  }

  // This is overridden to give the correct value when adding the negative constant.
  @Override
  int maxInOutValueRegisterSize() {
    if (!leftValue().needsRegister()) {
      assert fitsInDexInstruction(leftValue());
      ConstNumber left = leftValue().getConstInstruction().asConstNumber();
      return left.is8Bit() ? Constants.U8BIT_MAX : Constants.U4BIT_MAX;
    } else if (!rightValue().needsRegister()) {
      assert negativeFitsInDexInstruction(rightValue());
      ConstNumber right = rightValue().getConstInstruction().asConstNumber();
      return right.negativeIs8Bit() ? Constants.U8BIT_MAX : Constants.U4BIT_MAX;
    }
    return Constants.U8BIT_MAX;
  }

  @Override
  public boolean needsValueInRegister(Value value) {
    if (leftValue() == rightValue()) {
      // We cannot distinguish the the two values, so both must end up in registers no matter what.
      return true;
    }
    if (value == leftValue()) {
      // If the left value fits in the dex instruction no register is needed for that (rsub
      // instruction).
      return !fitsInDexInstruction(value);
    } else {
      assert value == rightValue();
      // If the negative right value fits in the dex instruction no register is needed for that (add
      // instruction with the negative value), unless the left is taking that place.
      return !negativeFitsInDexInstruction(value) || fitsInDexInstruction(leftValue());
    }
  }

  @Override
  public void buildDex(DexBuilder builder) {
    // Handle two address and non-int case through the generic arithmetic binop.
    if (isTwoAddr(builder) || type != NumericType.INT) {
      super.buildDex(builder);
      return;
    }

    com.android.tools.r8.code.Instruction instruction = null;
    if (!leftValue().needsRegister()) {
      // Sub instructions with small left constant is emitted as rsub.
      assert fitsInDexInstruction(leftValue());
      ConstNumber left = leftValue().getConstInstruction().asConstNumber();
      int right = builder.allocatedRegister(rightValue(), getNumber());
      int dest = builder.allocatedRegister(outValue, getNumber());
      if (left.is8Bit()) {
        instruction = new RsubIntLit8(dest, right, left.getIntValue());
      } else {
        assert left.is16Bit();
        instruction = new RsubInt(dest, right, left.getIntValue());
      }
    } else if (!rightValue().needsRegister()) {
      // Sub instructions with small right constant are emitted as add of the negative constant.
      assert negativeFitsInDexInstruction(rightValue());
      int dest = builder.allocatedRegister(outValue, getNumber());
      assert leftValue().needsRegister();
      int left = builder.allocatedRegister(leftValue(), getNumber());
      ConstNumber right = rightValue().getConstInstruction().asConstNumber();
      if (right.negativeIs8Bit()) {
        instruction = new AddIntLit8(dest, left, -right.getIntValue());
      } else {
        assert right.negativeIs16Bit();
        instruction = new AddIntLit16(dest, left, -right.getIntValue());
      }
    } else {
      assert type == NumericType.INT;
      int left = builder.allocatedRegister(leftValue(), getNumber());
      int right = builder.allocatedRegister(rightValue(), getNumber());
      int dest = builder.allocatedRegister(outValue, getNumber());
      instruction = CreateInt(dest, left, right);
    }
    builder.add(this, instruction);
  }

  @Override
  public boolean isSub() {
    return true;
  }

  @Override
  public Sub asSub() {
    return this;
  }
}
