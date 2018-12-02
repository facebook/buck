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

package com.facebook.buck.intellij.ideabuck.actions;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckKillCommandHandler;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.intellij.icons.AllIcons.Actions;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;

/**
 * Run buck kill command. It will force terminate all running buck commands and shut down the buck
 * local http server.
 */
public class BuckKillAction extends BuckBaseAction {

  public static final String ACTION_TITLE = "Run buck kill";
  public static final String ACTION_DESCRIPTION = "Run buck kill command";

  public BuckKillAction() {
    super(ACTION_TITLE, ACTION_DESCRIPTION, Actions.Cancel);
  }

  @Override
  public void update(AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      BuckBuildManager buildManager = BuckBuildManager.getInstance(project);
      e.getPresentation()
          .setEnabled(
              !buildManager.isKilling() && project.getComponent(BuckModule.class).isConnected());
    }
  }

  @Override
  public void executeOnPooledThread(final AnActionEvent e) {
    Project project = e.getProject();
    // stop the current running process
    if (project != null) {
      BuckBuildManager buckBuildManager = BuckBuildManager.getInstance(project);
      if (buckBuildManager.getCurrentRunningBuckCommandHandler() != null) {
        buckBuildManager.getCurrentRunningBuckCommandHandler().stop();
      }

      BuckModule buckModule = project.getComponent(BuckModule.class);

      // run the buck kill command
      BuckKillCommandHandler handler = new BuckKillCommandHandler(project, BuckCommand.KILL);
      BuckBuildManager.getInstance(project)
          .runBuckCommandWhileConnectedToBuck(handler, ACTION_TITLE, buckModule);
    }
  }
}
