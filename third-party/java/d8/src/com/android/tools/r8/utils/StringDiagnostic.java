// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.Resource.Origin;

public class StringDiagnostic implements Diagnostic {

  private final Origin origin;
  private final String message;

  public StringDiagnostic(String message) {
    this(message, Origin.unknown());
  }

  public StringDiagnostic(String message, Origin origin) {
    this.origin = origin;
    this.message = message;
  }

  @Override
  public Origin getOrigin() {
    return origin;
  }

  @Override
  public String getDiagnosticMessage() {
    return message;
  }
}
