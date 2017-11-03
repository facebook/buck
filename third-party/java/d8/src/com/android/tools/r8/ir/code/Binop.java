// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import static com.android.tools.r8.dex.Constants.U4BIT_MAX;
import static com.android.tools.r8.dex.Constants.U8BIT_MAX;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;

public abstract class Binop extends Instruction {

  protected final NumericType type;

  public Binop(NumericType type, Value dest, Value left, Value right) {
    super(dest);
    this.type = type;
    addInValue(left);
    addInValue(right);
  }

  public NumericType getNumericType() {
    return type;
  }

  public Value leftValue() {
    return inValues.get(0);
  }

  public Value rightValue() {
    return inValues.get(1);
  }

  public abstract boolean isCommutative();

  public boolean isTwoAddr(DexBuilder builder) {
    if (rightValue().needsRegister() && leftValue().needsRegister()) {
      int leftRegister = builder.allocatedRegister(leftValue(), getNumber());
      int rightRegister = builder.allocatedRegister(rightValue(), getNumber());
      int destRegister = builder.allocatedRegister(outValue, getNumber());
      return ((leftRegister == destRegister) ||
          (isCommutative() && rightRegister == destRegister)) &&
          leftRegister <= U4BIT_MAX &&
          rightRegister <= U4BIT_MAX;
    }
    return false;
  }

  boolean fitsInDexInstruction(Value value) {
    return fitsInLit16Instruction(value);
  }

  boolean fitsInLit16Instruction(Value value) {
    return type == NumericType.INT &&
        value.isConstant() &&
        value.getConstInstruction().asConstNumber().is16Bit();
  }

  boolean fitsInLit8Instruction(Value value) {
    return type == NumericType.INT &&
        value.isConstant() &&
        value.getConstInstruction().asConstNumber().is8Bit();
  }

  // The in and out register sizes are the same depending on the size of the literal
  // involved (is any).
  int maxInOutValueRegisterSize() {
    if (fitsInDexInstruction(rightValue())) {
      return rightValue().getConstInstruction().asConstNumber().is8Bit() ? U8BIT_MAX : U4BIT_MAX;
    }
    return U8BIT_MAX;
  }

  @Override
  public int maxInValueRegister() {
    return maxInOutValueRegisterSize();
  }

  @Override
  public int maxOutValueRegister() {
    return maxInOutValueRegisterSize();
  }

  int foldIntegers(int left, int right) {
    throw new Unreachable("Unsupported integer folding for " + this);
  }

  long foldLongs(long left, long right) {
    throw new Unreachable("Unsupported long folding for " + this);
  }

  float foldFloat(float left, float right) {
    throw new Unreachable("Unsupported float folding for " + this);
  }

  double foldDouble(double left, double right) {
    throw new Unreachable("Unsupported float folding for " + this);
  }

  @Override
  public boolean isBinop() {
    return true;
  }

  @Override
  public Binop asBinop() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.ALWAYS;
  }
}
