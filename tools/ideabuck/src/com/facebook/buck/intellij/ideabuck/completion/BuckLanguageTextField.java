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
import com.intellij.codeInsight.daemon.impl.analysis.FileHighlightingSetting;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightLevelUtil;
import com.intellij.lang.Language;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.ui.LanguageTextField;
import org.jetbrains.annotations.Nullable;

public class BuckLanguageTextField extends LanguageTextField {

  public BuckLanguageTextField(Project project) {
    super(BuckLanguage.INSTANCE, project, "", new ConfigurationEditorDocumentCreator());
  }

  private static class ConfigurationEditorDocumentCreator
      extends LanguageTextField.SimpleDocumentCreator {
    @Override
    public Document createDocument(String value, @Nullable Language language, Project project) {
      return LanguageTextField.createDocument(value, language, project, this);
    }

    @Override
    public void customizePsiFile(PsiFile file) {
      BuckCompletionUtils.setNonBuckFile(file);
      HighlightLevelUtil.forceRootHighlighting(file, FileHighlightingSetting.SKIP_HIGHLIGHTING);
    }
  }
}
