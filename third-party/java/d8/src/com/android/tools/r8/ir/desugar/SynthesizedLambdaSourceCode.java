// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.ir.synthetic.SingleBlockSourceCode;

// Represents source code of synthesized lambda class methods.
abstract class SynthesizedLambdaSourceCode extends SingleBlockSourceCode {

  final DexMethod currentMethod;
  final LambdaClass lambda;

  SynthesizedLambdaSourceCode(LambdaClass lambda, DexMethod currentMethod, DexType receiver) {
    super(receiver, currentMethod.proto);
    this.lambda = lambda;
    this.currentMethod = currentMethod;
  }

  SynthesizedLambdaSourceCode(LambdaClass lambda, DexMethod currentMethod) {
    this(lambda, currentMethod, lambda.type);
  }

  final LambdaDescriptor descriptor() {
    return lambda.descriptor;
  }

  final DexType[] captures() {
    DexTypeList captures = descriptor().captures;
    assert captures != null;
    return captures.values;
  }

  final DexItemFactory factory() {
    return lambda.rewriter.factory;
  }

  final int enforceParameterType(int register, DexType paramType, DexType enforcedType) {
    // `paramType` must be either same as `enforcedType` or both must be class
    // types and `enforcedType` must be a subclass of `paramType` in which case
    // a cast need to be inserted.
    if (paramType != enforcedType) {
      assert LambdaDescriptor.isSameOrDerived(factory(), enforcedType, paramType);
      add(builder -> builder.addCheckCast(register, enforcedType));
    }
    return register;
  }

  @Override
  public String toString() {
    return currentMethod.toSourceString();
  }
}

