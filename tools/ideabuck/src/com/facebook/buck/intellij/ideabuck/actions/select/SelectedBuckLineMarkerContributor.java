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

package com.facebook.buck.intellij.ideabuck.actions.select;

import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckDotTrailer;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionTrailer;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckIdentifier;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckStatement;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Line markers for a Buck file, with actions available for appropriate Buck targets. */
public class SelectedBuckLineMarkerContributor extends RunLineMarkerContributor {

  @Nullable
  @Override
  public Info getInfo(@NotNull PsiElement psiElement) {
    if (psiElement instanceof LeafPsiElement
        && psiElement.getParent() instanceof BuckIdentifier
        && PsiTreeUtil.getParentOfType(psiElement, BuckArgument.class) == null
        && PsiTreeUtil.getParentOfType(psiElement, BuckDotTrailer.class) == null) {
      BuckStatement buckStatement = PsiTreeUtil.getParentOfType(psiElement, BuckStatement.class);
      if (buckStatement != null) {
        return createInfo(getBuckTarget(buckStatement));
      }
    }
    return null;
  }

  private static String getBuckTargetName(@NotNull BuckStatement buckStatement) {
    return Optional.ofNullable(
            PsiTreeUtil.findChildOfType(buckStatement, BuckFunctionTrailer.class))
        .map(BuckFunctionTrailer::getName)
        .orElse(null);
  }

  @Nullable
  private static BuckTarget getBuckTarget(@NotNull BuckStatement buckStatement) {
    String targetName = getBuckTargetName(buckStatement);
    if (targetName == null) {
      return null;
    }
    VirtualFile buckFile = buckStatement.getContainingFile().getVirtualFile();
    return BuckTargetLocator.getInstance(buckStatement.getProject())
        .findTargetPatternForVirtualFile(buckFile)
        .flatMap(targetPath -> BuckTarget.parse(targetPath + targetName))
        .orElse(null);
  }

  private Info createInfo(@Nullable BuckTarget target) {
    if (target == null) {
      return null;
    }
    return new Info(BuckIcons.BUILD_RUN_TEST, new AnAction[] {}, it -> "Build this target");
  }
}
