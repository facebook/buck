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

import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import java.awt.GridBagConstraints;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;

public class BuckTestConfigurationEditor
    extends AbstractConfigurationEditor<BuckTestConfiguration> {
  private final JBTextField mTestSelectors;
  private final JPanel root;

  public BuckTestConfigurationEditor() {
    super();
    root = getRoot();
    final JBLabel testSelectorLabel = new JBLabel();
    testSelectorLabel.setText("Test selectors (--test-selectors)");
    mTestSelectors = new JBTextField();
    mTestSelectors
        .getEmptyText()
        .setText("Select tests to run using <class>, <#method> or <class#method>.");

    final GridBagConstraints constraints =
        new GridBagConstraints(
            0,
            GridBagConstraints.RELATIVE,
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
    root.add(testSelectorLabel, constraints);

    constraints.gridx = 1;
    constraints.gridy = GridBagConstraints.RELATIVE;
    constraints.weightx = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    root.add(mTestSelectors, constraints);
  }

  @Override
  protected void resetEditorFrom(@NotNull BuckTestConfiguration configuration) {
    super.resetEditorFrom(configuration);
    mTestSelectors.setText(configuration.data.testSelectors);
  }

  @Override
  protected void applyEditorTo(@NotNull BuckTestConfiguration configuration) {
    super.applyEditorTo(configuration);
    configuration.data.testSelectors = mTestSelectors.getText().trim();
  }
}
