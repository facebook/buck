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

import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.facebook.buck.intellij.ideabuck.external.IntellijBuckAction;
import com.facebook.buck.intellij.ideabuck.lang.BuckLanguage;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadTargetArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPsiUtils;
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
  public PsiElement getGotoDeclarationTarget(@Nullable PsiElement element, Editor editor) {
    Project project = editor.getProject();
    if (project == null || project.isDefault()) {
      return null;
    }
    if (element == null || !(element.getLanguage() instanceof BuckLanguage)) {
      return null;
    }
    String elementAsString = BuckPsiUtils.getStringValueFromBuckString(element);
    if (elementAsString == null) {
      return null;
    }
    BuckTargetPattern relativePattern = BuckTargetPattern.parse(elementAsString).orElse(null);
    if (relativePattern == null) {
      return null;
    }

    BuckTargetPattern pattern;
    if (relativePattern.isAbsolute()) {
      pattern = relativePattern;
    } else {
      pattern =
          Optional.of(element.getContainingFile())
              .map(PsiFile::getVirtualFile)
              .flatMap(vf -> BuckTargetLocator.getInstance(project).resolve(vf, relativePattern))
              .orElse(relativePattern);
    }
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    PsiElement psiTarget = null;
    BuckTarget target = pattern.asBuckTarget().orElse(null);
    if (target != null
        && PsiTreeUtil.getParentOfType(element, BuckLoadTargetArgument.class) != null) {
      // BuckLoadTargetArguments can only be targets, not patterns
      psiTarget =
          buckTargetLocator
              .findVirtualFileForExtensionFile(target)
              .filter(VirtualFile::exists)
              .map(PsiManager.getInstance(project)::findFile)
              .orElse(null);
    } else if (target != null) {
      // If it is a target, try to navigate to the element where the rule is defined...
      psiTarget = buckTargetLocator.findElementForTarget(target).orElse(null);
      if (psiTarget == null) {
        // ...otherwise just navigate to the file.
        psiTarget =
            buckTargetLocator
                .findVirtualFileForTarget(target)
                .map(file -> (PsiElement) PsiManager.getInstance(project).findFile(file))
                .orElse(null);
      }
    }
    if (psiTarget == null) {
      // If we couldn't resolve as target, try resolving it as pattern
      psiTarget =
          buckTargetLocator
              .findVirtualFileForTargetPattern(pattern)
              .map(
                  vf -> {
                    if (vf.isDirectory()) {
                      return PsiManager.getInstance(project).findDirectory(vf);
                    } else {
                      return PsiManager.getInstance(project).findFile(vf);
                    }
                  })
              .orElse(null);
    }
    if (psiTarget != null) {
      project
          .getMessageBus()
          .syncPublisher(IntellijBuckAction.EVENT)
          .consume(this.getClass().toString());
    }
    return psiTarget;
  }
}
