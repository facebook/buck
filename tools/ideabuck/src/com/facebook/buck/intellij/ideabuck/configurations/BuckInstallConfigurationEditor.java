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

import com.intellij.execution.ui.ClassBrowser;
import com.intellij.ide.util.ClassFilter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.ui.EditorTextFieldWithBrowseButton;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import java.awt.*;
import java.awt.event.ItemEvent;
import javax.swing.*;
import org.jetbrains.annotations.NotNull;

public class BuckInstallConfigurationEditor
    extends AbstractConfigurationEditor<BuckInstallConfiguration> {

  private final JComboBox<String> mActivitySetting;
  private final EditorTextFieldWithBrowseButton mActivityClass;
  private final JBTextField mProcessName;
  private final JPanel root;

  public static final String ACTIVITY_NONE = "None";
  public static final String ACTIVITY_DEFAULT = "Default Activity";
  public static final String ACTIVITY_SPECIFIED = "Specified Activity";

  public BuckInstallConfigurationEditor(Project project) {
    super(project);
    root = getRoot();
    JBLabel activitySettingLabel = new JBLabel();
    activitySettingLabel.setText("Launch");
    JBLabel activityClassLabel = new JBLabel();
    activityClassLabel.setText("Specify Activity");
    JBLabel processNameLabel = new JBLabel();
    processNameLabel.setText("Process name for debugging");

    mActivityClass = new EditorTextFieldWithBrowseButton(project, true);
    ActivityClassBrowser browser = new ActivityClassBrowser(project, "Select Activity");
    browser.setField(mActivityClass);

    mActivitySetting = new ComboBox<>();
    mActivitySetting.setModel(
        new DefaultComboBoxModel<>(
            new String[] {ACTIVITY_NONE, ACTIVITY_DEFAULT, ACTIVITY_SPECIFIED}));
    mActivitySetting.addItemListener(
        e -> {
          if (e.getStateChange() == ItemEvent.SELECTED) {
            String item = (String) e.getItem();
            if (item.equals(ACTIVITY_SPECIFIED)) {
              activityClassLabel.setVisible(true);
              mActivityClass.setVisible(true);
            } else {
              activityClassLabel.setVisible(false);
              mActivityClass.setVisible(false);
            }
          }
        });
    mActivitySetting.setSelectedItem(ACTIVITY_DEFAULT);
    mProcessName = new JBTextField();
    mProcessName
        .getEmptyText()
        .setText("If empty, a prompt for the process name will be shown during debugging");

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
    root.add(activitySettingLabel, constraints);

    constraints.gridx = 1;
    constraints.gridy = GridBagConstraints.RELATIVE;
    constraints.weightx = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    root.add(mActivitySetting, constraints);

    constraints.gridx = 0;
    constraints.gridy = GridBagConstraints.RELATIVE;
    constraints.weightx = 0;
    constraints.fill = GridBagConstraints.NONE;
    root.add(activityClassLabel, constraints);

    constraints.gridx = 1;
    constraints.gridy = GridBagConstraints.RELATIVE;
    constraints.weightx = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    root.add(mActivityClass, constraints);

    constraints.gridx = 0;
    constraints.gridy = GridBagConstraints.RELATIVE;
    constraints.weightx = 0;
    constraints.fill = GridBagConstraints.NONE;
    root.add(processNameLabel, constraints);

    constraints.gridx = 1;
    constraints.gridy = GridBagConstraints.RELATIVE;
    constraints.weightx = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    root.add(mProcessName, constraints);
  }

  @Override
  protected void resetEditorFrom(@NotNull BuckInstallConfiguration configuration) {
    super.resetEditorFrom(configuration);
    mActivitySetting.setSelectedItem(configuration.data.activitySetting);
    mActivityClass.setText(configuration.data.activityClass);
    mProcessName.setText(configuration.data.processName);
  }

  @Override
  protected void applyEditorTo(@NotNull BuckInstallConfiguration configuration) {
    super.applyEditorTo(configuration);
    configuration.data.activitySetting = (String) mActivitySetting.getSelectedItem();
    configuration.data.activityClass = mActivityClass.getText();
    configuration.data.processName = mProcessName.getText();
  }

  /** Browses through activity classes in the current project */
  private static class ActivityClassBrowser extends ClassBrowser {
    public ActivityClassBrowser(Project project, String title) {
      super(project, title);
    }

    @Override
    protected ClassFilter.ClassFilterWithScope getFilter() throws NoFilterException {
      return new ClassFilter.ClassFilterWithScope() {

        @Override
        public boolean isAccepted(PsiClass psiClass) {
          return InheritanceUtil.isInheritor(psiClass, "android.app.Activity");
        }

        @Override
        public GlobalSearchScope getScope() {
          return GlobalSearchScope.allScope(getProject());
        }
      };
    }

    @Override
    protected PsiClass findClass(String s) {
      return JavaPsiFacade.getInstance(getProject())
          .findClass(s, GlobalSearchScope.allScope(getProject()));
    }
  }
}
