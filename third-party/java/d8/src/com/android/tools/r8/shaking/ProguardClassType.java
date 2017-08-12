// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.errors.Unreachable;

public enum ProguardClassType {
  ANNOTATION_INTERFACE,
  CLASS,
  ENUM,
  INTERFACE;

  @Override
  public String toString() {
    switch (this) {
      case ANNOTATION_INTERFACE: return "@interface";
      case CLASS: return "class";
      case ENUM: return "enum";
      case INTERFACE: return "interface";
      default:
        throw new Unreachable("Invalid proguard class type '" + this + "'");
    }
  }
}
