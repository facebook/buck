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

package com.facebook.buck.intellij.ideabuck.actions;

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpressionStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionTrailer;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.awt.datatransfer.StringSelection;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Copy the Buck target of the current file, this currently only works for Java and resource files
 */
public class CopyTargetAction extends AnAction {

  @Override
  public void update(AnActionEvent e) {
    VirtualFile buckFile = findBuckFileFromActionEvent(e);
    if (buckFile != null && buckFile.equals(e.getData(CommonDataKeys.VIRTUAL_FILE))) {
      e.getPresentation().setEnabledAndVisible(isActionEventInRuleBody(e));
    } else {
      e.getPresentation().setEnabledAndVisible(buckFile != null);
    }
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    final Project project = e.getProject();
    if (project == null) {
      fail("The current project is invalid");
      return;
    }
    final VirtualFile buckFile = findBuckFileFromActionEvent(e);
    if (buckFile == null) {
      fail("Unable to find a BUCK file");
      return;
    }
    final BuckTargetPattern buckTargetPattern =
        BuckTargetLocator.getInstance(project)
            .findTargetPatternForVirtualFile(buckFile)
            .orElse(null);
    if (buckTargetPattern == null) {
      fail("Unable to find a Buck target pattern for " + buckFile.getCanonicalPath());
      return;
    }
    BuckCellManager buckCellManager = BuckCellManager.getInstance(project);
    final BuckCellManager.Cell buckCell =
        buckCellManager.findCellByVirtualFile(buckFile).orElse(null);
    String targetPath = buckTargetPattern.toString();
    if (buckCellManager
            .getDefaultCell()
            .map(defaultCell -> defaultCell.equals(buckCell))
            .orElse(false)
        && targetPath.contains("//")) {
      // If it is in the default cell, remove the cell name prefix
      targetPath = "//" + targetPath.split("//")[1];
    }
    if (buckFile.equals(e.getData(CommonDataKeys.VIRTUAL_FILE))) {
      final String targetName = getTargetNameFromActionEvent(e);
      if (targetName != null) {
        CopyPasteManager.getInstance().setContents(new StringSelection(targetPath + targetName));
      } else {
        fail("Unable to get the name of the Buck target");
      }
      return;
    }
    final String cellPath = buckTargetPattern.getCellPath().orElse(null);
    if (cellPath == null) {
      fail("Unable to get the cell path of " + buckTargetPattern);
      return;
    }
    final String[] parts = cellPath.split("/");
    if (parts.length == 0) {
      fail("Cell path " + cellPath + " is invalid");
      return;
    }

    CopyPasteManager.getInstance()
        .setContents(new StringSelection(targetPath + parts[parts.length - 1]));
  }

  private static void fail(String message) {
    Messages.showWarningDialog(message, "Failed to Copy Buck Target");
  }

  @Nullable
  private static PsiElement getPsiElementFromActionEvent(AnActionEvent e) {
    PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
    Caret caret = e.getData(CommonDataKeys.CARET);
    return (psiFile == null || caret == null) ? null : psiFile.findElementAt(caret.getOffset());
  }

  private static Collection<BuckFunctionTrailer> getBuckFunctionTrailersFromActionEvent(
      AnActionEvent e) {
    PsiElement element = getPsiElementFromActionEvent(e);
    if (element == null) {
      return Collections.emptyList();
    }
    BuckExpressionStatement expressionStatement =
        PsiTreeUtil.getParentOfType(element, BuckExpressionStatement.class);
    if (expressionStatement == null) {
      return Collections.emptyList();
    }
    return PsiTreeUtil.findChildrenOfType(expressionStatement, BuckFunctionTrailer.class);
  }

  private static boolean isActionEventInRuleBody(AnActionEvent e) {
    return getBuckFunctionTrailersFromActionEvent(e).stream()
        .anyMatch(buckFunctionTrailer -> buckFunctionTrailer.getName() != null);
  }

  @Nullable
  private static String getTargetNameFromActionEvent(AnActionEvent e) {
    return getBuckFunctionTrailersFromActionEvent(e).stream()
        .map(BuckFunctionTrailer::getName)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  @Nullable
  private static VirtualFile findBuckFileFromActionEvent(AnActionEvent e) {
    if (e.getProject() == null) {
      return null;
    }
    return Optional.ofNullable(e.getData(CommonDataKeys.VIRTUAL_FILE))
        .flatMap(
            file -> BuckTargetLocator.getInstance(e.getProject()).findBuckFileForVirtualFile(file))
        .orElse(null);
  }
}
