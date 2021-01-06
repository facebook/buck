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

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.RawCommandLineEditor;
import com.intellij.ui.components.JBLabel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nullable;

/** Configuration dialog for {@link BuckCommandBeforeRunTask}. */
public class BuckCommandToolEditorDialog extends DialogWrapper {

  private JBLabel label;
  private RawCommandLineEditor commandLineEditor;

  public BuckCommandToolEditorDialog(Project project) {
    super(project);
    setTitle("Buck Command");
    init();
  }

  @Nullable
  @Override
  protected JPanel createCenterPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.gridx = 0;
    c.gridy = 0;
    label = new JBLabel("Arguments:");
    panel.add(label, c);
    commandLineEditor = new RawCommandLineEditor();
    c.gridy = 1;
    panel.add(commandLineEditor, c);
    return panel;
  }

  public void setArguments(List<String> arguments) {
    commandLineEditor.setText(arguments.stream().collect(Collectors.joining(" ")));
  }

  public List<String> getArguments() {
    return Arrays.asList(commandLineEditor.getText().split(" "));
  }
}
