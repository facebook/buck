// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.errors.InternalCompilerError;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexType;

public enum MemberType {
  OBJECT,
  BOOLEAN,
  BYTE,
  CHAR,
  SHORT,
  INT,
  FLOAT,
  LONG,
  DOUBLE,
  INT_OR_FLOAT,
  LONG_OR_DOUBLE;

  public static MemberType fromTypeDescriptorChar(char descriptor) {
    switch (descriptor) {
      case 'L':
      case '[':
        return MemberType.OBJECT;
      case 'Z':
        return MemberType.BOOLEAN;
      case 'B':
        return MemberType.BYTE;
      case 'S':
        return MemberType.SHORT;
      case 'C':
        return MemberType.CHAR;
      case 'I':
        return MemberType.INT;
      case 'F':
        return MemberType.FLOAT;
      case 'J':
        return MemberType.LONG;
      case 'D':
        return MemberType.DOUBLE;
      case 'V':
        throw new InternalCompilerError("No member type for void type.");
      default:
        throw new Unreachable("Invalid descriptor char '" + descriptor + "'");
    }
  }

  public static MemberType fromDexType(DexType type) {
    return fromTypeDescriptorChar((char) type.descriptor.content[0]);
  }
}
