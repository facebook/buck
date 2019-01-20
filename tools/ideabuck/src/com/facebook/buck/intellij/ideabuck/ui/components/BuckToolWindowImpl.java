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

import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.intellij.execution.ui.RunnerLayoutUi;
import com.intellij.execution.ui.layout.PlaceInGrid;
import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.Nullable;

public class BuckToolWindowImpl implements BuckToolWindow {
  private final Project project;
  private final RunnerLayoutUi runnerLayoutUi;
  private ToolWindow mainToolWindow;
  private ToolWindow runToolWindow;

  public BuckToolWindowImpl(Project project) {
    this.project = project;
    this.runnerLayoutUi =
        RunnerLayoutUi.Factory.getInstance(project).create("buck", "buck", "buck", project);
  }

  @Override
  public void addPanel(BuckToolWindowPanel buckToolWindowPanel) {
    Content content =
        runnerLayoutUi.createContent(
            buckToolWindowPanel.getId(),
            buckToolWindowPanel.getComponent(),
            buckToolWindowPanel.getTitle(),
            null,
            null);
    content.setCloseable(false);
    content.setPinnable(false);
    runnerLayoutUi.addContent(content, 0, PlaceInGrid.center, false);
  }

  @Override
  public void initBuckToolWindow() {
    mainToolWindow = ToolWindowManager.getInstance(project).getToolWindow(MAIN_TOOL_WINDOW_ID);
    runToolWindow = ToolWindowManager.getInstance(project).getToolWindow(RUN_TOOL_WINDOW_ID);
    runnerLayoutUi.getOptions().setLeftToolbar(getLeftToolbarActions(), ActionPlaces.UNKNOWN);

    runnerLayoutUi.updateActionsNow();

    final ContentManager contentManager = mainToolWindow.getContentManager();
    Content content =
        contentManager.getFactory().createContent(runnerLayoutUi.getComponent(), "", true);
    contentManager.addContent(content);

    updateMainToolWindowTitleByTarget();
  }

  @Override
  public void showMainToolbar() {
    ApplicationManager.getApplication()
        .invokeLater(
            () -> {
              UISettings uiSettings = UISettings.getInstance();
              uiSettings.SHOW_MAIN_TOOLBAR = true;
              uiSettings.fireUISettingsChanged();
            });
  }

  @Override
  public void showMainToolWindow() {
    showToolWindow(mainToolWindow);
  }

  @Override
  public void showRunToolWindow() {
    showToolWindow(runToolWindow);
  }

  private void showToolWindow(ToolWindow toolWindow) {
    ApplicationManager.getApplication()
        .getInvokator()
        .invokeLater(
            () -> {
              if (toolWindow != null) {
                toolWindow.activate(null, false);
              }
            });
  }

  @Override
  public void showMainToolWindowIfNecessary() {
    if (!isToolWindowInstantiated()) {
      return;
    }
    if (!isMainToolWindowVisible()) {
      showMainToolWindow();
    }
  }

  @Override
  public boolean isToolWindowInstantiated() {
    return !project.isDisposed() && ToolWindowManager.getInstance(project) != null;
  }

  @Override
  public boolean isMainToolWindowVisible() {
    return isToolWindowVisible(mainToolWindow);
  }

  @Override
  public boolean isRunToolWindowVisible() {
    return isToolWindowVisible(runToolWindow);
  }

  private boolean isToolWindowVisible(ToolWindow toolWindow) {
    return toolWindow != null && toolWindow.isVisible();
  }

  @Override
  public void updateMainToolWindowTitleByTarget() {
    String target = BuckBuildManager.getInstance(project).getCurrentSavedTarget(project);
    updateMainToolWindowTitle("Target: " + target);
  }

  @Override
  public void updateMainToolWindowTitle(@Nullable String title) {
    ToolWindow toolWindow =
        ToolWindowManager.getInstance(project).getToolWindow(MAIN_TOOL_WINDOW_ID);
    toolWindow.setTitle(title == null ? "" : title);
  }

  @Override
  public ActionGroup getLeftToolbarActions() {
    ActionManager actionManager = ActionManager.getInstance();

    DefaultActionGroup group = new DefaultActionGroup();

    group.add(actionManager.getAction("buck.ChooseTarget"));
    group.addSeparator();
    group.add(actionManager.getAction("buck.Build"));
    group.add(actionManager.getAction("buck.Stop"));
    group.add(actionManager.getAction("buck.Test"));
    group.add(actionManager.getAction("buck.Install"));
    group.add(actionManager.getAction("buck.InstallDebug"));
    group.add(actionManager.getAction("buck.Uninstall"));
    group.add(actionManager.getAction("buck.ProjectGeneration"));
    group.add(actionManager.getAction("buck.Kill"));
    group.add(actionManager.getAction("buck.ScrollToEnd"));
    group.add(actionManager.getAction("buck.Clear"));

    return group;
  }

  @Override
  public synchronized void updateActionsNow() {
    ApplicationManager.getApplication().invokeLater(() -> runnerLayoutUi.updateActionsNow());
  }
}
