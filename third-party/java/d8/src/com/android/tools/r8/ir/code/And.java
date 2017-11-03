// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.AndInt;
import com.android.tools.r8.code.AndInt2Addr;
import com.android.tools.r8.code.AndIntLit16;
import com.android.tools.r8.code.AndIntLit8;
import com.android.tools.r8.code.AndLong;
import com.android.tools.r8.code.AndLong2Addr;

public class And extends LogicalBinop {

  public And(NumericType type, Value dest, Value left, Value right) {
    super(type, dest, left, right);
  }

  @Override
  public boolean isAnd() {
    return true;
  }

  @Override
  public And asAnd() {
    return this;
  }

  @Override
  public boolean isCommutative() {
    return true;
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateInt(int dest, int left, int right) {
    return new AndInt(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateLong(int dest, int left, int right) {
    // The dalvik jit had a bug where the long operations add, sub, or, xor and and would write
    // the first part of the result long before reading the second part of the input longs.
    // Therefore, there can be no overlap of the second part of an input long and the first
    // part of the output long.
    assert dest != left + 1;
    assert dest != right + 1;
    return new AndLong(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateInt2Addr(int left, int right) {
    return new AndInt2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateLong2Addr(int left, int right) {
    return new AndLong2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateIntLit8(int dest, int left, int constant) {
    return new AndIntLit8(dest, left, constant);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateIntLit16(int dest, int left, int constant) {
    return new AndIntLit16(dest, left, constant);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asAnd().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.ordinal() - other.asAnd().type.ordinal();
  }

  @Override
  int foldIntegers(int left, int right) {
    return left & right;
  }

  @Override
  long foldLongs(long left, long right) {
    return left & right;
  }
}
