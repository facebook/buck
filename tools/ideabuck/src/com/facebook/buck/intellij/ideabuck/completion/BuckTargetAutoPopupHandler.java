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

package com.facebook.buck.intellij.ideabuck.completion;

import com.facebook.buck.intellij.ideabuck.lang.BuckLanguage;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.facebook.buck.intellij.ideabuck.util.BuckPsiUtils;
import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/** Allow autocompletion to show up when users type in ":" or "/" in BUCK/bzl files */
public class BuckTargetAutoPopupHandler extends TypedHandlerDelegate {

  @NotNull
  @Override
  public Result checkAutoPopup(
      char charTyped, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    if (!file.getLanguage().isKindOf(BuckLanguage.INSTANCE)
        || (charTyped != '/' && charTyped != ':')) {
      return Result.CONTINUE;
    }
    final PsiElement at = file.findElementAt(editor.getCaretModel().getOffset());
    if (at == null
        || !BuckPsiUtils.hasElementType(
            at,
            BuckTypes.APOSTROPHED_STRING,
            BuckTypes.APOSTROPHED_RAW_STRING,
            BuckTypes.TRIPLE_APOSTROPHED_STRING,
            BuckTypes.TRIPLE_APOSTROPHED_RAW_STRING,
            BuckTypes.QUOTED_STRING,
            BuckTypes.QUOTED_RAW_STRING,
            BuckTypes.TRIPLE_QUOTED_STRING,
            BuckTypes.TRIPLE_QUOTED_RAW_STRING)) {
      return Result.CONTINUE;
    }
    AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
    return Result.STOP;
  }
}
