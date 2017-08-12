// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.optimize;

import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.UseRegistry;

public class InvokeSingleTargetExtractor extends UseRegistry {
  private InvokeKind kind = InvokeKind.NONE;
  private DexMethod target;

  public InvokeSingleTargetExtractor() {
  }

  private boolean setTarget(DexMethod target, InvokeKind kind) {
    if (this.kind != InvokeKind.NONE) {
      this.kind = InvokeKind.ILLEGAL;
      this.target = null;
    } else {
      assert this.target == null;
      this.target = target;
      this.kind = kind;
    }
    return false;
  }

  private boolean invalid() {
    kind = InvokeKind.ILLEGAL;
    return false;
  }

  public DexMethod getTarget() {
    return target;
  }

  public InvokeKind getKind() {
    return kind;
  }

  @Override
  public boolean registerInvokeVirtual(DexMethod method) {
    return setTarget(method, InvokeKind.VIRTUAL);
  }

  @Override
  public boolean registerInvokeDirect(DexMethod method) {
    return invalid();
  }

  @Override
  public boolean registerInvokeStatic(DexMethod method) {
    return setTarget(method, InvokeKind.STATIC);
  }

  @Override
  public boolean registerInvokeInterface(DexMethod method) {
    return invalid();
  }

  @Override
  public boolean registerInvokeSuper(DexMethod method) {
    return setTarget(method, InvokeKind.SUPER);
  }

  @Override
  public boolean registerInstanceFieldWrite(DexField field) {
    return invalid();
  }

  @Override
  public boolean registerInstanceFieldRead(DexField field) {
    return invalid();
  }

  @Override
  public boolean registerNewInstance(DexType type) {
    return invalid();
  }

  @Override
  public boolean registerStaticFieldRead(DexField field) {
    return invalid();
  }

  @Override
  public boolean registerStaticFieldWrite(DexField field) {
    return invalid();
  }

  @Override
  public boolean registerTypeReference(DexType type) {
    return invalid();
  }

  public enum InvokeKind {
    VIRTUAL,
    STATIC,
    SUPER,
    ILLEGAL,
    NONE
  }
}
