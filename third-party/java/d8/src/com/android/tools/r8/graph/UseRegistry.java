// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

public abstract class UseRegistry {

  public abstract boolean registerInvokeVirtual(DexMethod method);

  public abstract boolean registerInvokeDirect(DexMethod method);

  public abstract boolean registerInvokeStatic(DexMethod method);

  public abstract boolean registerInvokeInterface(DexMethod method);

  public abstract boolean registerInvokeSuper(DexMethod method);

  public abstract boolean registerInstanceFieldWrite(DexField field);

  public abstract boolean registerInstanceFieldRead(DexField field);

  public abstract boolean registerNewInstance(DexType type);

  public abstract boolean registerStaticFieldRead(DexField field);

  public abstract boolean registerStaticFieldWrite(DexField field);

  public abstract boolean registerTypeReference(DexType type);

  public void registerMethodHandle(DexMethodHandle methodHandle) {
    switch (methodHandle.type) {
      case INSTANCE_GET:
        registerInstanceFieldRead(methodHandle.asField());
        break;
      case INSTANCE_PUT:
        registerInstanceFieldWrite(methodHandle.asField());
        break;
      case STATIC_GET:
        registerStaticFieldRead(methodHandle.asField());
        break;
      case STATIC_PUT:
        registerStaticFieldWrite(methodHandle.asField());
        break;
      case INVOKE_INSTANCE:
        registerInvokeVirtual(methodHandle.asMethod());
        break;
      case INVOKE_STATIC:
        registerInvokeStatic(methodHandle.asMethod());
        break;
      case INVOKE_CONSTRUCTOR:
        DexMethod method = methodHandle.asMethod();
        registerNewInstance(method.getHolder());
        registerInvokeDirect(method);
        break;
      case INVOKE_INTERFACE:
        registerInvokeInterface(methodHandle.asMethod());
        break;
      case INVOKE_SUPER:
        registerInvokeSuper(methodHandle.asMethod());
        break;
      case INVOKE_DIRECT:
        registerInvokeDirect(methodHandle.asMethod());
        break;
      default:
        throw new AssertionError();
    }
  }

  public boolean registerConstClass(DexType type) {
    return registerTypeReference(type);
  }
}
