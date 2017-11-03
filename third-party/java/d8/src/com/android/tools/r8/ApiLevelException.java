// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.utils.AndroidApiLevel;

/**
 * Exception to signal features that are not supported until a given API level.
 */
public class ApiLevelException extends CompilationException {

  public ApiLevelException(
      AndroidApiLevel minApiLevel, String unsupportedFeatures, String sourceString) {
    super(makeMessage(minApiLevel, unsupportedFeatures, sourceString));
    assert minApiLevel != null;
    assert unsupportedFeatures != null;
  }

  private static String makeMessage(
      AndroidApiLevel minApiLevel, String unsupportedFeatures, String sourceString) {
    String message =
        unsupportedFeatures
            + " are only supported starting with "
            + minApiLevel.getName()
            + " (--min-api "
            + minApiLevel.getLevel()
            + ")";
    message = (sourceString != null) ? message + ": " + sourceString : message;
    return message;
  }
}