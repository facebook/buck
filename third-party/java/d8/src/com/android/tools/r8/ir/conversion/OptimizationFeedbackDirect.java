// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;

public class OptimizationFeedbackDirect implements OptimizationFeedback {

  @Override
  public void methodReturnsArgument(DexEncodedMethod method, int argument) {
    method.markReturnsArgument(argument);
  }

  @Override
  public void methodReturnsConstant(DexEncodedMethod method, long value) {
    method.markReturnsConstant(value);
  }

  @Override
  public void methodNeverReturnsNull(DexEncodedMethod method) {
    method.markNeverReturnsNull();
  }

  @Override
  public void markProcessed(DexEncodedMethod method, Constraint state) {
    method.markProcessed(state);
  }
}
