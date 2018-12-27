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
import com.facebook.buck.intellij.ideabuck.config.BuckCellSettingsProvider;
import com.facebook.buck.intellij.ideabuck.config.BuckExecutableDetector;
import com.facebook.buck.intellij.ideabuck.config.BuckExecutableSettingsProvider;
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
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextComponentAccessor;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.TableView;
import com.intellij.util.ui.ListTableModel;
import com.intellij.util.ui.LocalPathCellEditor;
import com.intellij.util.ui.table.TableModelEditor.EditableColumnInfo;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
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
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
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
  private TextFieldWithBrowseButton buildifierPathField;
  private TextFieldWithBrowseButton buildozerPathField;
  private JBTextField customizedInstallSettingField;
  private JCheckBox showDebug;
  private JCheckBox runAfterInstall;
  private JCheckBox multiInstallMode;
  private JCheckBox uninstallBeforeInstall;
  private JCheckBox customizedInstallSetting;
  private ListTableModel<BuckCell> cellTableModel;
  private BuckCellSettingsProvider buckCellSettingsProvider;
  private BuckExecutableSettingsProvider buckExecutableSettingsProvider;
  private BuckProjectSettingsProvider buckProjectSettingsProvider;
  private BuckExecutableDetector executableDetector;

  public BuckSettingsUI(
      BuckCellSettingsProvider buckCellSettingsProvider,
      BuckExecutableSettingsProvider buckExecutableSettingsProvider,
      BuckProjectSettingsProvider buckProjectSettingsProvider) {
    this.buckCellSettingsProvider = buckCellSettingsProvider;
    this.buckExecutableSettingsProvider = buckExecutableSettingsProvider;
    this.buckProjectSettingsProvider = buckProjectSettingsProvider;
    executableDetector = BuckExecutableDetector.newInstance();
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

  private void checkFieldIsExecutable(
      String name, JTextField textField, Optional<String> defaultValue) {
    String text = textField.getText();
    String executable = textToOptional(text).orElse(defaultValue.orElse(null));
    if (executable == null) {
      Messages.showErrorDialog(
          getProject(),
          "No " + name + " executable was specified or auto-detected for this field.",
          "No Executable Specified");
    } else {
      try {
        String found = executableDetector.getNamedExecutable(executable);
        Messages.showInfoMessage(
            getProject(),
            found + " appears to be a valid executable",
            "Found Executable " + executable);
      } catch (RuntimeException e) {
        Messages.showErrorDialog(
            getProject(),
            executable + " could not be resolved to a valid executable.",
            "Invalid Executable");
      }
    }
  }

  private JButton createExecutableFieldTestingButton(
      String executableName,
      Optional<String> defaultExecutable,
      TextFieldWithBrowseButton executableField) {
    JButton button = new JButton();
    button.setText("Test " + executableName);
    button.setToolTipText("Verify that the supplied " + executableName + " is a valid executable");
    button.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            checkFieldIsExecutable(
                executableName, executableField.getTextField(), defaultExecutable);
          }
        });
    return button;
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

  private Project getProject() {
    return buckProjectSettingsProvider.getProject();
  }

  private Optional<String> detectBuckExecutable() {
    try {
      return Optional.ofNullable(executableDetector.getBuckExecutable());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private Optional<String> detectAdbExecutable() {
    try {
      return Optional.ofNullable(executableDetector.getAdbExecutable());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private Optional<String> detectBuildifierExecutable() {
    try {
      return Optional.ofNullable(executableDetector.getBuildifierExecutable());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private Optional<String> detectBuildozerExecutable() {
    try {
      return Optional.ofNullable(executableDetector.getBuildozerExecutable());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private JPanel initExecutablesSection() {
    final Optional<String> detectedBuckExecutable = detectBuckExecutable();
    final Optional<String> detectedAdbExecutable = detectAdbExecutable();
    final Optional<String> detectedBuildifierExecutable = detectBuildifierExecutable();
    final Optional<String> detectedBuildozerExecutable = detectBuildozerExecutable();
    buckPathField =
        createTextFieldWithBrowseButton(
            detectedBuckExecutable.map(s -> "Default: " + s).orElse("No 'buck' found on path"),
            "Buck Executable",
            "Specify the buck executable to use (for this project)",
            null);
    JButton testBuckPathButton =
        createExecutableFieldTestingButton("buck", detectedBuckExecutable, buckPathField);

    adbPathField =
        createTextFieldWithBrowseButton(
            detectedAdbExecutable.map(s -> "Default: " + s).orElse("No 'adb' found on path"),
            "Adb Executable",
            "Specify the adb executable to use (for this project)",
            buckProjectSettingsProvider.getProject());
    JButton testAdbPathButton =
        createExecutableFieldTestingButton("adb", detectedAdbExecutable, adbPathField);

    buildifierPathField =
        createTextFieldWithBrowseButton(
            detectedBuildifierExecutable
                .map(s -> "Default: " + s)
                .orElse("No 'buildifier' found on path"),
            "Buildifier Executable",
            "Specify the buildifier executable to use (for this project)",
            null);
    JButton testBuildifierPathButton =
        createExecutableFieldTestingButton(
            "buildifier", detectedBuildifierExecutable, buildifierPathField);

    buildozerPathField =
        createTextFieldWithBrowseButton(
            detectedBuildozerExecutable
                .map(s -> "Default: " + s)
                .orElse("No 'buildozer' found on path"),
            "Buildozer Executable",
            "Specify the buildozer executable to use (for this project)",
            null);
    JButton testBuildozerPathButton =
        createExecutableFieldTestingButton(
            "buildozer", detectedBuildozerExecutable, buildozerPathField);

    final JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(IdeBorderFactory.createTitledBorder("Executables", true));

    GridBagConstraints leftSide = new GridBagConstraints();
    leftSide.fill = GridBagConstraints.NONE;
    leftSide.anchor = GridBagConstraints.LINE_START;
    leftSide.gridx = 0;
    leftSide.weightx = 0;

    GridBagConstraints middle = new GridBagConstraints();
    middle.fill = GridBagConstraints.HORIZONTAL;
    middle.anchor = GridBagConstraints.LINE_START;
    middle.gridx = 1;
    middle.weightx = 1;

    GridBagConstraints rightSide = new GridBagConstraints();
    rightSide.fill = GridBagConstraints.NONE;
    rightSide.anchor = GridBagConstraints.LINE_END;
    rightSide.gridx = 2;
    rightSide.weightx = 0;

    leftSide.gridy = middle.gridy = rightSide.gridy = 0;
    panel.add(new JLabel("Buck Executable:"), leftSide);
    panel.add(buckPathField, middle);
    panel.add(testBuckPathButton, rightSide);

    leftSide.gridy = middle.gridy = rightSide.gridy = 1;
    panel.add(new JLabel("Adb Executable:"), leftSide);
    panel.add(adbPathField, middle);
    panel.add(testAdbPathButton, rightSide);

    leftSide.gridy = middle.gridy = rightSide.gridy = 2;
    panel.add(new JLabel("Buildifer Executable:"), leftSide);
    panel.add(buildifierPathField, middle);
    panel.add(testBuildifierPathButton, rightSide);

    leftSide.gridy = middle.gridy = rightSide.gridy = 3;
    panel.add(new JLabel("Buildozer Executable:"), leftSide);
    panel.add(buildozerPathField, middle);
    panel.add(testBuildozerPathButton, rightSide);
    return panel;
  }

  private JPanel initUISettingsSection() {
    showDebug = new JCheckBox("Show debug in tool window");

    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(IdeBorderFactory.createTitledBorder("UI Settings", true));

    GridBagConstraints constraints = new GridBagConstraints();
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.anchor = GridBagConstraints.LINE_START;
    constraints.weightx = 1;

    constraints.gridy = 0;
    panel.add(showDebug, constraints);
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
    cellTableModel = new ListTableModel<>(CELL_NAME_COLUMN, ROOT_COLUMN, BUILD_FILENAME_COLUMN);
    cellTableModel.setItems(buckCellSettingsProvider.getCells());
    TableView<BuckCell> cellTable = new TableView<>(cellTableModel);
    cellTable.setPreferredScrollableViewportSize(
        new Dimension(
            cellTable.getPreferredScrollableViewportSize().width, 8 * cellTable.getRowHeight()));
    ToolbarDecorator decorator =
        ToolbarDecorator.createDecorator(cellTable)
            .setAddAction(
                (AnActionButton button) -> {
                  final FileChooserDescriptor dirChooser =
                      FileChooserDescriptorFactory.createSingleFolderDescriptor()
                          .withTitle("Select root directory of buck cell");
                  Project project = buckProjectSettingsProvider.getProject();
                  FileChooser.chooseFile(
                      dirChooser,
                      project,
                      BuckSettingsUI.this,
                      project.getBaseDir(),
                      file -> {
                        ProgressManager.getInstance()
                            .run(
                                new Modal(project, "Checking buildfile.name for cell", true) {
                                  @Override
                                  public void run(@NotNull ProgressIndicator progressIndicator) {
                                    BuckCell buckCell =
                                        discoverCell(
                                            buckExecutableForAutoDiscovery(),
                                            file.getName(),
                                            Paths.get(file.getPath()),
                                            progressIndicator);
                                    cellTableModel.addRow(buckCell);
                                  }
                                });
                      });
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

  private String buckExecutableForAutoDiscovery() {
    String buckExecutableText = buckPathField.getText().trim();
    String buckExecutable;
    if (buckExecutableText.isEmpty()) {
      buckExecutable = BuckExecutableDetector.newInstance().getBuckExecutable();
    } else {
      buckExecutable = buckExecutableText;
    }
    return buckExecutable;
  }

  private void discoverCells() {
    final FileChooserDescriptor dirChooser =
        FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select any directory within a buck cell");
    Project project = buckProjectSettingsProvider.getProject();
    Optional.ofNullable(
            FileChooser.chooseFile(dirChooser, BuckSettingsUI.this, project, project.getBaseDir()))
        .ifPresent(
            defaultCell ->
                ProgressManager.getInstance()
                    .run(
                        new Modal(project, "Autodetecting buck cells", true) {
                          @Override
                          public void run(@NotNull ProgressIndicator progressIndicator) {
                            discoverCells(
                                buckExecutableForAutoDiscovery(), defaultCell, progressIndicator);
                          }
                        }));
  }

  private ProcessBuilder noBuckdProcessBuilder(String... cmd) {
    ProcessBuilder processBuilder = new ProcessBuilder(cmd);
    processBuilder.environment().put("NO_BUCKD", "1"); // don't launch a daemon for these...
    return processBuilder;
  }

  private void waitUntilDoneOrCanceled(Process process, ProgressIndicator progressIndicator)
      throws InterruptedException {
    // waitFor timeout chosen to make the UI cancel button reasonably responsive.
    while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
      if (progressIndicator.isCanceled()) {
        if (process.isAlive()) {
          process.destroy();
        }
        throw new InterruptedException("User canceled");
      }
    }
  }

  private void discoverCells(
      String buckExecutable, VirtualFile defaultCell, ProgressIndicator progressIndicator) {
    Path mainCellRoot;
    try {
      progressIndicator.setIndeterminate(true);
      progressIndicator.setText("Finding root for " + defaultCell.getName());
      Process process =
          noBuckdProcessBuilder(buckExecutable, "root")
              .directory(new File(defaultCell.getPath()))
              .start();
      waitUntilDoneOrCanceled(process, progressIndicator);
      mainCellRoot =
          Paths.get(new BufferedReader(new InputStreamReader(process.getInputStream())).readLine());
    } catch (IOException | InterruptedException e) {
      LOG.error("Failed to autodiscover cells", e);
      return;
    }
    try {
      progressIndicator.setText("Finding other cells visible from " + mainCellRoot.getFileName());
      Gson gson = new Gson();
      Type type = new TypeToken<Map<String, String>>() {}.getType();
      Map<String, String> config;
      Process process =
          noBuckdProcessBuilder(buckExecutable, "audit", "cell", "--json")
              .directory(mainCellRoot.toFile())
              .start();
      waitUntilDoneOrCanceled(process, progressIndicator);
      try (InputStreamReader reader = new InputStreamReader(process.getInputStream())) {
        config = gson.fromJson(reader, type);
      }
      List<BuckCell> cells = new ArrayList<>();
      for (Map.Entry<String, String> entry : config.entrySet()) {
        if (progressIndicator.isCanceled()) {
          break;
        }
        String name = entry.getKey();
        Path cellRoot = mainCellRoot.resolve(entry.getValue()).normalize();
        progressIndicator.setText("Checking cell " + name);
        BuckCell cell = discoverCell(buckExecutable, name, cellRoot, progressIndicator);
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
    } catch (IOException | InterruptedException e) {
      LOG.error("Failed to autodiscover cells", e);
    }
  }

  @Nonnull
  private BuckCell discoverCell(
      String buckExecutable, String name, Path cellRoot, ProgressIndicator progressIndicator) {
    Gson gson = new Gson();
    Type type = new TypeToken<Map<String, String>>() {}.getType();
    BuckCell cell = new BuckCell();
    cell.setName(name);
    cell.setRoot(cellRoot.toString());
    try {
      Process process =
          noBuckdProcessBuilder(buckExecutable, "audit", "config", "buildfile.name", "--json")
              .directory(cellRoot.toFile())
              .start();
      waitUntilDoneOrCanceled(process, progressIndicator);
      try (InputStreamReader reader = new InputStreamReader(process.getInputStream())) {
        Map<String, String> cellConfig = gson.fromJson(reader, type);
        Optional.ofNullable(cellConfig)
            .flatMap(config -> Optional.ofNullable(config.get("buildfile.name")))
            .ifPresent(cell::setBuildFileName);
      }
    } catch (IOException | InterruptedException e) {
      LOG.warn("Failed to discover buildfile.name of cell at " + cellRoot, e);
    }
    return cell;
  }

  // Empty or all-whitespace text fields should be parsed as Optional.empty()
  private Optional<String> textToOptional(@Nullable String text) {
    return Optional.ofNullable(text).map(String::trim).filter(s -> !s.isEmpty());
  }

  public boolean isModified() {
    return !Comparing.equal(
            buckPathField.getText().trim(),
            buckExecutableSettingsProvider.getBuckExecutableOverride().orElse(""))
        || !Comparing.equal(
            adbPathField.getText().trim(),
            buckExecutableSettingsProvider.getAdbExecutableOverride().orElse(""))
        || !Comparing.equal(
            buildifierPathField.getText().trim(),
            buckExecutableSettingsProvider.getBuildifierExecutableOverride().orElse(""))
        || !Comparing.equal(
            buildozerPathField.getText().trim(),
            buckExecutableSettingsProvider.getBuildozerExecutableOverride().orElse(""))
        || buckProjectSettingsProvider.isRunAfterInstall() != runAfterInstall.isSelected()
        || buckProjectSettingsProvider.isShowDebugWindow() != showDebug.isSelected()
        || buckProjectSettingsProvider.isMultiInstallMode() != multiInstallMode.isSelected()
        || buckProjectSettingsProvider.isUninstallBeforeInstalling()
            != uninstallBeforeInstall.isSelected()
        || buckProjectSettingsProvider.isUseCustomizedInstallSetting()
            != customizedInstallSetting.isSelected()
        || !buckProjectSettingsProvider
            .getCustomizedInstallSettingCommand()
            .equals(customizedInstallSettingField.getText())
        || !buckCellSettingsProvider.getCells().equals(cellTableModel.getItems());
  }

  public void apply() {
    buckExecutableSettingsProvider.setBuckExecutableOverride(
        textToOptional(buckPathField.getText()));
    buckExecutableSettingsProvider.setAdbExecutableOverride(textToOptional(adbPathField.getText()));
    buckExecutableSettingsProvider.setBuildifierExecutableOverride(
        textToOptional(buildifierPathField.getText()));
    buckExecutableSettingsProvider.setBuildozerExecutableOverride(
        textToOptional(buildozerPathField.getText()));
    buckProjectSettingsProvider.setShowDebugWindow(showDebug.isSelected());
    buckProjectSettingsProvider.setRunAfterInstall(runAfterInstall.isSelected());
    buckProjectSettingsProvider.setMultiInstallMode(multiInstallMode.isSelected());
    buckProjectSettingsProvider.setUninstallBeforeInstalling(uninstallBeforeInstall.isSelected());
    buckProjectSettingsProvider.setUseCustomizedInstallSetting(
        customizedInstallSetting.isSelected());
    buckProjectSettingsProvider.setCustomizedInstallSettingCommand(
        customizedInstallSettingField.getText());
    buckCellSettingsProvider.setCells(cellTableModel.getItems());
  }

  public void reset() {
    adbPathField.setText(buckExecutableSettingsProvider.getAdbExecutableOverride().orElse(""));
    buckPathField.setText(buckExecutableSettingsProvider.getBuckExecutableOverride().orElse(""));
    buildifierPathField.setText(
        buckExecutableSettingsProvider.getBuildifierExecutableOverride().orElse(""));
    buildozerPathField.setText(
        buckExecutableSettingsProvider.getBuildozerExecutableOverride().orElse(""));
    showDebug.setSelected(buckProjectSettingsProvider.isShowDebugWindow());
    runAfterInstall.setSelected(buckProjectSettingsProvider.isRunAfterInstall());
    multiInstallMode.setSelected(buckProjectSettingsProvider.isMultiInstallMode());
    uninstallBeforeInstall.setSelected(buckProjectSettingsProvider.isUninstallBeforeInstalling());
    customizedInstallSetting.setSelected(
        buckProjectSettingsProvider.isUseCustomizedInstallSetting());
    customizedInstallSettingField.setText(
        buckProjectSettingsProvider.getCustomizedInstallSettingCommand());
    cellTableModel.setItems(buckCellSettingsProvider.getCells());
  }
}
