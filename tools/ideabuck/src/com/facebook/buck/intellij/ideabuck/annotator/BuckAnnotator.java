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

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager;
import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.facebook.buck.intellij.ideabuck.highlight.BuckSyntaxHighlighter;
import com.facebook.buck.intellij.ideabuck.lang.BuckFileType;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadCall;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadTargetArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckParameter;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSimpleExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckString;
import com.facebook.buck.intellij.ideabuck.util.BuckPsiUtils;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/** Annotator for Buck, it helps highlight and annotate any issue in Buck files. */
public class BuckAnnotator implements Annotator {

  @Override
  public void annotate(@NotNull PsiElement psiElement, @NotNull AnnotationHolder annotationHolder) {
    if (psiElement instanceof BuckArgument) {
      annotateBuckArgument((BuckArgument) psiElement, annotationHolder);
    } else if (psiElement instanceof BuckParameter) {
      annotateBuckParameter((BuckParameter) psiElement, annotationHolder);
    } else if (psiElement instanceof BuckLoadCall) {
      annotateLoadCall((BuckLoadCall) psiElement, annotationHolder);
    } else if (psiElement instanceof BuckSimpleExpression) {
      annotateSimpleExpression((BuckSimpleExpression) psiElement, annotationHolder);
    }
  }

  /** Colorize named arguments in function invocations. */
  private void annotateBuckArgument(BuckArgument argument, AnnotationHolder annotationHolder) {
    Optional.ofNullable(argument.getIdentifier())
        .ifPresent(
            identifier -> {
              Annotation annotation = annotationHolder.createInfoAnnotation(identifier, null);
              annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_PROPERTY_LVALUE);
            });
  }

  /** Colorize named parameters in function definitions. */
  private void annotateBuckParameter(BuckParameter parameter, AnnotationHolder annotationHolder) {
    Optional.ofNullable(parameter.getIdentifier())
        .ifPresent(
            identifier -> {
              Annotation annotation = annotationHolder.createInfoAnnotation(identifier, null);
              annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_PROPERTY_LVALUE);
            });
  }

  private void annotateLoadCall(BuckLoadCall loadCall, AnnotationHolder annotationHolder) {
    BuckLoadTargetArgument loadTargetArgument = loadCall.getLoadTargetArgument();

    BuckTarget buckTarget =
        Optional.of(loadTargetArgument.getString())
            .map(BuckString::getValue)
            .flatMap(BuckTarget::parse)
            .orElse(null);
    if (buckTarget == null) {
      annotationHolder
          .createErrorAnnotation(loadTargetArgument, "Cannot parse as load target")
          .setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
      return;
    }
    Project project = loadCall.getProject();
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    BuckTarget extensionTarget =
        Optional.of(loadCall.getContainingFile())
            .map(PsiFile::getVirtualFile)
            .flatMap(sourceFile -> buckTargetLocator.resolve(sourceFile, buckTarget))
            .orElse(null);
    if (extensionTarget == null) {
      annotationHolder
          .createErrorAnnotation(loadTargetArgument, "Cannot resolve load target")
          .setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
      return;
    }
    VirtualFile extensionFile =
        buckTargetLocator.findVirtualFileForExtensionFile(extensionTarget).orElse(null);
    if (extensionFile == null) {
      String message =
          buckTargetLocator
              .findPathForExtensionFile(extensionTarget)
              .map(path -> "Cannot find file at " + path.toString())
              .orElse("Cannot resolve path from target");
      annotationHolder
          .createErrorAnnotation(loadTargetArgument, message)
          .setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
      return;
    }
    if (extensionFile.getFileType() != BuckFileType.INSTANCE) {
      String message =
          "Cannot parse file extension for more info (perhaps the file extension is not associated with the ideabuck plugin?)";
      annotationHolder.createWeakWarningAnnotation(loadTargetArgument, message);
      return;
    }
    PsiFile psiFile = PsiManager.getInstance(project).findFile(extensionFile);
    if (psiFile == null) {
      String message = "Cannot get parse tree for file extension.";
      annotationHolder.createWeakWarningAnnotation(loadTargetArgument, message);
      return; // Unsure when this would happen...perhaps during indexing?
    }
    Set<String> availableSymbols = BuckPsiUtils.findSymbolsInPsiTree(psiFile, "").keySet();
    for (BuckLoadArgument loadArgument : loadCall.getLoadArgumentList()) {
      BuckString buckString = loadArgument.getString();
      if (availableSymbols.contains(buckString.getValue())) {
        annotationHolder
            .createInfoAnnotation(buckString, null)
            .setTextAttributes(BuckSyntaxHighlighter.BUCK_IDENTIFIER);
      } else {
        annotationHolder
            .createWarningAnnotation(buckString, "Cannot find symbol ")
            .setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
      }
    }
  }

  private void annotateSimpleExpression(
      BuckSimpleExpression simpleExpression, AnnotationHolder annotationHolder) {
    Optional.of(simpleExpression)
        .map(BuckPsiUtils::getStringValueFromSimpleExpression)
        .filter(
            s ->
                annotateStringAsTargetPattern(simpleExpression, s, annotationHolder)
                    || annotateStringAsLocalFile(simpleExpression, s, annotationHolder));
  }

  /** Annotates targets that refer to this file, as in ":other-target" */
  private boolean annotateStringAsTargetPattern(
      BuckSimpleExpression expression, String stringValue, AnnotationHolder annotationHolder) {
    BuckTargetPattern buckTargetPattern = BuckTargetPattern.parse(stringValue).orElse(null);
    if (buckTargetPattern == null) {
      return false;
    }
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(expression.getProject());
    if (!buckTargetPattern.isAbsolute()) {
      VirtualFile sourceFile = expression.getContainingFile().getVirtualFile();
      if (sourceFile == null) {
        return true;
      }
      BuckTargetPattern absolutePattern =
          buckTargetLocator.resolve(sourceFile, buckTargetPattern).orElse(null);
      if (absolutePattern == null) {
        Annotation annotation =
            annotationHolder.createWarningAnnotation(expression, "Cannot resolve target pattern");
        annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
        return true;
      }
      buckTargetPattern = absolutePattern;
    }
    Project project = expression.getProject();
    VirtualFile targetFile =
        buckTargetLocator.findVirtualFileForTargetPattern(buckTargetPattern).orElse(null);
    if (targetFile == null) {
      BuckCellManager buckCellManager = BuckCellManager.getInstance(project);
      String cellName = buckTargetPattern.getCellName().orElse(null);
      if (cellName != null && !buckCellManager.findCellByName(cellName).isPresent()) {
        String message = "Cannot resolve cell named " + cellName;
        Annotation annotation =
            BuckPsiUtils.findTextInElement(expression, cellName)
                .map(textRange -> annotationHolder.createErrorAnnotation(textRange, message))
                .orElseGet(() -> annotationHolder.createErrorAnnotation(expression, message));
        annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
        return true;
      }
      String message =
          buckTargetLocator
              .findPathForTargetPattern(buckTargetPattern)
              .map(path -> "Cannot find file at " + path.toString())
              .orElse("Cannot resolve path from target pattern");
      annotationHolder
          .createWarningAnnotation(expression, message)
          .setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
      return true;
    }
    BuckTarget buckTarget = buckTargetPattern.asBuckTarget().orElse(null);
    if (buckTarget == null) {
      // If this is a target pattern, but not a valid target, color it as such
      annotationHolder
          .createInfoAnnotation(expression, targetFile.getPath())
          .setTextAttributes(BuckSyntaxHighlighter.BUCK_TARGET_PATTERN);
      return true;
    }
    PsiFile psiFile = PsiManager.getInstance(project).findFile(targetFile);
    if (psiFile == null) {
      String message = "Cannot get parse tree for " + targetFile.getPath();
      annotationHolder
          .createWarningAnnotation(expression, message)
          .setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
      return true; // Unsure when this would happen...perhaps during indexing?
    }
    PsiElement target = BuckPsiUtils.findTargetInPsiTree(psiFile, buckTarget.getRuleName());
    if (target == null) {
      String ruleName = buckTarget.getRuleName();
      String message = "Cannot resolve rule named " + ruleName;
      Annotation annotation =
          BuckPsiUtils.findTextInElement(expression, ":" + ruleName)
              .map(textRange -> annotationHolder.createWarningAnnotation(textRange, message))
              .orElseGet(() -> annotationHolder.createWarningAnnotation(expression, message));
      annotation.setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
      return true;
    }
    annotationHolder
        .createInfoAnnotation(expression, targetFile.getPath())
        .setTextAttributes(BuckSyntaxHighlighter.BUCK_TARGET);
    return true;
  }

  /** Annotates targets that refer to files relative to this file. */
  private boolean annotateStringAsLocalFile(
      BuckSimpleExpression targetExpression,
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
}
