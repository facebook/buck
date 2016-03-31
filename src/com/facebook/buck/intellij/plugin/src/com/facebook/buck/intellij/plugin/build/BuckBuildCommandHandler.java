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

package com.facebook.buck.intellij.plugin.build;

import com.facebook.buck.intellij.plugin.ui.BuckEventsConsumer;
import com.facebook.buck.intellij.plugin.ui.BuckToolWindowFactory;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SystemNotifications;

public class BuckBuildCommandHandler extends BuckCommandHandler {

  private static final ConsoleViewContentType GRAY_OUTPUT =
      new ConsoleViewContentType(
          "BUCK_GRAY_OUTPUT", TextAttributesKey.createTextAttributesKey("CONSOLE_DARKGRAY_OUTPUT"));

  public BuckBuildCommandHandler(
      final Project project,
      final VirtualFile root,
      final BuckCommand command,
      final BuckEventsConsumer buckEventsConsumer) {
    super(project, VfsUtil.virtualToIoFile(root), command, buckEventsConsumer);
  }

  @Override
  protected boolean beforeCommand() {
    BuckBuildManager buildManager = BuckBuildManager.getInstance(project());

    if (!buildManager.isBuckProject(project)) {
      BuckToolWindowFactory.outputConsoleMessage(
          project,
          BuckBuildManager.NOT_BUCK_PROJECT_ERROR_MESSAGE, ConsoleViewContentType.ERROR_OUTPUT);
      return false;
    }

    buildManager.setBuilding(project, true);
    BuckToolWindowFactory.cleanConsole(project());

    String headMessage = "Running '" + command().getCommandLineString() + "'\n";
    BuckToolWindowFactory.outputConsoleMessage(
        project,
        headMessage, GRAY_OUTPUT);
    return true;
  }

  @Override
  protected void afterCommand() {
    BuckBuildManager.getInstance(project()).setBuilding(project, false);

    SystemNotifications.getInstance().notify(
        "Buck",
        StringUtil.capitalize(command.name()) + " Finished",
        this.processExitSuccesfull() ? "Successful" : "Failed");
  }
}
