// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.NotInt;
import com.android.tools.r8.code.NotLong;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.conversion.DexBuilder;

public class Not extends Unop {

  public final NumericType type;

  public Not(NumericType type, Value dest, Value source) {
    super(dest, source);
    this.type = type;
  }

  @Override
  public boolean canBeFolded() {
    return source().isConstant();
  }

  @Override
  public ConstInstruction fold(IRCode code) {
    assert canBeFolded();
    ValueType valueType = ValueType.fromNumericType(type);
    if (type == NumericType.INT) {
      int result = ~(source().getConstInstruction().asConstNumber().getIntValue());
      Value value = code.createValue(valueType, getLocalInfo());
      return new ConstNumber(value, result);
    } else {
      assert type == NumericType.LONG;
      long result = ~source().getConstInstruction().asConstNumber().getLongValue();
      Value value = code.createValue(valueType, getLocalInfo());
      return new ConstNumber(value, result);
    }
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.android.tools.r8.code.Instruction instruction;
    int dest = builder.allocatedRegister(dest(), getNumber());
    int src = builder.allocatedRegister(source(), getNumber());
    switch (type) {
      case INT:
        instruction = new NotInt(dest, src);
        break;
      case LONG:
        instruction = new NotLong(dest, src);
        break;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
    builder.add(this, instruction);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asNot().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.ordinal() - other.asNot().type.ordinal();
  }

  @Override
  public boolean isNot() {
    return true;
  }

  @Override
  public Not asNot() {
    return this;
  }
}
