/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.configurations;

import com.intellij.openapi.options.SettingsEditor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;

public class TestConfigurationEditor extends SettingsEditor<TestConfiguration> {
  private final JBTextField mTargets;
  private final JBTextField mAdditionalParams;
  private final JBTextField mTestSelectors;
  private final JPanel root;

  public TestConfigurationEditor() {
    root = new JPanel(new GridBagLayout());
    final JBLabel targetLabel = new JBLabel();
    targetLabel.setText("Targets");
    mTargets = new JBTextField();
    mTargets.getEmptyText().setText("Specify buck targets to test");

    final JBLabel testSelectorLabel = new JBLabel();
    testSelectorLabel.setText("Test selectors (--test-selectors)");
    mTestSelectors = new JBTextField();
    mTestSelectors
        .getEmptyText()
        .setText("Select tests to run using <class>, <#method> or <class#method>.");

    final JBLabel additionalParamsLabel = new JBLabel();
    additionalParamsLabel.setText("Additional params");
    mAdditionalParams = new JBTextField();
    mAdditionalParams.getEmptyText().setText("May be empty");

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
    root.add(testSelectorLabel, constraints);

    constraints.gridx = 1;
    constraints.gridy = 1;
    constraints.weightx = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    root.add(mTestSelectors, constraints);

    constraints.gridx = 0;
    constraints.gridy = 2;
    constraints.weightx = 0;
    constraints.fill = GridBagConstraints.NONE;
    root.add(additionalParamsLabel, constraints);

    constraints.gridx = 1;
    constraints.gridy = 2;
    constraints.weightx = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    root.add(mAdditionalParams, constraints);
  }

  @Override
  protected void resetEditorFrom(@NotNull TestConfiguration configuration) {
    mTargets.setText(configuration.data.targets);
    mAdditionalParams.setText(configuration.data.additionalParams);
    mTestSelectors.setText(configuration.data.testSelectors);
  }

  @Override
  protected void applyEditorTo(@NotNull TestConfiguration configuration) {
    configuration.data.targets = mTargets.getText().trim();
    configuration.data.additionalParams = mAdditionalParams.getText().trim();
    configuration.data.testSelectors = mTestSelectors.getText().trim();
  }

  @NotNull
  @Override
  protected JComponent createEditor() {
    return root;
  }
}
