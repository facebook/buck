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
import com.facebook.buck.intellij.ideabuck.lang.BuckLanguage;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadTargetArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPsiUtils;
import com.google.common.annotations.VisibleForTesting;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandlerBase;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

public class BuckGotoProvider extends GotoDeclarationHandlerBase {

  @Nullable
  @Override
  public PsiElement getGotoDeclarationTarget(@Nullable PsiElement element, Editor unused) {
    return getGotoDeclarationTarget(element);
  }

  @VisibleForTesting
  PsiElement getGotoDeclarationTarget(@Nullable PsiElement element) {
    if (element == null || !(element.getLanguage() instanceof BuckLanguage)) {
      return null;
    }
    Project project = element.getProject();
    if (project.isDefault()) {
      return null;
    }
    String elementAsString = BuckPsiUtils.getStringValueFromBuckString(element);
    if (elementAsString == null) {
      return null;
    }
    VirtualFile sourceFile = element.getContainingFile().getVirtualFile();
    if (sourceFile == null) {
      return null;
    }
    Optional<BuckTargetPattern> targetPattern = BuckTargetPattern.parse(elementAsString);
    if (targetPattern.isPresent()) {
      if (PsiTreeUtil.getParentOfType(element, BuckLoadTargetArgument.class) != null) {
        return resolveAsLoadTarget(project, sourceFile, targetPattern.get());
      } else {
        return targetPattern
            .flatMap(BuckTargetPattern::asBuckTarget)
            .map(t -> resolveAsBuckTarget(project, sourceFile, t))
            .orElseGet(() -> resolveAsBuckTargetPattern(project, sourceFile, targetPattern.get()));
      }
    } else {
      return resolveAsRelativeFile(project, sourceFile, elementAsString);
    }
  }

  @Nullable
  private PsiElement resolveAsLoadTarget(
      Project project, VirtualFile sourceFile, BuckTargetPattern targetPattern) {
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    return targetPattern
        .asBuckTarget()
        .flatMap(target -> buckTargetLocator.resolve(sourceFile, target))
        .flatMap(buckTargetLocator::findVirtualFileForExtensionFile)
        .map(targetFile -> findPsiElementForVirtualFile(project, targetFile))
        .orElse(null);
  }

  @Nullable
  private PsiElement resolveAsBuckTargetPattern(
      Project project, VirtualFile sourceFile, BuckTargetPattern targetPattern) {
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    return buckTargetLocator
        .resolve(sourceFile, targetPattern)
        .flatMap(buckTargetLocator::findVirtualFileForTargetPattern)
        .map(targetFile -> findPsiElementForVirtualFile(project, targetFile))
        .orElse(null);
  }

  @Nullable
  private PsiElement resolveAsBuckTarget(
      Project project, VirtualFile sourceFile, BuckTarget target) {
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    return buckTargetLocator
        .resolve(sourceFile, target)
        .flatMap(buckTargetLocator::findElementForTarget)
        .map(o -> (PsiElement) o)
        .orElse(null);
  }

  @Nullable
  private PsiElement resolveAsRelativeFile(
      Project project, VirtualFile sourceFile, String elementAsString) {
    return Optional.of(sourceFile.getParent())
        .map(f -> f.findFileByRelativePath(elementAsString))
        .map(f -> findPsiElementForVirtualFile(project, f))
        .orElse(null);
  }

  @Nullable
  private PsiElement findPsiElementForVirtualFile(Project project, VirtualFile file) {
    PsiManager psiManager = PsiManager.getInstance(project);
    if (file.isDirectory()) {
      return psiManager.findDirectory(file);
    } else {
      return psiManager.findFile(file);
    }
  }
}
