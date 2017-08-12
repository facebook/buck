// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;

public class ProguardMapError extends CompilationError {
  private ProguardMapError(String message) {
    super(message);
  }

  private ProguardMapError(String message, Throwable cause) {
    super(message, cause);
  }

  static ProguardMapError keptTypeWasRenamed(DexType type, String keptName, String rename) {
    return new ProguardMapError(
        "Warning: " + type + createMessageForConflict(keptName, rename));
  }

  static ProguardMapError keptMethodWasRenamed(DexMethod method, String keptName, String rename) {
    return new ProguardMapError(
        "Warning: " + method.toSourceString() + createMessageForConflict(keptName, rename));
  }

  static ProguardMapError keptFieldWasRenamed(DexField field, String keptName, String rename) {
    return new ProguardMapError(
        "Warning: " + field.toSourceString() + createMessageForConflict(keptName, rename));
  }

  private static String createMessageForConflict(String keptName, String rename) {
    return " is not being kept as '" + keptName + "', but remapped to '" + rename + "'";
  }
}
