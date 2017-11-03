// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexType;

public enum NumericType {
  BYTE,
  CHAR,
  SHORT,
  INT,
  LONG,
  FLOAT,
  DOUBLE;

  public DexType dexTypeFor(DexItemFactory factory) {
    switch (this) {
      case BYTE:
        return factory.byteType;
      case CHAR:
        return factory.charType;
      case SHORT:
        return factory.shortType;
      case INT:
        return factory.intType;
      case LONG:
        return factory.longType;
      case FLOAT:
        return factory.floatType;
      case DOUBLE:
        return factory.doubleType;
      default:
        throw new Unreachable("Invalid numeric type '" + this + "'");
    }
  }

  public static NumericType fromDexType(DexType type) {
    switch (type.descriptor.content[0]) {
      case 'B':  // byte
        return NumericType.BYTE;
      case 'S':  // short
        return NumericType.SHORT;
      case 'C':  // char
        return NumericType.CHAR;
      case 'I':  // int
        return NumericType.INT;
      case 'F':  // float
        return NumericType.FLOAT;
      case 'J':  // long
        return NumericType.LONG;
      case 'D':  // double
        return NumericType.DOUBLE;
      default:
        return null;
    }
  }
}
