// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.CmpLong;
import com.android.tools.r8.code.CmpgDouble;
import com.android.tools.r8.code.CmpgFloat;
import com.android.tools.r8.code.CmplDouble;
import com.android.tools.r8.code.CmplFloat;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.utils.LongInterval;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.StringUtils.BraceType;

public class Cmp extends Binop {

  public enum Bias {
    NONE, GT, LT
  }

  private final Bias bias;

  public Cmp(NumericType type, Bias bias, Value dest, Value left, Value right) {
    super(type, dest, left, right);
    this.bias = bias;
  }

  @Override
  public boolean isCommutative() {
    return false;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.android.tools.r8.code.Instruction instruction;
    int dest = builder.allocatedRegister(outValue, getNumber());
    int left = builder.allocatedRegister(leftValue(), getNumber());
    int right = builder.allocatedRegister(rightValue(), getNumber());
    switch (type) {
      case DOUBLE:
        assert bias != Bias.NONE;
        if (bias == Bias.GT) {
          instruction = new CmpgDouble(dest, left, right);
        } else {
          assert bias == Bias.LT;
          instruction = new CmplDouble(dest, left, right);
        }
        break;
      case FLOAT:
        assert bias != Bias.NONE;
        if (bias == Bias.GT) {
          instruction = new CmpgFloat(dest, left, right);
        } else {
          assert bias == Bias.LT;
          instruction = new CmplFloat(dest, left, right);
        }
        break;
      case LONG:
        assert bias == Bias.NONE;
        instruction = new CmpLong(dest, left, right);
        break;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
    builder.add(this, instruction);
  }

  private String biasToString(Bias bias) {
    switch (bias) {
      case NONE:
        return "none";
      case GT:
        return "gt";
      case LT:
        return "lt";
      default:
        throw new Unreachable("Unexpected bias " + bias);
    }
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append(getClass().getSimpleName());
    builder.append(" (");
    switch (type) {
      case DOUBLE:
        builder.append("double, ");
        builder.append(biasToString(bias));
        break;
      case FLOAT:
        builder.append("float, ");
        builder.append(biasToString(bias));
        break;
      case LONG:
        builder.append("long");
        break;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
    builder.append(")");
    for (int i = builder.length(); i < 20; i++) {
      builder.append(" ");
    }
    if (outValue != null) {
      builder.append(outValue);
      builder.append(" <- ");
    }
    StringUtils.append(builder, inValues, ", ", BraceType.NONE);
    return builder.toString();
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asCmp().bias == bias;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return bias.ordinal() - other.asCmp().bias.ordinal();
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    return Constants.U8BIT_MAX;
  }

  private boolean nonOverlapingRanges() {
    return type == NumericType.LONG
        && leftValue().hasValueRange()
        && rightValue().hasValueRange()
        && leftValue().getValueRange().doesntOverlapWith(rightValue().getValueRange());
  }

  @Override
  public boolean canBeFolded() {
    return (leftValue().isConstNumber() && rightValue().isConstNumber()) || nonOverlapingRanges();
  }

  @Override
  public ConstInstruction fold(IRCode code) {
    assert canBeFolded();
    int result;
    if (type == NumericType.LONG) {
      if (leftValue().isConstNumber() && rightValue().isConstNumber()) {
        long left = leftValue().getConstInstruction().asConstNumber().getLongValue();
        long right = rightValue().getConstInstruction().asConstNumber().getLongValue();
        result = Integer.signum(Long.compare(left, right));
      } else {
        assert nonOverlapingRanges();
        LongInterval leftRange = leftValue().getValueRange();
        LongInterval rightRange = rightValue().getValueRange();
        result = Integer.signum(Long.compare(leftRange.getMin(), rightRange.getMin()));
      }
    } else if (type == NumericType.FLOAT) {
      float left = leftValue().getConstInstruction().asConstNumber().getFloatValue();
      float right = rightValue().getConstInstruction().asConstNumber().getFloatValue();
      if (Float.isNaN(left) || Float.isNaN(right)) {
        result = bias == Bias.GT ? 1 : -1;
      } else {
        result = (int) Math.signum(left - right);
      }
    } else {
      assert type == NumericType.DOUBLE;
      double left = leftValue().getConstInstruction().asConstNumber().getDoubleValue();
      double right = rightValue().getConstInstruction().asConstNumber().getDoubleValue();
      if (Double.isNaN(left) || Double.isNaN(right)) {
        result = bias == Bias.GT ? 1 : -1;
      } else {
        result = (int) Math.signum(left - right);
      }
    }
    assert result == -1 || result == 0 || result == 1;
    Value value = code.createValue(ValueType.INT, getLocalInfo());
    return new ConstNumber(value, result);
  }

  @Override
  public boolean isCmp() {
    return true;
  }

  @Override
  public Cmp asCmp() {
    return this;
  }
}
