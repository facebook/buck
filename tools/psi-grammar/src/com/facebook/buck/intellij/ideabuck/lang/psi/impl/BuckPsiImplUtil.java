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
package com.facebook.buck.intellij.ideabuck.lang.psi.impl;

import com.facebook.buck.intellij.ideabuck.lang.psi.BuckElementFactory;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckIdentifier;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNameIdentifierOwner;
import org.jetbrains.annotations.NotNull;

/** Mixins for {@link com.facebook.buck.intellij.ideabuck.lang.BuckLanguage} elements. */
public class BuckPsiImplUtil {

  // BuckIdentifier mixins

  /** See {@link PsiNameIdentifierOwner#getName()} */
  public static String getName(BuckIdentifier buckIdentifier) {
    return buckIdentifier.getIdentifierToken().getText();
  }

  /** See {@link PsiNameIdentifierOwner#getNameIdentifier()} ()} */
  public static PsiElement getNameIdentifier(BuckIdentifier buckIdentifier) {
    return buckIdentifier.getIdentifierToken();
  }

  /** See {@link PsiNameIdentifierOwner#setName(String)} */
  public static BuckIdentifier setName(BuckIdentifier buckIdentifier, @NotNull String newName) {
    BuckIdentifier tempIdentifier =
        BuckElementFactory.createElement(
            buckIdentifier.getProject(), newName, BuckIdentifier.class);
    PsiElement oldToken = buckIdentifier.getIdentifierToken();
    PsiElement newToken = tempIdentifier.getIdentifierToken();
    buckIdentifier.getNode().replaceChild(oldToken.getNode(), newToken.getNode());
    return buckIdentifier;
  }
}
