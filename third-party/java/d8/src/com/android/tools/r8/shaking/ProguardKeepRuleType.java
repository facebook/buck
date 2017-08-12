// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.errors.Unreachable;

public enum ProguardKeepRuleType {
  KEEP,
  KEEP_CLASS_MEMBERS,
  KEEP_CLASSES_WITH_MEMBERS;

  @Override
  public String toString() {
    switch (this) {
      case KEEP:
        return "keep";
      case KEEP_CLASS_MEMBERS:
        return "keepclassmembers";
      case KEEP_CLASSES_WITH_MEMBERS:
        return "keepclasseswithmembers";
      default:
        throw new Unreachable("Unknown ProguardKeepRuleType.");
    }
  }
}
