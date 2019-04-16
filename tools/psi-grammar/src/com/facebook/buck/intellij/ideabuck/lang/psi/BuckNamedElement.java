/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.intellij.ideabuck.lang.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * See
 * http://www.jetbrains.org/intellij/sdk/docs/reference_guide/custom_language_support/references_and_resolve.html
 */
public interface BuckNamedElement extends PsiNameIdentifierOwner {

  /*
   * Implementation note:
   * Normally, one would just leave these methods unimplemented.
   * However, because this is built using two-pass generation, the
   * first pass needs an implementation (subclasses will not have it
   * defined yet), and the second pass will actually fill in
   * subclass implementations.
   *
   * A future improvement to the build would be to have two versions
   * of this file: one with a default implementation (like this) for
   * the first pass and one without an implementation for the second
   * pass so that subclasses without an implementation fail at compile
   * time.
   */

  @Override
  default String getName() {
    throw new AssertionError("Forgot to implement " + getClass().getName() + ".getName()!");
  }

  @Override
  default @Nullable PsiElement getNameIdentifier() {
    throw new AssertionError(
        "Forgot to implement " + getClass().getName() + ".getNameIdentifier()!");
  }

  @Override
  default PsiElement setName(@NotNull String s) throws IncorrectOperationException {
    throw new AssertionError("Forgot to implement " + getClass().getName() + ".setName(String)!");
  }
}
