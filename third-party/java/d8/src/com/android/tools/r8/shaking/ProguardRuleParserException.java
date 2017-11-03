// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

public class ProguardRuleParserException extends Exception {
  public ProguardRuleParserException(String message) {
    super(message);
  }

  public ProguardRuleParserException(String message, String snippet) {
    this(message, snippet, null);
  }

  public ProguardRuleParserException(String message, String snippet, Throwable cause) {
    super(message + " at " + snippet, cause);
  }
}
