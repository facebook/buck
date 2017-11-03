// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

public class DelegatingUseRegistry extends UseRegistry {
    private final UseRegistry delegate;

    public DelegatingUseRegistry(UseRegistry delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean registerInvokeVirtual(DexMethod method) {
      return delegate.registerInvokeVirtual(method);
    }

    @Override
    public boolean registerInvokeDirect(DexMethod method) {
      return delegate.registerInvokeDirect(method);
    }

    @Override
    public boolean registerInvokeStatic(DexMethod method) {
      return delegate.registerInvokeStatic(method);
    }

    @Override
    public boolean registerInvokeInterface(DexMethod method) {
      return delegate.registerInvokeInterface(method);
    }

    @Override
    public boolean registerInvokeSuper(DexMethod method) {
      return delegate.registerInvokeSuper(method);
    }

    @Override
    public boolean registerInstanceFieldWrite(DexField field) {
      return delegate.registerInstanceFieldWrite(field);
    }

    @Override
    public boolean registerInstanceFieldRead(DexField field) {
      return delegate.registerInstanceFieldRead(field);
    }

    @Override
    public boolean registerNewInstance(DexType type) {
      return delegate.registerNewInstance(type);
    }

    @Override
    public boolean registerStaticFieldRead(DexField field) {
      return delegate.registerStaticFieldRead(field);
    }

    @Override
    public boolean registerStaticFieldWrite(DexField field) {
      return delegate.registerStaticFieldWrite(field);
    }

    @Override
    public boolean registerTypeReference(DexType type) {
      return delegate.registerTypeReference(type);
    }

}
