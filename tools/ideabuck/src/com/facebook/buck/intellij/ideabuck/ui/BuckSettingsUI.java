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

import com.facebook.buck.intellij.ideabuck.config.BuckCell;
import com.facebook.buck.intellij.ideabuck.config.BuckExecutableDetector;
import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.icons.AllIcons.Actions;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task.Modal;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextComponentAccessor;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.AnActionButtonRunnable;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.TableView;
import com.intellij.util.ui.ListTableModel;
import com.intellij.util.ui.LocalPathCellEditor;
import com.intellij.util.ui.table.TableModelEditor.EditableColumnInfo;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ItemEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.table.TableCellEditor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Buck Setting GUI, located in "Preference > Tools > Buck". */
public class BuckSettingsUI extends JPanel {

  private static Logger LOG = Logger.getInstance(BuckSettingsUI.class);

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
  private ListTableModel<BuckCell> cellTableModel;
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
    constraints.gridy = 3;
    container.add(initBuckCellSection(), constraints);

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

  private static final EditableColumnInfo<BuckCell, String> CELL_NAME_COLUMN =
      new EditableColumnInfo<BuckCell, String>("cell name") {
        @Override
        public String valueOf(BuckCell buckCell) {
          return buckCell.getName();
        }

        @Override
        public void setValue(BuckCell buckCell, String value) {
          buckCell.setName(value);
        }
      };

  private static final EditableColumnInfo<BuckCell, String> ROOT_COLUMN =
      new EditableColumnInfo<BuckCell, String>("cell root directory") {
        @Override
        public String valueOf(BuckCell buckCell) {
          return buckCell.getRoot();
        }

        @Override
        public void setValue(BuckCell buckCell, String value) {
          buckCell.setRoot(value);
        }

        @Nullable
        @Override
        public TableCellEditor getEditor(BuckCell buckCell) {
          return new LocalPathCellEditor()
              .fileChooserDescriptor(FileChooserDescriptorFactory.createSingleFolderDescriptor())
              .normalizePath(true);
        }
      };

  private static final EditableColumnInfo<BuckCell, String> BUILD_FILENAME_COLUMN =
      new EditableColumnInfo<BuckCell, String>("build file name") {
        @Override
        public String valueOf(BuckCell buckCell) {
          return buckCell.getBuildFileName();
        }

        @Override
        public void setValue(BuckCell buckCell, String value) {
          buckCell.setBuildFileName(value);
        }
      };

  private JPanel initBuckCellSection() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(IdeBorderFactory.createTitledBorder("Cells", true));
    cellTableModel =
        new ListTableModel<BuckCell>(CELL_NAME_COLUMN, ROOT_COLUMN, BUILD_FILENAME_COLUMN);
    cellTableModel.setItems(optionsProvider.getCells());
    TableView<BuckCell> cellTable = new TableView<BuckCell>(cellTableModel);
    ToolbarDecorator decorator =
        ToolbarDecorator.createDecorator(cellTable)
            .setAddAction(
                new AnActionButtonRunnable() {
                  @Override
                  public void run(AnActionButton button) {
                    final FileChooserDescriptor dirChooser =
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Select root directory of buck cell");
                    Project project = optionsProvider.getProject();
                    FileChooser.chooseFile(
                        dirChooser,
                        project,
                        BuckSettingsUI.this,
                        project.getBaseDir(),
                        file -> {
                          BuckCell newCell =
                              discoverCell(file.getName(), Paths.get(file.getPath()));
                          cellTableModel.addRow(newCell);
                        });
                  }
                })
            .addExtraAction(
                new AnActionButton("Automatically discover cells", Actions.Find) {
                  @Override
                  public void actionPerformed(AnActionEvent anActionEvent) {
                    discoverCells();
                  }
                });
    JBLabel label = new JBLabel("By default, commands take place in the topmost cell");
    panel.add(label, BorderLayout.NORTH);
    panel.add(decorator.createPanel(), BorderLayout.CENTER);
    return panel;
  }

  private void discoverCells() {
    final FileChooserDescriptor dirChooser =
        FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select any directory within a buck cell");
    Project project = optionsProvider.getProject();
    String buckExecutableText = buckPathField.getText().trim();
    String buckExecutable;
    if (buckExecutableText.isEmpty()) {
      buckExecutable = BuckExecutableDetector.newInstance().getBuckExecutable();
    } else {
      buckExecutable = buckExecutableText;
    }
    VirtualFile defaultCell =
        FileChooser.chooseFile(dirChooser, BuckSettingsUI.this, project, project.getBaseDir());
    ProgressManager.getInstance()
        .run(
            new Modal(project, "Autodetecting buck cells", false) {
              @Override
              public void run(@NotNull ProgressIndicator progressIndicator) {
                discoverCells(buckExecutable, defaultCell, progressIndicator);
              }
            });
  }

  private void discoverCells(
      String buckExecutable, VirtualFile defaultCell, ProgressIndicator progressIndicator) {
    try {
      progressIndicator.setIndeterminate(true);
      progressIndicator.setText("Finding root for " + defaultCell.getName());
      Path mainCellRoot =
          Paths.get(
              new BufferedReader(
                      new InputStreamReader(
                          Runtime.getRuntime()
                              .exec(
                                  new String[] {buckExecutable, "root"},
                                  null,
                                  new File(defaultCell.getPath()))
                              .getInputStream()))
                  .readLine());
      progressIndicator.setText("Finding other cells visible from " + mainCellRoot.getFileName());
      Gson gson = new Gson();
      Type type = new TypeToken<Map<String, String>>() {}.getType();
      Map<String, String> config;
      try (InputStreamReader reader =
          new InputStreamReader(
              Runtime.getRuntime()
                  .exec(
                      new String[] {buckExecutable, "audit", "cell", "--json"},
                      null,
                      mainCellRoot.toFile())
                  .getInputStream())) {
        config = gson.fromJson(reader, type);
      }
      List<BuckCell> cells = new ArrayList<>();
      for (Map.Entry<String, String> entry : config.entrySet()) {
        String name = entry.getKey();
        Path cellRoot = mainCellRoot.resolve(entry.getValue()).normalize();
        progressIndicator.setText("Checking cell " + name);
        BuckCell cell = discoverCell(name, cellRoot);
        if (mainCellRoot.equals(cellRoot)) {
          cells.add(0, cell); // put default cell at front of cell list
        } else {
          cells.add(cell);
        }
      }
      if (cells.isEmpty()) {
        BuckCell cell = new BuckCell();
        cell.setName("");
        cell.setRoot(mainCellRoot.toString());
        cells.add(new BuckCell());
      }
      cellTableModel.setItems(cells);
    } catch (IOException e) {
      LOG.error("Failed to autodiscover cells", e);
    }
  }

  @Nonnull
  private BuckCell discoverCell(String name, Path cellRoot) {
    String buckExecutable = buckPathField.getText().trim();
    Gson gson = new Gson();
    Type type = new TypeToken<Map<String, String>>() {}.getType();
    BuckCell cell = new BuckCell();
    cell.setName(name);
    cell.setRoot(cellRoot.toString());
    try (InputStreamReader reader =
        new InputStreamReader(
            Runtime.getRuntime()
                .exec(
                    new String[] {buckExecutable, "audit", "config", "buildfile.name", "--json"},
                    null,
                    cellRoot.toFile())
                .getInputStream())) {
      Map<String, String> cellConfig = gson.fromJson(reader, type);
      Optional.ofNullable(cellConfig.get("buildfile.name")).ifPresent(cell::setBuildFileName);
    } catch (IOException e) {
      LOG.error("Failed to autodiscover cell at " + cellRoot, e);
    }
    return cell;
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
            .equals(customizedInstallSettingField.getText())
        || !optionsProvider.getCells().equals(cellTableModel.getItems());
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
    optionsProvider.setCells((List<BuckCell>) cellTableModel.getItems());
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
    cellTableModel.setItems(optionsProvider.getCells());
  }
}
