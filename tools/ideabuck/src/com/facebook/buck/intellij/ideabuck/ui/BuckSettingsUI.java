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

package com.facebook.buck.intellij.ideabuck.ui;

import com.facebook.buck.intellij.ideabuck.config.BuckExecutableDetector;
import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextComponentAccessor;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.Comparing;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.components.JBTextField;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ItemEvent;
import java.util.Optional;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Buck Setting GUI, located in "Preference > Tools > Buck". */
public class BuckSettingsUI extends JPanel {

  public static final String CUSTOMIZED_INSTALL_FLAGS_HINT =
      "input your additional install flags here: eg. --no-cache";

  private TextFieldWithBrowseButton buckPathField;
  private TextFieldWithBrowseButton adbPathField;
  private JBTextField customizedInstallSettingField;
  private JCheckBox showDebug;
  private JCheckBox enableAutoDeps;
  private JCheckBox runAfterInstall;
  private JCheckBox multiInstallMode;
  private JCheckBox uninstallBeforeInstall;
  private JCheckBox customizedInstallSetting;
  private BuckProjectSettingsProvider optionsProvider;

  public BuckSettingsUI(BuckProjectSettingsProvider buckProjectSettingsProvider) {
    optionsProvider = buckProjectSettingsProvider;
    init();
  }

  private TextFieldWithBrowseButton createTextFieldWithBrowseButton(
      String emptyText, String title, String description, Project project) {
    JBTextField textField = new JBTextField();
    textField.getEmptyText().setText(emptyText);
    TextFieldWithBrowseButton field = new TextFieldWithBrowseButton(textField);
    FileChooserDescriptor fileChooserDescriptor =
        new FileChooserDescriptor(true, false, false, false, false, false);
    field.addBrowseFolderListener(
        title,
        description,
        project,
        fileChooserDescriptor,
        TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT);
    return field;
  }

  private void init() {

    final GridBagConstraints constraints = new GridBagConstraints();
    constraints.anchor = GridBagConstraints.FIRST_LINE_START;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.weightx = 1;
    constraints.weighty = 1;

    JPanel container = new JPanel(new GridBagLayout());
    container.setLayout(new GridBagLayout());
    constraints.gridy = 0;
    container.add(initUISettingsSection(), constraints);
    constraints.gridy = 1;
    container.add(initExecutablesSection(), constraints);
    constraints.gridy = 2;
    container.add(initInstallSettingsSection(), constraints);

    this.setLayout(new BorderLayout());
    this.add(container, BorderLayout.NORTH);
  }

  private JPanel initExecutablesSection() {
    BuckExecutableDetector executableDetector = BuckExecutableDetector.newInstance();
    String emptyTextForBuckExecutable;
    try {
      emptyTextForBuckExecutable = "Default: " + executableDetector.getBuckExecutable();
    } catch (RuntimeException e) {
      emptyTextForBuckExecutable = "No buck found on path";
    }
    String emptyTextForAdbExecutable;
    try {
      emptyTextForAdbExecutable = "Default: " + executableDetector.getAdbExecutable();
    } catch (RuntimeException e) {
      emptyTextForAdbExecutable = "No adb found on path";
    }
    buckPathField =
        createTextFieldWithBrowseButton(
            emptyTextForBuckExecutable,
            "Buck Executable",
            "Specify the buck executable to use (for this project)",
            null);

    adbPathField =
        createTextFieldWithBrowseButton(
            emptyTextForAdbExecutable,
            "Adb Executable",
            "Specify the adb executable to use (for this project)",
            optionsProvider.getProject());

    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(IdeBorderFactory.createTitledBorder("Executables", true));

    GridBagConstraints leftSide = new GridBagConstraints();
    leftSide.fill = GridBagConstraints.NONE;
    leftSide.anchor = GridBagConstraints.LINE_START;
    leftSide.gridx = 0;
    leftSide.weightx = 0;

    GridBagConstraints rightSide = new GridBagConstraints();
    rightSide.fill = GridBagConstraints.HORIZONTAL;
    rightSide.anchor = GridBagConstraints.LINE_START;
    leftSide.gridx = 1;
    rightSide.weightx = 1;

    leftSide.gridy = rightSide.gridy = 0;
    panel.add(new JLabel("Buck Executable:"), leftSide);
    panel.add(buckPathField, rightSide);

    leftSide.gridy = rightSide.gridy = 1;
    panel.add(new JLabel("Adb Executable:"), leftSide);
    panel.add(adbPathField, rightSide);

    return panel;
  }

  private JPanel initUISettingsSection() {
    showDebug = new JCheckBox("Show debug in tool window");
    enableAutoDeps = new JCheckBox("Enable auto dependencies");

    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(IdeBorderFactory.createTitledBorder("UI Settings", true));

    GridBagConstraints constraints = new GridBagConstraints();
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.anchor = GridBagConstraints.LINE_START;
    constraints.weightx = 1;

    constraints.gridy = 0;
    panel.add(showDebug, constraints);

    constraints.gridy = 1;
    panel.add(enableAutoDeps, constraints);
    return panel;
  }

  private JPanel initInstallSettingsSection() {
    runAfterInstall = new JCheckBox("Run after install (-r)");
    multiInstallMode = new JCheckBox("Multi-install mode (-x)");
    uninstallBeforeInstall = new JCheckBox("Uninstall before installing (-u)");
    customizedInstallSetting = new JCheckBox("Use customized install setting:  ");
    customizedInstallSettingField = new JBTextField();
    customizedInstallSettingField.getEmptyText().setText(CUSTOMIZED_INSTALL_FLAGS_HINT);
    customizedInstallSettingField.setEnabled(false);

    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(IdeBorderFactory.createTitledBorder("Install Settings", true));

    GridBagConstraints leftSide = new GridBagConstraints();
    leftSide.fill = GridBagConstraints.NONE;
    leftSide.anchor = GridBagConstraints.LINE_START;
    leftSide.gridx = 0;
    leftSide.weightx = 0;

    GridBagConstraints rightSide = new GridBagConstraints();
    rightSide.fill = GridBagConstraints.HORIZONTAL;
    rightSide.anchor = GridBagConstraints.LINE_START;
    leftSide.gridx = 1;
    rightSide.weightx = 1;

    leftSide.gridy = rightSide.gridy = 0;
    panel.add(runAfterInstall, leftSide);

    leftSide.gridy = rightSide.gridy = 1;
    panel.add(multiInstallMode, leftSide);

    leftSide.gridy = rightSide.gridy = 2;
    panel.add(uninstallBeforeInstall, leftSide);

    leftSide.gridy = rightSide.gridy = 3;
    panel.add(customizedInstallSetting, leftSide);
    panel.add(customizedInstallSettingField, rightSide);

    customizedInstallSetting.addItemListener(
        e -> {
          if (e.getStateChange() == ItemEvent.SELECTED) {
            customizedInstallSettingField.setEnabled(true);
            runAfterInstall.setEnabled(false);
            multiInstallMode.setEnabled(false);
            uninstallBeforeInstall.setEnabled(false);
          } else {
            customizedInstallSettingField.setEnabled(false);
            runAfterInstall.setEnabled(true);
            multiInstallMode.setEnabled(true);
            uninstallBeforeInstall.setEnabled(true);
          }
        });
    return panel;
  }

  // When displaying an empty Optional in a text field, use "".
  private String optionalToText(Optional<String> optional) {
    return optional.orElse("");
  }

  // Empty or all-whitespace text fields should be parsed as Optional.empty()
  private Optional<String> textToOptional(String text) {
    if (text == null || text.trim().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(text);
  }

  public boolean isModified() {
    return !Comparing.equal(
            buckPathField.getText().trim(),
            optionalToText(optionsProvider.getBuckExecutableOverride()))
        || !Comparing.equal(
            adbPathField.getText().trim(),
            optionalToText(optionsProvider.getAdbExecutableOverride()))
        || optionsProvider.isRunAfterInstall() != runAfterInstall.isSelected()
        || optionsProvider.isShowDebugWindow() != showDebug.isSelected()
        || optionsProvider.isAutoDepsEnabled() != enableAutoDeps.isSelected()
        || optionsProvider.isMultiInstallMode() != multiInstallMode.isSelected()
        || optionsProvider.isUninstallBeforeInstalling() != uninstallBeforeInstall.isSelected()
        || optionsProvider.isUseCustomizedInstallSetting() != customizedInstallSetting.isSelected()
        || !optionsProvider
            .getCustomizedInstallSettingCommand()
            .equals(customizedInstallSettingField.getText());
  }

  public void apply() {
    optionsProvider.setBuckExecutableOverride(textToOptional(buckPathField.getText()));
    optionsProvider.setAdbExecutableOverride(textToOptional(adbPathField.getText()));
    optionsProvider.setShowDebugWindow(showDebug.isSelected());
    optionsProvider.setAutoDepsEnabled(enableAutoDeps.isSelected());
    optionsProvider.setRunAfterInstall(runAfterInstall.isSelected());
    optionsProvider.setMultiInstallMode(multiInstallMode.isSelected());
    optionsProvider.setUninstallBeforeInstalling(uninstallBeforeInstall.isSelected());
    optionsProvider.setUseCustomizedInstallSetting(customizedInstallSetting.isSelected());
    optionsProvider.setCustomizedInstallSettingCommand(customizedInstallSettingField.getText());
  }

  public void reset() {
    adbPathField.setText(optionsProvider.getAdbExecutableOverride().orElse(""));
    buckPathField.setText(optionsProvider.getBuckExecutableOverride().orElse(""));
    adbPathField.setText(optionsProvider.getAdbExecutableOverride().orElse(""));
    showDebug.setSelected(optionsProvider.isShowDebugWindow());
    enableAutoDeps.setSelected(optionsProvider.isAutoDepsEnabled());
    runAfterInstall.setSelected(optionsProvider.isRunAfterInstall());
    multiInstallMode.setSelected(optionsProvider.isMultiInstallMode());
    uninstallBeforeInstall.setSelected(optionsProvider.isUninstallBeforeInstalling());
    customizedInstallSetting.setSelected(optionsProvider.isUseCustomizedInstallSetting());
    customizedInstallSettingField.setText(optionsProvider.getCustomizedInstallSettingCommand());
  }
}
