/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.ui.components;

import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.project.Project;
import javax.swing.JComponent;

public class BuckDebugPanelImpl implements BuckDebugPanel {

  private static final String OUTPUT_WINDOW_CONTENT_ID = "BuckOutputWindowContent";
  private ConsoleView outputConsole;

  public BuckDebugPanelImpl(Project project) {
    outputConsole = new ConsoleViewImpl(project, false);
  }

  @Override
  public synchronized void outputConsoleMessage(String message, ConsoleViewContentType type) {
    outputConsole.print(message, type);
  }

  @Override
  public synchronized void outputConsoleHyperlink(String link, HyperlinkInfo linkInfo) {
    outputConsole.printHyperlink(link, linkInfo);
  }

  @Override
  public synchronized void cleanConsole() {
    outputConsole.clear();
  }

  @Override
  public String getId() {
    return OUTPUT_WINDOW_CONTENT_ID;
  }

  @Override
  public String getTitle() {
    return "Debug";
  }

  @Override
  public JComponent getComponent() {
    return outputConsole.getComponent();
  }
}
