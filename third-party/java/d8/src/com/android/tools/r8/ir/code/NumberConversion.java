// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.DoubleToFloat;
import com.android.tools.r8.code.DoubleToInt;
import com.android.tools.r8.code.DoubleToLong;
import com.android.tools.r8.code.FloatToDouble;
import com.android.tools.r8.code.FloatToInt;
import com.android.tools.r8.code.FloatToLong;
import com.android.tools.r8.code.IntToByte;
import com.android.tools.r8.code.IntToChar;
import com.android.tools.r8.code.IntToDouble;
import com.android.tools.r8.code.IntToFloat;
import com.android.tools.r8.code.IntToLong;
import com.android.tools.r8.code.IntToShort;
import com.android.tools.r8.code.LongToDouble;
import com.android.tools.r8.code.LongToFloat;
import com.android.tools.r8.code.LongToInt;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.conversion.DexBuilder;

public class NumberConversion extends Unop {

  public final NumericType from;
  public final NumericType to;

  public NumberConversion(NumericType from, NumericType to, Value dest, Value source) {
    super(dest, source);
    this.from = from;
    this.to = to;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.android.tools.r8.code.Instruction instruction;
    int dest = builder.allocatedRegister(dest(), getNumber());
    int src = builder.allocatedRegister(source(), getNumber());
    switch (from) {
      case INT:
        switch (to) {
          case BYTE:
            instruction = new IntToByte(dest, src);
            break;
          case CHAR:
            instruction = new IntToChar(dest, src);
            break;
          case SHORT:
            instruction = new IntToShort(dest, src);
            break;
          case LONG:
            instruction = new IntToLong(dest, src);
            break;
          case FLOAT:
            instruction = new IntToFloat(dest, src);
            break;
          case DOUBLE:
            instruction = new IntToDouble(dest, src);
            break;
          default:
            throw new Unreachable("Unexpected types " + from + ", " + to);
        }
        break;
      case LONG:
        switch (to) {
          case INT:
            instruction = new LongToInt(dest, src);
            break;
          case FLOAT:
            instruction = new LongToFloat(dest, src);
            break;
          case DOUBLE:
            instruction = new LongToDouble(dest, src);
            break;
          default:
            throw new Unreachable("Unexpected types " + from + ", " + to);
        }
        break;
      case FLOAT:
        switch (to) {
          case INT:
            instruction = new FloatToInt(dest, src);
            break;
          case LONG:
            instruction = new FloatToLong(dest, src);
            break;
          case DOUBLE:
            instruction = new FloatToDouble(dest, src);
            break;
          default:
            throw new Unreachable("Unexpected types " + from + ", " + to);
        }
        break;
      case DOUBLE:
        switch (to) {
          case INT:
            instruction = new DoubleToInt(dest, src);
            break;
          case LONG:
            instruction = new DoubleToLong(dest, src);
            break;
          case FLOAT:
            instruction = new DoubleToFloat(dest, src);
            break;
          default:
            throw new Unreachable("Unexpected types " + from + ", " + to);
        }
        break;
      default:
        throw new Unreachable("Unexpected types " + from + ", " + to);
    }
    builder.add(this, instruction);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    NumberConversion o = other.asNumberConversion();
    return o.from == from && o.to == to;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    NumberConversion o = other.asNumberConversion();
    int result;
    result = from.ordinal() - o.from.ordinal();
    if (result != 0) {
      return result;
    }
    return to.ordinal() - o.to.ordinal();
  }

  @Override
  public boolean isNumberConversion() {
    return true;
  }

  @Override
  public NumberConversion asNumberConversion() {
    return this;
  }
}
