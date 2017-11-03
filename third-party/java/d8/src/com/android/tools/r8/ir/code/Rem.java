// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.RemDouble;
import com.android.tools.r8.code.RemDouble2Addr;
import com.android.tools.r8.code.RemFloat;
import com.android.tools.r8.code.RemFloat2Addr;
import com.android.tools.r8.code.RemInt;
import com.android.tools.r8.code.RemInt2Addr;
import com.android.tools.r8.code.RemIntLit16;
import com.android.tools.r8.code.RemIntLit8;
import com.android.tools.r8.code.RemLong;
import com.android.tools.r8.code.RemLong2Addr;

public class Rem extends ArithmeticBinop {

  public Rem(NumericType type, Value dest, Value left, Value right) {
    super(type, dest, left, right);
  }

  @Override
  public boolean isRem() {
    return true;
  }

  @Override
  public Rem asRem() {
    return this;
  }

  @Override
  public boolean isCommutative() {
    return false;
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateInt(int dest, int left, int right) {
    return new RemInt(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateLong(int dest, int left, int right) {
    return new RemLong(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateFloat(int dest, int left, int right) {
    return new RemFloat(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateDouble(int dest, int left, int right) {
    return new RemDouble(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateInt2Addr(int left, int right) {
    return new RemInt2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateLong2Addr(int left, int right) {
    return new RemLong2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateFloat2Addr(int left, int right) {
    return new RemFloat2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateDouble2Addr(int left, int right) {
    return new RemDouble2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateIntLit8(int dest, int left, int constant) {
    return new RemIntLit8(dest, left, constant);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateIntLit16(int dest, int left, int constant) {
    return new RemIntLit16(dest, left, constant);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asRem().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.ordinal() - other.asRem().type.ordinal();
  }

  @Override
  public boolean canBeFolded() {
    return super.canBeFolded() && !rightValue().isZero();
  }

  @Override
  int foldIntegers(int left, int right) {
    return left % right;
  }

  @Override
  long foldLongs(long left, long right) {
    return left % right;
  }

  @Override
  float foldFloat(float left, float right) {
    return left % right;
  }

  @Override
  double foldDouble(double left, double right) {
    return left % right;
  }

  @Override
  public boolean instructionTypeCanThrow() {
    return type != NumericType.DOUBLE && type != NumericType.FLOAT;
  }
}
