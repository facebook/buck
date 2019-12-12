/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.abi;

import org.objectweb.asm.signature.SignatureVisitor;

/**
 * A {@link SignatureVisitor} that chains to another one. Most of the other visitors in ASM can
 * already do this; {@link SignatureVisitor} is an exception for some reason.
 */
class SignatureVisitorWrapper extends SignatureVisitor {
  private final SignatureVisitor sv;

  public SignatureVisitorWrapper(int api, SignatureVisitor sv) {
    super(api);
    this.sv = sv;
  }

  @Override
  public void visitFormalTypeParameter(String name) {
    sv.visitFormalTypeParameter(name);
  }

  @Override
  public SignatureVisitor visitClassBound() {
    return sv.visitClassBound();
  }

  @Override
  public SignatureVisitor visitInterfaceBound() {
    return sv.visitInterfaceBound();
  }

  @Override
  public SignatureVisitor visitSuperclass() {
    return sv.visitSuperclass();
  }

  @Override
  public SignatureVisitor visitInterface() {
    return sv.visitInterface();
  }

  @Override
  public SignatureVisitor visitParameterType() {
    return sv.visitParameterType();
  }

  @Override
  public SignatureVisitor visitReturnType() {
    return sv.visitReturnType();
  }

  @Override
  public SignatureVisitor visitExceptionType() {
    return sv.visitExceptionType();
  }

  @Override
  public void visitBaseType(char descriptor) {
    sv.visitBaseType(descriptor);
  }

  @Override
  public void visitTypeVariable(String name) {
    sv.visitTypeVariable(name);
  }

  @Override
  public SignatureVisitor visitArrayType() {
    return sv.visitArrayType();
  }

  @Override
  public void visitClassType(String name) {
    sv.visitClassType(name);
  }

  @Override
  public void visitInnerClassType(String name) {
    sv.visitInnerClassType(name);
  }

  @Override
  public void visitTypeArgument() {
    sv.visitTypeArgument();
  }

  @Override
  public SignatureVisitor visitTypeArgument(char wildcard) {
    return sv.visitTypeArgument(wildcard);
  }

  @Override
  public void visitEnd() {
    sv.visitEnd();
  }
}
