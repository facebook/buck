// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.synthetic;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.android.tools.r8.ir.conversion.SourceCode;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.InternalOptions;
import java.util.function.Consumer;

public final class SynthesizedCode extends Code {

  private final SourceCode sourceCode;
  private final Consumer<UseRegistry> registryCallback;

  public SynthesizedCode(SourceCode sourceCode) {
    this.sourceCode = sourceCode;
    this.registryCallback = SynthesizedCode::registerReachableDefinitionsDefault;
  }

  public SynthesizedCode(SourceCode sourceCode, Consumer<UseRegistry> callback) {
    this.sourceCode = sourceCode;
    this.registryCallback = callback;
  }

  @Override
  public final IRCode buildIR(DexEncodedMethod encodedMethod, InternalOptions options)
      throws ApiLevelException {
    return new IRBuilder(encodedMethod, sourceCode, options).build();
  }

  @Override
  public final String toString() {
    return toString(null, null);
  }

  private static void registerReachableDefinitionsDefault(UseRegistry registry) {
    throw new Unreachable();
  }

  @Override
  public void registerReachableDefinitions(UseRegistry registry) {
    registryCallback.accept(registry);
  }

  @Override
  protected final int computeHashCode() {
    return sourceCode.hashCode();
  }

  @Override
  protected final boolean computeEquals(Object other) {
    return other instanceof SynthesizedCode &&
        this.sourceCode.equals(((SynthesizedCode) other).sourceCode);
  }

  @Override
  public final String toString(DexEncodedMethod method, ClassNameMapper naming) {
    return "SynthesizedCode: " + sourceCode.toString();
  }
}
