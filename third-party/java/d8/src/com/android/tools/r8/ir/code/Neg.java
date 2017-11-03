// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.NegDouble;
import com.android.tools.r8.code.NegFloat;
import com.android.tools.r8.code.NegInt;
import com.android.tools.r8.code.NegLong;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.conversion.DexBuilder;

public class Neg extends Unop {

  public final NumericType type;

  public Neg(NumericType type, Value dest, Value source) {
    super(dest, source);
    this.type = type;
  }

  @Override
  public boolean canBeFolded() {
    return (type == NumericType.INT || type == NumericType.LONG || type == NumericType.FLOAT
            || type == NumericType.DOUBLE)
        && source().isConstant();
  }

  @Override
  public ConstInstruction fold(IRCode code) {
    assert canBeFolded();
    ValueType valueType = ValueType.fromNumericType(type);
    if (type == NumericType.INT) {
      int result = -source().getConstInstruction().asConstNumber().getIntValue();
      Value value = code.createValue(valueType, getLocalInfo());
      return new ConstNumber(value, result);
    } else if (type == NumericType.LONG) {
      long result = -source().getConstInstruction().asConstNumber().getLongValue();
      Value value = code.createValue(valueType, getLocalInfo());
      return new ConstNumber(value, result);
    } else if (type == NumericType.FLOAT) {
      float result = -source().getConstInstruction().asConstNumber().getFloatValue();
      Value value = code.createValue(valueType, getLocalInfo());
      return new ConstNumber(value, Float.floatToIntBits(result));
    } else {
      assert type == NumericType.DOUBLE;
      double result = -source().getConstInstruction().asConstNumber().getDoubleValue();
      Value value = code.createValue(valueType, getLocalInfo());
      return new ConstNumber(value, Double.doubleToLongBits(result));
    }
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asNeg().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.ordinal() - other.asNeg().type.ordinal();
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.android.tools.r8.code.Instruction instruction;
    int dest = builder.allocatedRegister(dest(), getNumber());
    int src = builder.allocatedRegister(source(), getNumber());
    switch (type) {
      case INT:
        instruction = new NegInt(dest, src);
        break;
      case LONG:
        instruction = new NegLong(dest, src);
        break;
      case FLOAT:
        instruction = new NegFloat(dest, src);
        break;
      case DOUBLE:
        instruction = new NegDouble(dest, src);
        break;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
    builder.add(this, instruction);
  }

  @Override
  public boolean isNeg() {
    return true;
  }

  @Override
  public Neg asNeg() {
    return this;
  }
}
