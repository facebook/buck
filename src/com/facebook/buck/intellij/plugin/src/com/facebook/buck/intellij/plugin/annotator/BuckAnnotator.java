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

package com.facebook.buck.intellij.plugin.annotator;

import com.facebook.buck.intellij.plugin.build.BuckBuildTargetUtil;
import com.facebook.buck.intellij.plugin.file.BuckFileUtil;
import com.facebook.buck.intellij.plugin.lang.psi.BuckValue;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;

/**
 * Annotator for Buck, it helps highlight and annotate any issue in Buck files.
 */
public class BuckAnnotator implements Annotator {

  private static final String ANNOTATOR_ERROR_CANNOT_LOCATE_TARGET =
      "Cannot locate the Buck target";

  @Override
  public void annotate(PsiElement psiElement, AnnotationHolder annotationHolder) {
    BuckValue value = PsiTreeUtil.getParentOfType(psiElement, BuckValue.class);
    if (value == null) {
      return;
    }
    final Project project = psiElement.getProject();
    if (project == null) {
      return;
    }

    String target = psiElement.getText();
    if (target.matches("\".*\"") || target.matches("'.*'")) {
      target = target.substring(1, target.length() - 1);
    } else {
      return;
    }
    if (!BuckBuildTargetUtil.isValidAbsoluteTarget(target)) {
      return;
    }
    VirtualFile buckDir = project.getBaseDir().findFileByRelativePath(
        BuckBuildTargetUtil.extractAbsoluteTarget(target));
    VirtualFile targetBuckFile = buckDir != null ?
        buckDir.findChild(BuckFileUtil.getBuildFileName()) : null;

    if (targetBuckFile == null) {
      TextRange range = new TextRange(psiElement.getTextRange().getStartOffset(),
          psiElement.getTextRange().getEndOffset());
      annotationHolder.createErrorAnnotation(range, ANNOTATOR_ERROR_CANNOT_LOCATE_TARGET);
    }
  }
}
