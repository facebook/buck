// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.conversion.IRBuilder;
import java.util.Collections;

// Source code representing synthesized lambda constructor.
final class LambdaConstructorSourceCode extends SynthesizedLambdaSourceCode {

  LambdaConstructorSourceCode(LambdaClass lambda) {
    super(lambda, lambda.constructor);
  }

  @Override
  protected void prepareInstructions() {
    // Super constructor call (always java.lang.Object.<init>()).
    DexMethod objectInitMethod = lambda.rewriter.objectInitMethod;
    add(builder -> builder.addInvoke(Invoke.Type.DIRECT, objectInitMethod,
        objectInitMethod.proto, Collections.singletonList(getReceiverValue())));

    // Assign capture fields.
    DexType[] capturedTypes = captures();
    int capturedValues = capturedTypes.length;
    if (capturedValues > 0) {
      for (int i = 0; i < capturedValues; i++) {
        DexField field = lambda.getCaptureField(i);
        int idx = i;
        add(builder -> builder.addInstancePut(getParamRegister(idx), getReceiverRegister(), field));
      }
    }

    // Final return.
    add(IRBuilder::addReturn);
  }

  @Override
  public int hashCode() {
    // We want all zero-parameter constructor source code instances to
    // be treated as equal, since it only has one call to super constructor,
    // which is always java.lang.Object.<init>().
    return captures().length == 0
        ? System.identityHashCode(lambda.rewriter.objectInitMethod) : super.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof LambdaConstructorSourceCode)) {
      return false;
    }
    if (captures().length == 0) {
      // See comment in hashCode().
      return ((LambdaConstructorSourceCode) obj).captures().length == 0;
    }
    return super.equals(obj);
  }
}
