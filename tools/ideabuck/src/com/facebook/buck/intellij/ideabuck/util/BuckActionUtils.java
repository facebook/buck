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

package com.facebook.buck.intellij.ideabuck.util;

import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpressionStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionTrailer;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/** Methods for finding PsiElements, Buck files and other data from an AnActionEvent */
public class BuckActionUtils {

  private BuckActionUtils() {}

  /** Gets PsiElement from the ActionEvent/caret location */
  @Nullable
  public static PsiElement getPsiElementFromActionEvent(AnActionEvent e) {
    PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
    Caret caret = e.getData(CommonDataKeys.CARET);
    return (psiFile == null || caret == null) ? null : psiFile.findElementAt(caret.getOffset());
  }

  /** Gets all BuckFunctionTrailers related to PsiElement from ActionEvent */
  public static Collection<BuckFunctionTrailer> getBuckFunctionTrailersFromActionEvent(
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

  public static boolean isActionEventInRuleBody(AnActionEvent e) {
    return getBuckFunctionTrailersFromActionEvent(e).stream()
        .anyMatch(buckFunctionTrailer -> buckFunctionTrailer.getName() != null);
  }

  @Nullable
  public static String getTargetNameFromActionEvent(AnActionEvent e) {
    return getBuckFunctionTrailersFromActionEvent(e).stream()
        .map(BuckFunctionTrailer::getName)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /** Gets the Buck File from the current file */
  @Nullable
  public static VirtualFile findBuckFileFromActionEvent(AnActionEvent e) {
    if (e.getProject() == null) {
      return null;
    }
    return Optional.ofNullable(e.getData(CommonDataKeys.VIRTUAL_FILE))
        .flatMap(
            file -> BuckTargetLocator.getInstance(e.getProject()).findBuckFileForVirtualFile(file))
        .orElse(null);
  }
}
