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

package com.facebook.buck.intellij.plugin.navigation;

import com.facebook.buck.intellij.plugin.build.BuckBuildTargetUtil;
import com.facebook.buck.intellij.plugin.lang.BuckLanguage;
import com.facebook.buck.intellij.plugin.lang.psi.BuckValue;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandlerBase;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

public class BuckGotoProvider extends GotoDeclarationHandlerBase {

  @Override
  public PsiElement getGotoDeclarationTarget(@Nullable PsiElement source, Editor editor) {
    if (source != null && source.getLanguage() instanceof BuckLanguage) {
      // The parent type of the element must be BuckValue.
      BuckValue value = PsiTreeUtil.getParentOfType(source, BuckValue.class);
      if (value == null) {
        return null;
      }
      final Project project = editor.getProject();
      if (project == null) {
        return null;
      }

      String target = source.getText();
      if (target.startsWith("'") && target.endsWith("'")) {
        target = target.substring(1, target.length() - 1);
      }
      VirtualFile targetBuckFile =
          BuckBuildTargetUtil.getBuckFileFromAbsoluteTarget(project, target);
      if (targetBuckFile == null) {
        return null;
      }
      return PsiManager.getInstance(project).findFile(targetBuckFile);
    }
    return null;
  }
}
