// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.ShrInt;
import com.android.tools.r8.code.ShrInt2Addr;
import com.android.tools.r8.code.ShrIntLit8;
import com.android.tools.r8.code.ShrLong;
import com.android.tools.r8.code.ShrLong2Addr;
import com.android.tools.r8.errors.Unreachable;

public class Shr extends LogicalBinop {

  public Shr(NumericType type, Value dest, Value left, Value right) {
    super(type, dest, left, right);
  }

  @Override
  boolean fitsInDexInstruction(Value value) {
    // The shr instruction only has the /lit8 variant.
    return fitsInLit8Instruction(value);
  }

  @Override
  public boolean isShr() {
    return true;
  }

  @Override
  public Shr asShr() {
    return this;
  }

  @Override
  public boolean isCommutative() {
    return false;
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateInt(int dest, int left, int right) {
    return new ShrInt(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateLong(int dest, int left, int right) {
    return new ShrLong(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateInt2Addr(int left, int right) {
    return new ShrInt2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateLong2Addr(int left, int right) {
    return new ShrLong2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateIntLit8(int dest, int left, int constant) {
    return new ShrIntLit8(dest, left, constant);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateIntLit16(int dest, int left, int constant) {
    throw new Unreachable("Unsupported instruction ShrIntLit16");
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asShr().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.ordinal() - other.asShr().type.ordinal();
  }

  @Override
  int foldIntegers(int left, int right) {
    return left >> right;
  }

  @Override
  long foldLongs(long left, long right) {
    return left >> right;
  }
}
