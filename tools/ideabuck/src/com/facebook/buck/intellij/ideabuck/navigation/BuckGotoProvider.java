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
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadTargetArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckProperty;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckRuleBlock;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckRuleBody;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckValue;
import com.facebook.buck.intellij.ideabuck.util.BuckCellFinder;
import com.google.common.collect.Iterables;
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

    final Project project = editor.getProject();
    if (project == null) {
      return null;
    }

    PsiFile sourcePsiFile = source.getContainingFile();
    BuckCellFinder buckCellFinder = BuckCellFinder.getInstance(project);
    PsiManager psiManager = PsiManager.getInstance(project);

    String target = unwrapString(source.getText());
    Optional<PsiElement> psiTarget;

    if (PsiTreeUtil.getParentOfType(source, BuckLoadTargetArgument.class) != null) {
      psiTarget =
          buckCellFinder
              .findExtensionFile(sourcePsiFile.getVirtualFile(), target)
              .map(psiManager::findFile);
    } else if (PsiTreeUtil.getParentOfType(source, BuckValue.class) != null) {
      if (target.startsWith(":")) {
        // ':' prefix means this is a target in the same BUCK file
        psiTarget = findTargetInPsiTree(sourcePsiFile, target.substring(1));
      } else if (target.contains(":")) {
        // If it contains the ':' character, it's a target somewhere else
        psiTarget =
            buckCellFinder
                .findBuckTargetFile(sourcePsiFile.getVirtualFile(), target)
                .map(psiManager::findFile)
                .map(
                    psiFile -> {
                      // Try to refine it to find the actual target in the file
                      String targetName = target.substring(target.lastIndexOf(':') + 1);
                      return findTargetInPsiTree(psiFile, targetName).orElse(psiFile);
                    });
      } else {
        // If there's no ':' character, try to find file relative to the current file
        psiTarget = findLocalFile(source, target).map(psiManager::findFile);
      }
    } else {
      psiTarget = Optional.empty();
    }
    if (psiTarget.isPresent()) {
      project
          .getMessageBus()
          .syncPublisher(IntellijBuckAction.EVENT)
          .consume(this.getClass().toString());
    }
    return psiTarget.orElse(null);
  }

  private static String unwrapString(String target) {
    if (target.length() >= 2 && target.startsWith("\"") && target.endsWith("\"")) {
      target = target.substring(1, target.length() - 1);
    } else if (target.length() >= 2 && target.startsWith("'") && target.endsWith("'")) {
      target = target.substring(1, target.length() - 1);
    }
    return target;
  }

  private static Optional<PsiElement> findTargetInPsiTree(PsiElement root, String target) {
    for (BuckRuleBlock buckRuleBlock : PsiTreeUtil.findChildrenOfType(root, BuckRuleBlock.class)) {
      BuckRuleBody buckRuleBody = buckRuleBlock.getRuleBody();
      for (BuckProperty buckProperty :
          PsiTreeUtil.findChildrenOfType(buckRuleBody, BuckProperty.class)) {
        if (!Optional.ofNullable(buckProperty.getPropertyLvalue().getIdentifier())
            .map(PsiElement::getText)
            .filter("name"::equals)
            .isPresent()) {
          continue;
        }
        if (Optional.of(buckProperty.getExpression())
            .map(BuckExpression::getValueList)
            .map(list -> Iterables.getFirst(list, null))
            .map(BuckValue::getText)
            .map(BuckGotoProvider::unwrapString)
            .filter(target::equals)
            .isPresent()) {
          return Optional.of(buckRuleBlock);
        }
      }
    }
    return Optional.empty();
  }

  private static @Nullable Optional<VirtualFile> findLocalFile(
      PsiElement sourceElement, String target) {
    return Optional.of(sourceElement)
        .map(PsiElement::getContainingFile)
        .map(PsiFile::getVirtualFile)
        .map(VirtualFile::getParent)
        .map(parent -> parent.findFileByRelativePath(unwrapString(target)));
  }
}
