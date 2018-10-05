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

import com.facebook.buck.intellij.ideabuck.external.IntellijBuckAction;
import com.facebook.buck.intellij.ideabuck.lang.BuckLanguage;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadTargetArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPsiUtils;
import com.facebook.buck.intellij.ideabuck.util.BuckCellFinder;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandlerBase;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

public class BuckGotoProvider extends GotoDeclarationHandlerBase {

  @Nullable
  @Override
  public PsiElement getGotoDeclarationTarget(@Nullable PsiElement source, Editor editor) {
    if (source == null || !(source.getLanguage() instanceof BuckLanguage)) {
      return null;
    }
    String target = BuckPsiUtils.getStringValueFromBuckString(source);
    if (target == null) {
      return null;
    }

    final Project project = editor.getProject();
    if (project == null) {
      return null;
    }

    PsiFile sourcePsiFile = source.getContainingFile();
    BuckCellFinder buckCellFinder = BuckCellFinder.getInstance(project);
    PsiManager psiManager = PsiManager.getInstance(project);

    PsiElement psiTarget = null;
    if (PsiTreeUtil.getParentOfType(source, BuckLoadTargetArgument.class) != null) {
      psiTarget =
          buckCellFinder
              .findExtensionFile(sourcePsiFile.getVirtualFile(), target)
              .filter(VirtualFile::exists)
              .map(psiManager::findFile)
              .orElse(null);
    } else {
      if (target.startsWith(":")) {
        // ':' prefix means this is a target in the same BUCK file
        psiTarget = BuckPsiUtils.findTargetInPsiTree(sourcePsiFile, target.substring(1));
      } else if (target.contains(":")) {
        // If it contains the ':' character, it's a target in a different BUCK file
        @Nullable
        PsiFile psiFile =
            buckCellFinder
                .findBuckTargetFile(sourcePsiFile.getVirtualFile(), target)
                .filter(VirtualFile::exists)
                .map(psiManager::findFile)
                .orElse(null);
        if (psiFile != null) {
          String targetName = target.substring(target.lastIndexOf(':') + 1);
          psiTarget = BuckPsiUtils.findTargetInPsiTree(psiFile, targetName);
          if (psiTarget == null) {
            psiTarget = psiFile;
          }
        }
      } else {
        // If there's no ':' character, try to find file relative to the current file
        psiTarget = findLocalFile(source, target).map(psiManager::findFile).orElse(null);
      }
    }
    if (psiTarget != null) {
      project
          .getMessageBus()
          .syncPublisher(IntellijBuckAction.EVENT)
          .consume(this.getClass().toString());
    }
    return psiTarget;
  }

  private static String unwrapString(String target) {
    if (target.length() >= 2 && target.startsWith("\"") && target.endsWith("\"")) {
      target = target.substring(1, target.length() - 1);
    } else if (target.length() >= 2 && target.startsWith("'") && target.endsWith("'")) {
      target = target.substring(1, target.length() - 1);
    }
    return target;
  }

  private static Optional<VirtualFile> findLocalFile(PsiElement sourceElement, String target) {
    return Optional.of(sourceElement)
        .map(PsiElement::getContainingFile)
        .map(PsiFile::getVirtualFile)
        .map(VirtualFile::getParent)
        .map(parent -> parent.findFileByRelativePath(unwrapString(target)));
  }
}
