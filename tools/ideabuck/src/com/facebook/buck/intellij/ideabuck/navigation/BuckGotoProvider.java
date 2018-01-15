/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.intellij.ideabuck.build.BuckBuildUtil;
import com.facebook.buck.intellij.ideabuck.external.IntellijBuckAction;
import com.facebook.buck.intellij.ideabuck.lang.BuckLanguage;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadTargetArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckValue;
import com.google.common.base.Optional;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandlerBase;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

public class BuckGotoProvider extends GotoDeclarationHandlerBase {

  @Override
  public PsiElement getGotoDeclarationTarget(@Nullable PsiElement source, Editor editor) {
    if (source != null && source.getLanguage() instanceof BuckLanguage) {
      @Nullable
      BuckLoadTargetArgument loadTargetArgument =
          PsiTreeUtil.getParentOfType(source, BuckLoadTargetArgument.class);
      if (loadTargetArgument != null) {
        return getExtensionFileElement(loadTargetArgument);
      }

      // The parent type of the element must be BuckValue.
      BuckValue value = PsiTreeUtil.getParentOfType(source, BuckValue.class);
      if (value == null) {
        return null;
      }
      final Project project = editor.getProject();
      if (project == null) {
        return null;
      }

      String target = source.getText();
      if ((target.startsWith("'") && target.endsWith("'"))
          || (target.startsWith("\"") && target.endsWith("\""))) {
        target = target.substring(1, target.length() - 1);
      }

      VirtualFile targetFile =
          // Try to find the BUCK file
          Optional.fromNullable(BuckBuildUtil.getBuckFileFromAbsoluteTarget(project, target))
              // Try to find the normal file
              .or(
                  Optional.fromNullable(
                      source
                          .getContainingFile()
                          .getParent()
                          .getVirtualFile()
                          // If none exist, then it's null
                          .findFileByRelativePath(target)))
              .orNull();

      if (targetFile == null) {
        return null;
      }
      project
          .getMessageBus()
          .syncPublisher(IntellijBuckAction.EVENT)
          .consume(this.getClass().toString());
      return PsiManager.getInstance(project).findFile(targetFile);
    }
    return null;
  }

  @Nullable
  private PsiFile getExtensionFileElement(BuckLoadTargetArgument loadTargetArgument) {
    @Nullable VirtualFile extensionFile = BuckBuildUtil.resolveExtensionFile(loadTargetArgument);
    if (extensionFile != null) {
      return PsiManager.getInstance(loadTargetArgument.getProject()).findFile(extensionFile);
    }
    return null;
  }
}
