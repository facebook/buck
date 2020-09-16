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

import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionTrailer;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckListMaker;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckString;
import com.facebook.buck.intellij.ideabuck.util.BuckActionUtils;
import com.facebook.buck.intellij.ideabuck.util.BuckPsiUtils;
import com.facebook.buck.intellij.ideabuck.visibility.BuckVisibilityState;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Gets the Buck dep the user clicked on, then checks if it is visible to the build rule that
 * depends on it
 */
public class BuckVisibilityAction extends AnAction {

  public static final Set<String> SUPPORTED_PARAMETER_NAMES =
      new HashSet<>(
          Arrays.asList("deps", "exported_deps", "provided_deps", "exported_provided_deps"));

  private static final String DIALOG_TITLE = "Check Dep Visibility";

  @Override
  public void update(AnActionEvent e) {
    VirtualFile buckFile = BuckActionUtils.findBuckFileFromActionEvent(e);
    BuckTargetPattern buckTargetPattern = getBuckTargetPatternAtCaret(e);
    e.getPresentation().setEnabledAndVisible(buckFile != null && buckTargetPattern != null);
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) {
      return;
    }
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    BuckTargetPattern parentTargetPattern = getBuckTargetPatternFromEvent(buckTargetLocator, e);
    if (parentTargetPattern == null) {
      return;
    }
    BuckTargetPattern depTargetPattern =
        buckTargetLocator
            .resolve(BuckActionUtils.findBuckFileFromActionEvent(e), getBuckTargetPatternAtCaret(e))
            .orElse(null);
    if (depTargetPattern == null) {
      return;
    }
    VirtualFile depTargetFile =
        buckTargetLocator.findVirtualFileForTargetPattern(depTargetPattern).orElse(null);
    if (depTargetFile == null) {
      return;
    }
    BuckVisibilityState visibleTargets =
        getVisibilityState(project, buckTargetLocator, depTargetFile, depTargetPattern);
    BuckVisibilityState.VisibleState visibleState =
        visibleTargets.getVisibility(parentTargetPattern.asBuckTarget().orElse(null));
    if (visibleState == BuckVisibilityState.VisibleState.UNKNOWN) {
      Messages.showInfoMessage("Dep visibility cannot be determined", DIALOG_TITLE);
      return;
    }
    String message =
        visibleState == BuckVisibilityState.VisibleState.VISIBLE
            ? "Dep is visible"
            : "Dep is not visible";
    Messages.showInfoMessage(message, DIALOG_TITLE);
  }

  @Nullable
  private static BuckTargetPattern getBuckTargetPatternFromEvent(
      BuckTargetLocator buckTargetLocator, AnActionEvent e) {
    String targetName = BuckActionUtils.getTargetNameFromActionEvent(e);
    if (targetName == null) {
      return null;
    }
    VirtualFile buckFile = BuckActionUtils.findBuckFileFromActionEvent(e);
    if (buckFile == null) {
      return null;
    }
    BuckTargetPattern filePattern =
        buckTargetLocator.findTargetPatternForVirtualFile(buckFile).orElse(null);
    if (filePattern == null) {
      return null;
    }
    String targetPath = filePattern.toString();
    return BuckTargetPattern.parse(targetPath + targetName).orElse(null);
  }

  @Nullable
  private static BuckTargetPattern getBuckTargetPatternAtCaret(AnActionEvent e) {
    PsiElement psiElement = BuckActionUtils.getPsiElementFromActionEvent(e);
    if (psiElement == null) {
      return null;
    }
    BuckString buckString = PsiTreeUtil.getParentOfType(psiElement, BuckString.class);
    if (buckString == null) {
      return null;
    }
    // The parameter name should be one of the values from SUPPORTED_PARAMETER_NAMES
    BuckArgument argument = PsiTreeUtil.getParentOfType(buckString, BuckArgument.class);
    if (argument == null
        || argument.getIdentifier() == null
        || !SUPPORTED_PARAMETER_NAMES.contains(argument.getIdentifier().getText())) {
      return null;
    }
    return BuckTargetPattern.parse(buckString.getValue()).orElse(null);
  }

  private static BuckVisibilityState getVisibilityState(
      Project project,
      BuckTargetLocator buckTargetLocator,
      VirtualFile buckFile,
      BuckTargetPattern buckTargetPattern) {
    PsiFile psiFile = PsiUtil.getPsiFile(project, buckFile);
    BuckFunctionTrailer buckFunctionTrailer =
        BuckPsiUtils.findTargetInPsiTree(psiFile, buckTargetPattern.getRuleName().orElse(null));
    // If build rule can't be found, just assume not visible
    if (buckFunctionTrailer == null) {
      return new BuckVisibilityState(BuckVisibilityState.VisibleState.NOT_VISIBLE);
    }
    // Find the list of deps listed under visibility
    BuckArgument visibilityArgument =
        PsiTreeUtil.findChildrenOfType(buckFunctionTrailer, BuckArgument.class).stream()
            .filter(buckArg -> "visibility".equals(buckArg.getName()))
            .findFirst()
            .orElse(null);
    // Visibility wasn't found, assume visibility limited to package
    if (visibilityArgument == null) {
      return new BuckVisibilityState(
          Collections.singletonList(buckTargetPattern.asPackageMatchingPattern()));
    }
    BuckListMaker visibilityListMaker =
        PsiTreeUtil.findChildOfType(visibilityArgument, BuckListMaker.class);
    if (visibilityListMaker == null) {
      return new BuckVisibilityState(BuckVisibilityState.VisibleState.UNKNOWN);
    }
    List<String> visibilities =
        visibilityListMaker.getExpressionListOrComprehension().getExpressionList().stream()
            .map(BuckExpression::getStringValue)
            .collect(Collectors.toList());
    if (visibilities.contains(null)) {
      return new BuckVisibilityState(BuckVisibilityState.VisibleState.UNKNOWN);
    }
    if (visibilities.contains("PUBLIC")) {
      return new BuckVisibilityState(BuckVisibilityState.VisibleState.VISIBLE);
    }
    // Convert the raw target strings into BuckTargetPatterns
    return new BuckVisibilityState(
        visibilities.stream()
            .map(
                p ->
                    buckTargetLocator
                        .resolve(buckFile, BuckTargetPattern.parse(p).orElse(null))
                        .orElse(null))
            .collect(Collectors.toList()));
  }
}
