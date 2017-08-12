// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;

public interface OptimizationFeedback {
  void methodReturnsArgument(DexEncodedMethod method, int argument);
  void methodReturnsConstant(DexEncodedMethod method, long value);
  void methodNeverReturnsNull(DexEncodedMethod method);
  void markProcessed(DexEncodedMethod method, Constraint state);
}
