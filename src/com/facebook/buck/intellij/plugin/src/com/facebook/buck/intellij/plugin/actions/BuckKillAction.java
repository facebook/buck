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

package com.facebook.buck.intellij.plugin.actions;

import com.facebook.buck.intellij.plugin.build.BuckBuildManager;
import com.facebook.buck.intellij.plugin.build.BuckCommand;
import com.facebook.buck.intellij.plugin.build.BuckKillCommandHandler;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;

/**
 * Run buck kill command.
 * It will force terminate all running buck commands and shut down the buck local http server.
 */
public class BuckKillAction extends DumbAwareAction {

  public static final String ACTION_TITLE = "Run buck kill";
  public static final String ACTION_DESCRIPTION = "Run buck kill command";

  public BuckKillAction() {
    super(ACTION_TITLE, ACTION_DESCRIPTION, AllIcons.Actions.Suspend);
  }

  @Override
  public void update(AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      BuckBuildManager buildManager = BuckBuildManager.getInstance(project);
      e.getPresentation().setEnabled(!buildManager.isKilling() && buildManager.isBuilding());
    }
  }

  @Override
  public void actionPerformed(final AnActionEvent e) {
    BuckKillCommandHandler handler = new BuckKillCommandHandler(
        e.getProject(),
        e.getProject().getBaseDir(),
        BuckCommand.KILL);
    BuckBuildManager.getInstance(e.getProject()).runBuckCommand(handler, ACTION_TITLE);
  }
}
