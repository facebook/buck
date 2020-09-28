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

package com.facebook.buck.intellij.ideabuck.inspection;

import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckElementFactory;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckListMaker;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import javax.annotation.Nullable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

/** Adds the specified target to the list the list of visible targets, specified by a PSIElement */
public class BuckInsertTargetToVisibilityQuickFix implements LocalQuickFix {

  @NotNull private final BuckTargetPattern mTargetToAdd;
  @NotNull private final BuckTargetPattern mTargetWithVisibility;
  @NotNull private final BuckListMaker mVisibilityList;

  public BuckInsertTargetToVisibilityQuickFix(
      @NotNull BuckTargetPattern targetToAdd,
      @NotNull BuckTargetPattern targetWithVisibility,
      @NotNull BuckListMaker visibilityList) {
    mTargetToAdd = targetToAdd;
    mTargetWithVisibility = targetWithVisibility;
    mVisibilityList = visibilityList;
  }

  @Nls(capitalization = Nls.Capitalization.Sentence)
  @NotNull
  @Override
  public String getName() {
    return "Make this dep visible to this target";
  }

  @Nls(capitalization = Nls.Capitalization.Sentence)
  @NotNull
  @Override
  public String getFamilyName() {
    return getName();
  }

  @Override
  public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor problemDescriptor) {
    String listMakerContent = getListMakerWithTargetText(project);
    if (listMakerContent == null) {
      return;
    }
    PsiElement navigationElement = mVisibilityList.getNavigationElement();
    if (navigationElement instanceof Navigatable
        && ((Navigatable) navigationElement).canNavigate()) {
      ((Navigatable) navigationElement).navigate(true);
    }
    // Add "[" and "]" to have a BuckListMaker exist as child PSI element in order to parse
    // and retrieve correctly
    mVisibilityList.replace(
        BuckElementFactory.createElement(
            project, "[" + listMakerContent + "]", BuckListMaker.class));
  }

  @Nullable
  private String getListMakerWithTargetText(@NotNull Project project) {
    int lineStartOffset = getLineOffset(project);
    if (lineStartOffset == -1) {
      return null;
    }
    String startSpacing = StringUtil.repeat(" ", lineStartOffset);
    // Omit cell name if cell names are the same
    String targetText =
        mTargetToAdd.getCellName().equals(mTargetWithVisibility.getCellName())
            ? mTargetToAdd.getCellQualifiedName()
            : mTargetToAdd.toString();
    return "\"" + targetText + "\",\n" + startSpacing + mVisibilityList.getText();
  }

  private int getLineOffset(@NotNull Project project) {
    PsiFile containingFile = mVisibilityList.getContainingFile();
    Document document = PsiDocumentManager.getInstance(project).getDocument(containingFile);
    // This shouldn't happen, since the file exists at this point
    if (document == null) {
      return -1;
    }
    return mVisibilityList.getTextOffset()
        - document.getLineStartOffset(document.getLineNumber(mVisibilityList.getTextOffset()));
  }
}
