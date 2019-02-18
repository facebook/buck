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
package com.facebook.buck.intellij.ideabuck.navigation;

import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgTypes;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandlerBase;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Navigation to files inlined from {@code .buckconfig} files. */
public class BcfgGotoFileProvider extends GotoDeclarationHandlerBase {

  @Nullable
  @Override
  public PsiElement getGotoDeclarationTarget(@Nullable PsiElement psiElement, Editor editor) {
    if (psiElement == null) {
      return null;
    }
    if (BcfgTypes.FILE_PATH.equals(psiElement.getNode().getElementType())) {
      return getGotoFilePath(psiElement);
    }
    return null;
  }

  @Nullable
  private PsiElement getGotoFilePath(PsiElement psiElement) {
    return Optional.of(psiElement)
        .filter(e -> BcfgTypes.FILE_PATH.equals(e.getNode().getElementType()))
        .map(PsiElement::getContainingFile)
        .map(PsiFile::getVirtualFile)
        .map(VirtualFile::getParent)
        .map(vf -> vf.findFileByRelativePath(psiElement.getText()))
        .map(PsiManager.getInstance(psiElement.getProject())::findFile)
        .orElse(null);
  }

  @Nullable
  @Override
  public String getActionText(@NotNull DataContext context) {
    return null;
  }
}
