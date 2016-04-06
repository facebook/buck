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

import com.facebook.buck.intellij.plugin.config.BuckSettingsProvider;
import com.facebook.buck.intellij.plugin.ui.tree.BuckTreeNodeDetail;
import com.facebook.buck.intellij.plugin.ui.tree.BuckTreeNodeDetailError;
import com.facebook.buck.intellij.plugin.ui.tree.BuckTreeNodeFileError;
import com.facebook.buck.intellij.plugin.ui.tree.renderers.BuckTreeCellRenderer;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunnerLayoutUi;
import com.intellij.execution.ui.layout.PlaceInGrid;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.DataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.tabs.JBTabs;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.impl.JBTabsImpl;
import com.intellij.ui.treeStructure.Tree;

import javax.swing.JComponent;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

public class BuckToolWindowFactory implements ToolWindowFactory, DumbAware {

  private static final String OUTPUT_WINDOW_CONTENT_ID = "BuckOutputWindowContent";
  public static final String TOOL_WINDOW_ID = "Buck";
  private static final String TABS_CONTENT_ID = "BuckWindowTabsContent";
  private static final String BUILD_OUTPUT_PANEL = "BuckBuildOutputPanel";

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

    BuckSettingsProvider.State state = BuckSettingsProvider.getInstance().getState();

    JBTabs myTabs = new JBTabsImpl(project);
    // Debug Console
    if (state.showDebug) {
      myTabs.addTab(new TabInfo(consoleContent.getComponent())).setText("Debug");
    }
    // Build Tree Events
    Content treeViewContent = runnerLayoutUi.createContent(BUILD_OUTPUT_PANEL,
            createBuildInfoPanel(project), "Build Output", null, null);
    myTabs.addTab(new TabInfo(treeViewContent.getComponent()).setText("Build"));

    Content tabsContent = runnerLayoutUi.createContent(
            TABS_CONTENT_ID,
            myTabs.getComponent(),
            "Buck Tool Tabs",
            null,
            null);

    runnerLayoutUi.addContent(tabsContent, 0, PlaceInGrid.center, false);
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
    group.add(actionManager.getAction("buck.Build"));
    group.add(actionManager.getAction("buck.Test"));
    group.add(actionManager.getAction("buck.Install"));
    group.add(actionManager.getAction("buck.InstallDebug"));
    group.add(actionManager.getAction("buck.Uninstall"));
    group.add(actionManager.getAction("buck.Kill"));
    group.add(actionManager.getAction("buck.ProjectGeneration"));

    Logger.getInstance(this.getClass()).info("getLeftToolbarActions");

    return group;
  }

  private JComponent createBuildInfoPanel(Project project) {
    Tree result = new Tree(BuckUIManager.getInstance(project).getTreeModel());
    result.addMouseListener(new MouseListener() {
      @Override
      public void mouseClicked(MouseEvent e) {
        Tree tree = (Tree) e.getComponent();
        int selRow = tree.getRowForLocation(e.getX(), e.getY());
        TreePath selPath = tree.getPathForLocation(e.getX(), e.getY());
        if (selRow != -1 && e.getClickCount() == 2) {
          TreeNode node = (TreeNode) selPath.getLastPathComponent();
          if (node.isLeaf()) {
            BuckTreeNodeDetail buckNode = (BuckTreeNodeDetail) node;
            if (buckNode instanceof BuckTreeNodeDetailError) {
              BuckToolWindowFactory.this.handleClickOnError((BuckTreeNodeDetailError) buckNode);
            }
          }
        }
      }

      @Override
      public void mousePressed(MouseEvent e) {

      }

      @Override
      public void mouseReleased(MouseEvent e) {

      }

      @Override
      public void mouseEntered(MouseEvent e) {

      }

      @Override
      public void mouseExited(MouseEvent e) {

      }
    });
    result.setCellRenderer(new BuckTreeCellRenderer());
    result.setShowsRootHandles(false);
    result.setRowHeight(0);
    JBScrollPane treeView = new JBScrollPane(result);
    return treeView;
  }

  private void handleClickOnError(BuckTreeNodeDetailError node) {
    TreeNode parentNode = node.getParent();
    if (parentNode instanceof BuckTreeNodeFileError) {
      BuckTreeNodeFileError buckParentNode = (BuckTreeNodeFileError) parentNode;

      DataContext dataContext = DataManager.getInstance().getDataContext();
      Project project = DataKeys.PROJECT.getData(dataContext);

      String relativePath = buckParentNode.getFilePath().replace(project.getBasePath(), "");

      VirtualFile virtualFile = project.getBaseDir().findFileByRelativePath(relativePath);
      OpenFileDescriptor openFileDescriptor = new OpenFileDescriptor(
              project,
              virtualFile,
              node.getLine() - 1,
              node.getColumn() - 1);
      openFileDescriptor.navigate(true);
    }
  }

}
