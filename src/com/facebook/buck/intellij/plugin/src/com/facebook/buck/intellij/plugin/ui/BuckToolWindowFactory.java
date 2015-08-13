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

package com.facebook.buck.intellij.plugin.ui;

import com.facebook.buck.intellij.plugin.build.BuckBuildManager;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunnerLayoutUi;
import com.intellij.execution.ui.layout.PlaceInGrid;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;

public class BuckToolWindowFactory implements ToolWindowFactory, DumbAware {

  private static final String OUTPUT_WINDOW_CONTENT_ID = "BuckOutputWindowContent";
  public static final String TOOL_WINDOW_ID = "Buck";

  public static void updateBuckToolWindowTitle(Project project) {
    ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
    String target = BuckBuildManager.getInstance(project).getCurrentSavedTarget(project);
    if (target != null) {
      toolWindow.setTitle("Target: " + target);
    }
  }

  public static boolean isToolWindowVisible(Project project) {
    ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
    return toolWindow.isVisible();
  }

  public static synchronized void outputConsoleMessage(
      Project project, String message, ConsoleViewContentType type) {
    BuckUIManager.getInstance(project).getConsoleWindow(project).print(message, type);
  }

  public static synchronized void outputConsoleHyperlink(
      Project project, String link, HyperlinkInfo linkInfo) {
    BuckUIManager.getInstance(project).getConsoleWindow(project).printHyperlink(link, linkInfo);
  }

  public static synchronized void cleanConsole(Project project) {
    BuckUIManager.getInstance(project).getConsoleWindow(project).clear();
  }

  public static synchronized void updateActionsNow(final Project project) {

    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        BuckUIManager.getInstance(project).getLayoutUi(project).updateActionsNow();
      }
    });
  }

  @Override
  public void createToolWindowContent(
      final Project project, ToolWindow toolWindow) {
    toolWindow.setAvailable(true, null);
    toolWindow.setToHideOnEmptyContent(true);

    RunnerLayoutUi runnerLayoutUi = BuckUIManager.getInstance(project).getLayoutUi(project);
    Content consoleContent = createConsoleContent(runnerLayoutUi, project);

    runnerLayoutUi.addContent(consoleContent, 0, PlaceInGrid.center, false);
    runnerLayoutUi.getOptions().setLeftToolbar(
        getLeftToolbarActions(project), ActionPlaces.UNKNOWN);

    runnerLayoutUi.updateActionsNow();

    final ContentManager contentManager = toolWindow.getContentManager();
    Content content = contentManager.getFactory().createContent(
        runnerLayoutUi.getComponent(), "", true);
    contentManager.addContent(content);

    updateBuckToolWindowTitle(project);
  }

  private Content createConsoleContent(RunnerLayoutUi layoutUi, Project project) {
    ConsoleView consoleView = BuckUIManager.getInstance(project).getConsoleWindow(project);
    Content consoleWindowContent = layoutUi.createContent(
        OUTPUT_WINDOW_CONTENT_ID, consoleView.getComponent(), "Output Logs", null, null);
    consoleWindowContent.setCloseable(false);
    return consoleWindowContent;
  }

  public ActionGroup getLeftToolbarActions(final Project project) {
    ActionManager actionManager = ActionManager.getInstance();

    DefaultActionGroup group = new DefaultActionGroup();
    group.add(actionManager.getAction("buck.ChooseTarget"));
    group.addSeparator();
    group.add(actionManager.getAction("buck.Install"));
    group.add(actionManager.getAction("buck.Build"));
    group.add(actionManager.getAction("buck.Kill"));
    group.add(actionManager.getAction("buck.Uninstall"));
    return group;
  }
}
