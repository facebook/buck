// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.graph.DexMethod;
import com.google.common.base.Equivalence;

/**
 * Implements an equivalence on {@link DexMethod} that does not take the holder into account.
 *
 * <p>Useful when comparing method implementations by their signature only.
 */
public class MethodSignatureEquivalence extends Equivalence<DexMethod> {

  private static final MethodSignatureEquivalence THEINSTANCE = new MethodSignatureEquivalence();

  private MethodSignatureEquivalence() {
  }

  public static MethodSignatureEquivalence get() {
    return THEINSTANCE;
  }

  @Override
  protected boolean doEquivalent(DexMethod a, DexMethod b) {
    return a.name.equals(b.name) && a.proto.equals(b.proto);
  }

  @Override
  protected int doHash(DexMethod method) {
    return method.name.hashCode() * 31 + method.proto.hashCode();
  }
}
