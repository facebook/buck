// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.Resource.Origin;

/**
 * Interface for all diagnostic message produced by D8 and R8.
 */
public interface Diagnostic {
  Origin getOrigin();

  String getDiagnosticMessage();
}
