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

import com.facebook.buck.intellij.ideabuck.config.BuckCell;
import com.facebook.buck.intellij.ideabuck.external.IntellijBuckAction;
import com.facebook.buck.intellij.ideabuck.highlight.BuckSyntaxHighlighter;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadTargetArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPsiUtils;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSingleExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.facebook.buck.intellij.ideabuck.util.BuckCellFinder;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Annotator for Buck, it helps highlight and annotate any issue in Buck files. */
public class BuckAnnotator implements Annotator {

  @Override
  public void annotate(@NotNull PsiElement psiElement, @NotNull AnnotationHolder annotationHolder) {
    if (annotateIdentifier(psiElement, annotationHolder)) {
      return;
    }
    if (annotateLoadTarget(psiElement, annotationHolder)) {
      return;
    }
    if (annotateSingleExpression(psiElement, annotationHolder)) {
      return;
    }
  }

  private boolean annotateIdentifier(PsiElement psiElement, AnnotationHolder annotationHolder) {
    if (psiElement.getNode().getElementType() != BuckTypes.IDENTIFIER) {
      return false;
    }
    PsiElement parent = psiElement.getParent();
    assert parent != null;

    if (BuckPsiUtils.testType(parent, BuckTypes.FUNCTION_NAME)) {
      Annotation annotation = annotationHolder.createInfoAnnotation(psiElement, null);
      annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_FUNCTION_NAME);
      return true;
    }
    if (BuckPsiUtils.testType(parent, BuckTypes.PROPERTY_LVALUE)) {
      Annotation annotation = annotationHolder.createInfoAnnotation(psiElement, null);
      annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_PROPERTY_LVALUE);
      return true;
    }
    return false;
  }

  private boolean annotateLoadTarget(PsiElement psiElement, AnnotationHolder annotationHolder) {
    if (psiElement.getNode().getElementType() != BuckTypes.LOAD_TARGET_ARGUMENT) {
      return false;
    }
    BuckLoadTargetArgument loadTargetArgument = (BuckLoadTargetArgument) psiElement;
    Project project = loadTargetArgument.getProject();
    String target = BuckPsiUtils.getStringValueFromBuckString(loadTargetArgument.getString());
    VirtualFile sourceFile = loadTargetArgument.getContainingFile().getVirtualFile();
    BuckCellFinder buckCellFinder = BuckCellFinder.getInstance(project);
    Optional<VirtualFile> extensionFile = buckCellFinder.findExtensionFile(sourceFile, target);
    if (extensionFile.isPresent()) {
      Annotation annotation =
          annotationHolder.createInfoAnnotation(psiElement, extensionFile.get().getPath());
      annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_FILE_NAME);
    } else {
      annotationHolder.createErrorAnnotation(psiElement, "Cannot resolve load target");
    }
    return true;
  }

  private boolean annotateSingleExpression(
      PsiElement psiElement, AnnotationHolder annotationHolder) {
    if (psiElement.getNode().getElementType() != BuckTypes.SINGLE_EXPRESSION) {
      return false;
    }
    BuckSingleExpression singleExpression = (BuckSingleExpression) psiElement;
    String stringValue = BuckPsiUtils.getStringValueFromExpression(singleExpression);
    if (stringValue == null) {
      return false;
    }
    if (annotateLocalTarget(singleExpression, stringValue, annotationHolder)) {
      return true;
    }
    if (annotateAbsoluteTarget(singleExpression, stringValue, annotationHolder)) {
      return true;
    }
    if (annotateLocalFile(singleExpression, stringValue, annotationHolder)) {
      return true;
    }
    return true;
  }

  /** Annotates targets that refer to this file, as in ":other-target" */
  private boolean annotateLocalTarget(
      BuckSingleExpression targetExpression,
      String targetValue,
      AnnotationHolder annotationHolder) {
    if (!targetValue.startsWith(":")) {
      return false;
    }
    String target = targetValue.substring(1);
    if (target.contains(":")) {
      return false;
    }
    Project project = targetExpression.getProject();
    PsiFile containingFile = targetExpression.getContainingFile();
    PsiElement targetElement = BuckPsiUtils.findTargetInPsiTree(containingFile, target);
    if (targetElement != null) {
      Annotation annotation = annotationHolder.createInfoAnnotation(targetExpression, null);
      annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_TARGET);
    } else {
      Annotation annotation =
          annotationHolder.createWeakWarningAnnotation(
              targetExpression, "Cannot resolve target \"" + targetValue + "\"");
      annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
    }
    logToMessageBus(project);
    return true;
  }

  private static final Pattern ABSOLUTE_TARGET_PATTERN =
      Pattern.compile("@?(?<cell>[-A-Za-z_.]*)//(?<path>[^:]*):(?<name>[^:]*)");

  /** Annotates targets that refer to other files, as in "othercell//path/to:target". */
  private boolean annotateAbsoluteTarget(
      BuckSingleExpression targetExpression,
      String targetValue,
      AnnotationHolder annotationHolder) {
    Matcher matcher = ABSOLUTE_TARGET_PATTERN.matcher(targetValue);
    if (!matcher.matches()) {
      return false;
    }
    Project project = targetExpression.getProject();
    PsiFile containingFile = targetExpression.getContainingFile();
    String cellName = matcher.group("cell");
    VirtualFile sourceFile = containingFile.getVirtualFile();
    BuckCellFinder buckCellFinder = BuckCellFinder.getInstance(project);
    @Nullable
    VirtualFile targetVirtualFile =
        buckCellFinder.findBuckTargetFile(sourceFile, targetValue).orElse(null);
    if (targetVirtualFile == null) {
      BuckCell cell = buckCellFinder.findBuckCellByName(cellName).orElse(null);
      Annotation annotation;
      if (cell == null) {
        annotation =
            annotationHolder.createWarningAnnotation(
                targetExpression, "Unrecognized Buck cell: \"" + cellName + "\"");
      } else {
        annotation =
            annotationHolder.createWarningAnnotation(
                targetExpression, "Cannot find Buck file for \"" + targetValue + "\"");
      }
      annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
      logToMessageBus(project);
      return true;
    }
    @Nullable PsiFile targetPsiFile = PsiManager.getInstance(project).findFile(targetVirtualFile);
    if (targetPsiFile == null) {
      Annotation annotation =
          annotationHolder.createWarningAnnotation(
              targetExpression, "Cannot find Buck file: " + targetVirtualFile.getPath());
      annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
      logToMessageBus(project);
      return true;
    }
    String targetName = matcher.group("name");
    if ("".equals(targetName)) {
      // If the target pattern is "cell/path/to:", that merely requires a Buck file to exist
    } else {
      PsiElement targetElement = BuckPsiUtils.findTargetInPsiTree(targetPsiFile, targetName);
      if (targetElement == null) {
        String text = targetExpression.getText();
        TextRange textRange = targetExpression.getTextRange();
        int colonSeparator = textRange.getStartOffset() + text.indexOf(':');
        // We found the file, which is good...
        annotationHolder
            .createInfoAnnotation(
                new TextRange(textRange.getStartOffset(), colonSeparator),
                targetVirtualFile.getPath())
            .setTextAttributes(BuckSyntaxHighlighter.BUCK_TARGET);
        // ...but not the target, which is bad.
        annotationHolder
            .createWarningAnnotation(
                new TextRange(colonSeparator, textRange.getEndOffset()),
                "Cannot resolve target " + targetName)
            .setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
        logToMessageBus(project);
        return true;
      }
    }
    Annotation annotation =
        annotationHolder.createInfoAnnotation(targetExpression, targetVirtualFile.getPath());
    annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_TARGET);
    return true;
  }

  /** Annotates targets that refer to files relative to this file. */
  private boolean annotateLocalFile(
      BuckSingleExpression targetExpression,
      String targetValue,
      AnnotationHolder annotationHolder) {
    Optional<VirtualFile> targetFile =
        Optional.of(targetExpression)
            .map(PsiElement::getContainingFile)
            .map(PsiFile::getVirtualFile)
            .map(VirtualFile::getParent)
            .map(dir -> dir.findFileByRelativePath(targetValue))
            .filter(VirtualFile::exists);
    if (!targetFile.isPresent()) {
      return false;
    }
    Annotation annotation =
        annotationHolder.createInfoAnnotation(targetExpression, targetFile.get().getPath());
    annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_FILE_NAME);
    return true;
  }

  private void logToMessageBus(Project project) {
    project.getMessageBus().syncPublisher(IntellijBuckAction.EVENT).consume(getClass().toString());
  }
}
