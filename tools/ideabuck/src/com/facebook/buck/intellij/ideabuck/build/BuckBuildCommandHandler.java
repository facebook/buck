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

package com.facebook.buck.intellij.ideabuck.build;

import com.facebook.buck.intellij.ideabuck.ui.BuckUIManager;
import com.facebook.buck.intellij.ideabuck.ui.components.BuckDebugPanel;
import com.facebook.buck.intellij.ideabuck.ui.utils.BuckPluginNotifications;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;

public class BuckBuildCommandHandler extends BuckCommandHandler {

  private static final ConsoleViewContentType GRAY_OUTPUT =
      new ConsoleViewContentType(
          "BUCK_GRAY_OUTPUT", TextAttributesKey.createTextAttributesKey("CONSOLE_DARKGRAY_OUTPUT"));

  /** @deprecated Use {@link BuckBuildCommandHandler(Project, BuckCommand)} */
  @Deprecated
  public BuckBuildCommandHandler(
      final Project project, final VirtualFile root, final BuckCommand command) {
    super(project, VfsUtil.virtualToIoFile(root), command, true);
  }

  /** @deprecated Use {@link BuckBuildCommandHandler(Project, BuckCommand, boolean)} */
  @Deprecated
  public BuckBuildCommandHandler(
      final Project project,
      final VirtualFile root,
      final BuckCommand command,
      final boolean doStartNotify) {
    super(project, VfsUtil.virtualToIoFile(root), command, doStartNotify);
  }

  public BuckBuildCommandHandler(Project project, BuckCommand command) {
    super(project, command, true);
  }

  public BuckBuildCommandHandler(Project project, BuckCommand command, boolean doStartNotify) {
    super(project, command, doStartNotify);
  }

  @Override
  protected boolean beforeCommand() {
    BuckBuildManager buildManager = BuckBuildManager.getInstance(project);
    BuckDebugPanel buckDebugPanel = BuckUIManager.getInstance(project).getBuckDebugPanel();

    if (!buildManager.isBuckProject(project)) {
      buckDebugPanel.outputConsoleMessage(
          BuckBuildManager.NOT_BUCK_PROJECT_ERROR_MESSAGE, ConsoleViewContentType.ERROR_OUTPUT);
      return false;
    }

    buildManager.setBuilding(project, true);
    buckDebugPanel.cleanConsole();

    String headMessage = "Running '" + command().getCommandLineString() + "'\n";
    buckDebugPanel.outputConsoleMessage(headMessage, GRAY_OUTPUT);
    return true;
  }

  @Override
  protected void afterCommand() {
    BuckBuildManager.getInstance(project).setBuilding(project, false);

    BuckPluginNotifications.notifySystemCommandFinished(
        command.name(), this.processExitSuccesfull());
  }
}
