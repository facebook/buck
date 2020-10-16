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

package com.facebook.buck.intellij.ideabuck.configurations;

import com.facebook.buck.intellij.ideabuck.lang.BuckLanguage;
import com.intellij.codeInsight.daemon.impl.analysis.FileHighlightingSetting;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightLevelUtil;
import com.intellij.lang.Language;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiFile;
import com.intellij.ui.LanguageTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import java.awt.*;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Base menu class for editing Buck command run configs */
public abstract class AbstractConfigurationEditor<T extends AbstractConfiguration>
    extends SettingsEditor<T> {
  private static final Key<String> FROM_RUN_CONFIG_EDITOR = Key.create("FROM_RUN_CONFIG_EDITOR");

  private final LanguageTextField mTargets;
  private final JBTextField mAdditionalParams;
  private final JBTextField mBuckExecutablePath;
  private final JPanel root;

  public AbstractConfigurationEditor(Project project) {

    root = new JPanel(new GridBagLayout());
    final JBLabel targetLabel = new JBLabel();
    targetLabel.setText("Targets");
    mTargets =
        new LanguageTextField(
            BuckLanguage.INSTANCE, project, "", new ConfigurationEditorDocumentCreator());
    mTargets.setToolTipText("Specify buck targets");

    final JBLabel additionalParamsLabel = new JBLabel();
    additionalParamsLabel.setText("Additional params");
    mAdditionalParams = new JBTextField();
    mAdditionalParams.getEmptyText().setText("May be empty");

    final JBLabel buckExecutablePathLabel = new JBLabel();
    buckExecutablePathLabel.setText("Buck executable path");
    mBuckExecutablePath = new JBTextField();
    mBuckExecutablePath.getEmptyText().setText("Default executable will be used if empty");

    final GridBagConstraints constraints =
        new GridBagConstraints(
            0,
            0,
            1,
            1,
            0,
            0,
            GridBagConstraints.WEST,
            GridBagConstraints.NONE,
            JBUI.emptyInsets(),
            0,
            0);
    constraints.insets = JBUI.insetsRight(8);
    root.add(targetLabel, constraints);
    constraints.gridx = 1;
    constraints.gridy = 0;
    constraints.weightx = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    root.add(mTargets, constraints);

    constraints.gridx = 0;
    constraints.gridy = 1;
    constraints.weightx = 0;
    constraints.fill = GridBagConstraints.NONE;
    root.add(additionalParamsLabel, constraints);

    constraints.gridx = 1;
    constraints.gridy = 1;
    constraints.weightx = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    root.add(mAdditionalParams, constraints);

    constraints.gridx = 0;
    constraints.gridy = 2;
    constraints.weightx = 0;
    constraints.fill = GridBagConstraints.NONE;
    root.add(buckExecutablePathLabel, constraints);

    constraints.gridx = 1;
    constraints.gridy = 2;
    constraints.weightx = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    root.add(mBuckExecutablePath, constraints);
  }

  @Override
  protected void resetEditorFrom(@NotNull T configuration) {
    mTargets.setText(configuration.data.targets);
    mAdditionalParams.setText(configuration.data.additionalParams);
    mBuckExecutablePath.setText(configuration.data.buckExecutablePath);
  }

  @Override
  protected void applyEditorTo(@NotNull T configuration) {
    configuration.data.targets = mTargets.getText().trim();
    configuration.data.additionalParams = mAdditionalParams.getText().trim();
    configuration.data.buckExecutablePath = mBuckExecutablePath.getText().trim();
  }

  protected JPanel getRoot() {
    return root;
  }

  @NotNull
  @Override
  protected JComponent createEditor() {
    return root;
  }

  public static boolean isFileFromRunConfigurationEditor(PsiFile psiFile) {
    return psiFile.getUserData(FROM_RUN_CONFIG_EDITOR) != null;
  }

  private static class ConfigurationEditorDocumentCreator
      extends LanguageTextField.SimpleDocumentCreator {
    @Override
    public Document createDocument(String value, @Nullable Language language, Project project) {
      return LanguageTextField.createDocument(value, language, project, this);
    }

    @Override
    public void customizePsiFile(PsiFile file) {
      file.putUserData(FROM_RUN_CONFIG_EDITOR, "");
      HighlightLevelUtil.forceRootHighlighting(file, FileHighlightingSetting.SKIP_HIGHLIGHTING);
    }
  }
}
