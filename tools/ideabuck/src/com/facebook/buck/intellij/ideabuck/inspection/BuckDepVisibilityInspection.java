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

import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionTrailer;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckListMaker;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckString;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckVisitor;
import com.facebook.buck.intellij.ideabuck.util.BuckPsiUtils;
import com.facebook.buck.intellij.ideabuck.visibility.BuckVisibilityState;
import com.google.common.annotations.VisibleForTesting;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class BuckDepVisibilityInspection extends LocalInspectionTool {
  @Override
  public PsiElementVisitor buildVisitor(
      ProblemsHolder holder, boolean isOnTheFly, LocalInspectionToolSession session) {
    return new Visitor(holder);
  }

  @Override
  public @Nullable String getStaticDescription() {
    return "Checks visibility of Buck deps";
  }

  public static class Visitor extends BuckVisitor {
    public static final Set<String> SUPPORTED_PARAMETER_NAMES =
        new HashSet<>(
            Arrays.asList("deps", "exported_deps", "provided_deps", "exported_provided_deps"));
    private static final String DEP_VISIBILITY_FIX_DESCRIPTION =
        "Not visible to specified Buck dep";
    private static final ProblemHighlightType DEP_VISIBILITY_FIX_HIGHLIGHT_TYPE =
        ProblemHighlightType.GENERIC_ERROR;
    private final ProblemsHolder mHolder;

    public Visitor(ProblemsHolder holder) {
      mHolder = holder;
    }

    @Override
    public void visitString(BuckString buckString) {
      Project project = buckString.getProject();
      BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
      BuckTargetPattern parentTargetPattern =
          getParentBuckTargetPattern(buckTargetLocator, buckString);
      if (parentTargetPattern == null) {
        return;
      }
      BuckTargetPattern depTargetPattern =
          getBuckTargetPatternFromBuckString(buckTargetLocator, buckString);
      if (depTargetPattern == null) {
        return;
      }
      VirtualFile depTargetBuckFile =
          buckTargetLocator.findVirtualFileForTargetPattern(depTargetPattern).orElse(null);
      if (depTargetBuckFile == null) {
        return;
      }
      Pair<BuckVisibilityState, BuckListMaker> visibilityStateListPair =
          getVisibilityStateWithList(
              project, buckTargetLocator, depTargetBuckFile, depTargetPattern);
      BuckVisibilityState buckVisibilityState = visibilityStateListPair.getFirst();
      BuckListMaker buckListMaker = visibilityStateListPair.getSecond();
      if (buckVisibilityState.getVisibility(parentTargetPattern.asBuckTarget().orElse(null))
              == BuckVisibilityState.VisibleState.NOT_VISIBLE
          && buckListMaker != null) {
        mHolder.registerProblem(
            buckString,
            DEP_VISIBILITY_FIX_DESCRIPTION,
            DEP_VISIBILITY_FIX_HIGHLIGHT_TYPE,
            new BuckInsertTargetToVisibilityQuickFix(
                parentTargetPattern, depTargetPattern, buckListMaker));
      }
    }

    @VisibleForTesting
    @Nullable
    static BuckTargetPattern getParentBuckTargetPattern(
        BuckTargetLocator buckTargetLocator, PsiElement psiElement) {
      BuckFunctionTrailer buckFunctionTrailer =
          PsiTreeUtil.getParentOfType(psiElement, BuckFunctionTrailer.class);
      if (buckFunctionTrailer == null) {
        return null;
      }
      String targetName = buckFunctionTrailer.getName();
      VirtualFile buckFile = psiElement.getContainingFile().getVirtualFile();
      BuckTargetPattern filePattern =
          buckTargetLocator.findTargetPatternForVirtualFile(buckFile).orElse(null);
      if (filePattern == null) {
        return null;
      }
      String targetPath = filePattern.toString();
      return BuckTargetPattern.parse(targetPath + targetName).orElse(null);
    }

    @VisibleForTesting
    @Nullable
    static BuckTargetPattern getBuckTargetPatternFromBuckString(
        BuckTargetLocator buckTargetLocator, BuckString buckString) {
      VirtualFile buckFile = buckString.getContainingFile().getVirtualFile();
      // The parameter name should be one of the values from SUPPORTED_PARAMETER_NAMES
      BuckArgument argument = PsiTreeUtil.getParentOfType(buckString, BuckArgument.class);
      if (argument == null
          || argument.getIdentifier() == null
          || !SUPPORTED_PARAMETER_NAMES.contains(argument.getIdentifier().getText())) {
        return null;
      }
      BuckTargetPattern buckTargetPattern =
          BuckTargetPattern.parse(buckString.getValue()).orElse(null);
      if (buckTargetPattern == null) {
        return null;
      }
      return buckTargetLocator.resolve(buckFile, buckTargetPattern).orElse(null);
    }

    @VisibleForTesting
    static Pair<BuckVisibilityState, BuckListMaker> getVisibilityStateWithList(
        Project project,
        BuckTargetLocator buckTargetLocator,
        VirtualFile buckFile,
        BuckTargetPattern buckTargetPattern) {
      PsiFile psiFile = PsiUtil.getPsiFile(project, buckFile);
      BuckFunctionTrailer buckFunctionTrailer =
          BuckPsiUtils.findTargetInPsiTree(psiFile, buckTargetPattern.getRuleName().orElse(null));
      // If build rule can't be found, assume visibility cannot be determined
      if (buckFunctionTrailer == null) {
        return new Pair<>(new BuckVisibilityState(BuckVisibilityState.VisibleState.UNKNOWN), null);
      }
      // Find the list of deps listed under visibility
      BuckArgument visibilityArgument =
          PsiTreeUtil.findChildrenOfType(buckFunctionTrailer, BuckArgument.class).stream()
              .filter(buckArg -> "visibility".equals(buckArg.getName()))
              .findFirst()
              .orElse(null);
      // Visibility wasn't found, assume visibility limited to package
      if (visibilityArgument == null) {
        return new Pair<>(
            new BuckVisibilityState(
                Collections.singletonList(buckTargetPattern.asPackageMatchingPattern())),
            null);
      }
      BuckListMaker visibilityListMaker =
          PsiTreeUtil.findChildOfType(visibilityArgument, BuckListMaker.class);
      if (visibilityListMaker == null) {
        return new Pair<>(new BuckVisibilityState(BuckVisibilityState.VisibleState.UNKNOWN), null);
      }
      List<String> visibilities =
          visibilityListMaker.getExpressionListOrComprehension().getExpressionList().stream()
              .map(BuckExpression::getStringValue)
              .collect(Collectors.toList());
      if (visibilities.contains(null)) {
        return new Pair<>(
            new BuckVisibilityState(BuckVisibilityState.VisibleState.UNKNOWN), visibilityListMaker);
      }
      if (visibilities.contains("PUBLIC")) {
        return new Pair<>(
            new BuckVisibilityState(BuckVisibilityState.VisibleState.VISIBLE), visibilityListMaker);
      }
      // Convert the raw target strings into BuckTargetPatterns
      return new Pair<>(
          new BuckVisibilityState(
              Stream.concat(
                      visibilities.stream()
                          .map(
                              v ->
                                  BuckTargetPattern.parse(v)
                                      .flatMap(p -> buckTargetLocator.resolve(buckFile, p))
                                      .orElse(null))
                          .filter(Objects::nonNull),
                      // Target should be visible if in same package
                      Stream.of(buckTargetPattern.asPackageMatchingPattern()))
                  .collect(Collectors.toList())),
          visibilityListMaker);
    }
  }
}
