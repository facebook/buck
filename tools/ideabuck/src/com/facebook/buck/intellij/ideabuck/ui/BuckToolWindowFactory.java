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

import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider;
import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;

public class BuckToolWindowFactory implements ToolWindowFactory, DumbAware {

  @Override
  public void createToolWindowContent(final Project project, ToolWindow toolWindow) {
    toolWindow.setAvailable(true, null);
    toolWindow.setToHideOnEmptyContent(true);
    toolWindow.setIcon(BuckIcons.BUCK_TOOL_WINDOW_ICON);

    BuckProjectSettingsProvider settingsProvider = BuckProjectSettingsProvider.getInstance(project);
    BuckUIManager buckUIManager = BuckUIManager.getInstance(project);

    // Init tool window
    buckUIManager.initBuckToolWindow(settingsProvider.isShowDebugWindow());
  }
}
