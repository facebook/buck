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
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckValue;
import com.facebook.buck.intellij.ideabuck.util.BuckCellFinder;
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
  public PsiElement getGotoDeclarationTarget(@Nullable PsiElement source, Editor editor) {
    if (source == null || !(source.getLanguage() instanceof BuckLanguage)) {
      return null;
    }

    final Project project = editor.getProject();
    if (project == null) {
      return null;
    }

    VirtualFile sourceFile = source.getContainingFile().getVirtualFile();
    BuckCellFinder buckCellFinder = BuckCellFinder.getInstance(project);

    String target = source.getText();
    if (target.length() >= 2
        && ((target.startsWith("'") && target.endsWith("'"))
            || (target.startsWith("\"") && target.endsWith("\"")))) {
      target = target.substring(1, target.length() - 1);
    }

    Optional<VirtualFile> targetFile;
    if (PsiTreeUtil.getParentOfType(source, BuckLoadTargetArgument.class) != null) {
      targetFile = buckCellFinder.findExtensionFile(sourceFile, target);
    } else if (PsiTreeUtil.getParentOfType(source, BuckValue.class) != null) {
      targetFile = buckCellFinder.findBuckTargetFile(sourceFile, target);
    } else {
      targetFile = Optional.empty();
    }

    return targetFile
        .map(
            f -> {
              project
                  .getMessageBus()
                  .syncPublisher(IntellijBuckAction.EVENT)
                  .consume(this.getClass().toString());
              return PsiManager.getInstance(project).findFile(f);
            })
        .orElse(null);
  }
}
