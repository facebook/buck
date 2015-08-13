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

import com.facebook.buck.intellij.plugin.ui.BuckToolWindowFactory;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Iterator;

public class BuckKillCommandHandler extends BuckCommandHandler {

  public BuckKillCommandHandler(
      final Project project,
      final VirtualFile root,
      final BuckCommand command) {
    super(project, VfsUtil.virtualToIoFile(root), command);
  }

  @Override
  protected void notifyLines(
      Key outputType,
      Iterator<String> lines,
      StringBuilder lineBuilder) {
  }

  @Override
  protected boolean beforeCommand() {
    if (!BuckBuildManager.getInstance(project()).isBuckProject(project)) {
      BuckToolWindowFactory.outputConsoleMessage(
          project(),
          BuckBuildManager.NOT_BUCK_PROJECT_ERROR_MESSAGE,
          ConsoleViewContentType.ERROR_OUTPUT);
      return false;
    }
    BuckBuildManager.getInstance(project()).setKilling(project, true);
    return true;
  }

  @Override
  protected void afterCommand() {
    BuckBuildManager buildManager = BuckBuildManager.getInstance(project());
    buildManager.setBuilding(project, false);
    buildManager.setKilling(project, false);
    BuckToolWindowFactory.outputConsoleMessage(
        project(),
        "Build aborted\n",
        ConsoleViewContentType.ERROR_OUTPUT);
  }
}
