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

import com.facebook.buck.intellij.ideabuck.ui.components.BuckDebugPanel;
import com.facebook.buck.intellij.ideabuck.ui.components.BuckDebugPanelImpl;
import com.facebook.buck.intellij.ideabuck.ui.components.BuckToolWindow;
import com.facebook.buck.intellij.ideabuck.ui.components.BuckToolWindowImpl;
import com.facebook.buck.intellij.ideabuck.ui.components.BuckTreeViewPanel;
import com.facebook.buck.intellij.ideabuck.ui.components.BuckTreeViewPanelImpl;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;

public class BuckUIManager {

  private Project mProject;
  private BuckToolWindow mBuckToolWindow;
  private BuckDebugPanel mBuckDebugPanel;
  private BuckTreeViewPanel mBuckTreeViewPanel;

  public static synchronized BuckUIManager getInstance(Project project) {
    return ServiceManager.getService(project, BuckUIManager.class);
  }

  public BuckUIManager(Project project) {
    mProject = project;
  }

  public BuckDebugPanel getBuckDebugPanel() {
    if (mBuckDebugPanel == null) {
      mBuckDebugPanel = new BuckDebugPanelImpl(mProject);
    }
    return mBuckDebugPanel;
  }

  public BuckTreeViewPanel getBuckTreeViewPanel() {
    if (mBuckTreeViewPanel == null) {
      mBuckTreeViewPanel = new BuckTreeViewPanelImpl();
    }
    return mBuckTreeViewPanel;
  }

  public void initBuckToolWindow(boolean hasDebugPanel) {
    getBuckToolWindow().addPanel(getBuckTreeViewPanel());
    if (hasDebugPanel) {
      getBuckToolWindow().addPanel(getBuckDebugPanel());
    }
    getBuckToolWindow().initBuckToolWindow();
  }

  public BuckToolWindow getBuckToolWindow() {
    if (mBuckToolWindow == null) {
      mBuckToolWindow = new BuckToolWindowImpl(mProject);
    }
    return mBuckToolWindow;
  }
}
