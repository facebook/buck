/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.plugin.intellij.ui;

import com.facebook.buck.plugin.intellij.BuckPluginComponent;
import com.facebook.buck.plugin.intellij.BuckTarget;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

import java.awt.*;

public class BuckUI {

  public static final String BUCK_TOOL_WINDOW_ID = "Buck";
  public static final String BUCK_MESSAGE_WINDOW_ID = "Message";

  private BuckPluginComponent component;
  private ToolWindow toolWindow;
  private ToolWindow messageWindow;
  private BuckTargetsPanel buckTargetsPanel;
  private BuckProgressPanel buckProgressPanel;

  public BuckUI(BuckPluginComponent component) {
    this.component = Preconditions.checkNotNull(component);
    createToolWindow();
    createMessageWindow();
    createTargetsPanel();
    createProgressPanel();
  }

  private void createToolWindow() {
    ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(component.getProject());
    toolWindow = toolWindowManager.registerToolWindow(BUCK_TOOL_WINDOW_ID,
        false, /* canClose */
        ToolWindowAnchor.RIGHT
    );
    toolWindow.setIcon(AllIcons.Debugger.Threads);
  }

  private void createMessageWindow() {
    ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(component.getProject());
    messageWindow = toolWindowManager.registerToolWindow(BUCK_MESSAGE_WINDOW_ID,
        true, /* canClose */
        ToolWindowAnchor.BOTTOM
    );
    messageWindow.setIcon(AllIcons.Ant.Message);
  }

  private void createTargetsPanel() {
    buckTargetsPanel = new BuckTargetsPanel(component);
    Content content = ContentFactory.SERVICE.getInstance().createContent(
        buckTargetsPanel.getPanel(),
        buckTargetsPanel.DISPLAY_NAME,
        false /* isLockable */
    );
    toolWindow.getContentManager().addContent(content);
  }

  private void createProgressPanel() {
    buckProgressPanel = new BuckProgressPanel();
    Content content = ContentFactory.SERVICE.getInstance().createContent(
        buckProgressPanel.getPanel(),
        buckProgressPanel.DISPLAY_NAME,
        false /* isLockable */
    );
    messageWindow.getContentManager().addContent(content);
  }

  public BuckProgressPanel getProgressPanel() {
    return buckProgressPanel;
  }

  public void showMessageWindow() {
    EventQueue.invokeLater(new Runnable() {
      @Override
      public void run() {
        messageWindow.show(null);
      }
    });
  }

  public void showErrorMessage(String message) {
    Preconditions.checkNotNull(message);
    Messages.showErrorDialog(message, "Buck");
  }

  public void updateTargets(ImmutableList<BuckTarget> targets) {
    Preconditions.checkNotNull(targets);
    buckTargetsPanel.updateTargets(targets);
  }
}
