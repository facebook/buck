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

package com.facebook.buck.intellij.ideabuck.annotator;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildUtil;
import com.facebook.buck.intellij.ideabuck.external.IntellijBuckAction;
import com.facebook.buck.intellij.ideabuck.highlight.BuckSyntaxHighlighter;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadTargetArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPsiUtils;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckValue;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

/** Annotator for Buck, it helps highlight and annotate any issue in Buck files. */
public class BuckAnnotator implements Annotator {

  private static final String ANNOTATOR_ERROR_CANNOT_LOCATE_TARGET =
      "Cannot locate the Buck target";

  @Override
  public void annotate(PsiElement psiElement, AnnotationHolder annotationHolder) {
    if (annotateIdentifier(psiElement, annotationHolder)) {
      return;
    }
    annotateErrors(psiElement, annotationHolder);
  }

  private void annotateErrors(PsiElement psiElement, AnnotationHolder annotationHolder) {
    if (psiElement instanceof BuckLoadTargetArgument) {
      annotateLoadTargetErrors((BuckLoadTargetArgument) psiElement, annotationHolder);
      return;
    }
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
    if (!BuckBuildUtil.isValidAbsoluteTarget(target)) {
      return;
    }
    @Nullable
    VirtualFile targetBuckFile = BuckBuildUtil.getBuckFileFromAbsoluteTarget(project, target);

    if (targetBuckFile == null) {
      annotationHolder.createErrorAnnotation(
          psiElement.getTextRange(), ANNOTATOR_ERROR_CANNOT_LOCATE_TARGET);
      project
          .getMessageBus()
          .syncPublisher(IntellijBuckAction.EVENT)
          .consume(this.getClass().toString());
    }
  }

  private void annotateLoadTargetErrors(
      BuckLoadTargetArgument loadTargetArgument, AnnotationHolder annotationHolder) {
    Project project = loadTargetArgument.getProject();
    String target = loadTargetArgument.getText();
    target = target.substring(1, target.length() - 1); // strip quotes
    if (!BuckBuildUtil.isValidAbsoluteTarget(target)) {
      // TODO(ttsugrii): warn about usage of invalid pattern
      return;
    }
    String packagePath = BuckBuildUtil.extractAbsoluteTarget(target);
    String fileName = BuckBuildUtil.extractTargetName(target);
    @Nullable
    VirtualFile packageDirectory = project.getBaseDir().findFileByRelativePath(packagePath);
    @Nullable
    VirtualFile loadTargetFile =
        packageDirectory != null ? packageDirectory.findChild(fileName) : null;
    if (loadTargetFile == null) {
      annotationHolder.createErrorAnnotation(
          loadTargetArgument.getTextRange(), "Cannot locate extension file " + fileName);
      project
          .getMessageBus()
          .syncPublisher(IntellijBuckAction.EVENT)
          .consume(this.getClass().toString());
    }
  }

  private boolean annotateIdentifier(PsiElement psiElement, AnnotationHolder annotationHolder) {
    if (psiElement.getNode().getElementType() != BuckTypes.IDENTIFIER) {
      return false;
    }

    PsiElement parent = psiElement.getParent();
    assert parent != null;

    final Annotation annotation = annotationHolder.createInfoAnnotation(psiElement, null);
    if (BuckPsiUtils.testType(parent, BuckTypes.RULE_NAME)) {
      annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_RULE_NAME);
      return true;
    } else if (BuckPsiUtils.testType(parent, BuckTypes.PROPERTY_LVALUE)) {
      annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_KEYWORD);
      return true;
    }
    return false;
  }
}
