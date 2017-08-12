// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.XorInt;
import com.android.tools.r8.code.XorInt2Addr;
import com.android.tools.r8.code.XorIntLit16;
import com.android.tools.r8.code.XorIntLit8;
import com.android.tools.r8.code.XorLong;
import com.android.tools.r8.code.XorLong2Addr;

public class Xor extends LogicalBinop {

  public Xor(NumericType type, Value dest, Value left, Value right) {
    super(type, dest, left, right);
  }

  @Override
  public boolean isXor() {
    return true;
  }

  @Override
  public Xor asXor() {
    return this;
  }

  @Override
  public boolean isCommutative() {
    return true;
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateInt(int dest, int left, int right) {
    return new XorInt(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateLong(int dest, int left, int right) {
    // The dalvik jit had a bug where the long operations add, sub, or, xor and and would write
    // the first part of the result long before reading the second part of the input longs.
    // Therefore, there can be no overlap of the second part of an input long and the first
    // part of the output long.
    assert dest != left + 1;
    assert dest != right + 1;
    return new XorLong(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateInt2Addr(int left, int right) {
    return new XorInt2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateLong2Addr(int left, int right) {
    return new XorLong2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateIntLit8(int dest, int left, int constant) {
    return new XorIntLit8(dest, left, constant);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateIntLit16(int dest, int left, int constant) {
    return new XorIntLit16(dest, left, constant);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asXor().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.ordinal() - other.asXor().type.ordinal();
  }

  @Override
  int foldIntegers(int left, int right) {
    return left ^ right;
  }

  @Override
  long foldLongs(long left, long right) {
    return left ^ right;
  }
}
