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

package com.facebook.buck.intellij.ideabuck.actions;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;

public class BuckStopAction extends BuckBaseAction {
  public static final String ACTION_TITLE = "Stop the current buck command";
  public static final String ACTION_DESCRIPTION = "Stop the current buck command";

  public BuckStopAction() {
    super(ACTION_TITLE, ACTION_DESCRIPTION, BuckIcons.ACTION_STOP);
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
  public void executeOnPooledThread(AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      BuckBuildManager buckBuildManager = BuckBuildManager.getInstance(project);
      if (buckBuildManager.getCurrentRunningBuckCommandHandler() != null) {
        buckBuildManager.getCurrentRunningBuckCommandHandler().stop();
      }
      buckBuildManager.setBuilding(project, false);
    }
  }
}
