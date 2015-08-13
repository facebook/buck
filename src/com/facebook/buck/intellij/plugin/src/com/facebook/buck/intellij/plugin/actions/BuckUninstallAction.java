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

import com.facebook.buck.intellij.plugin.build.BuckBuildCommandHandler;
import com.facebook.buck.intellij.plugin.build.BuckBuildManager;
import com.facebook.buck.intellij.plugin.build.BuckCommand;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;

/**git st
 * Run buck uninstall command.
 */
public class BuckUninstallAction extends DumbAwareAction {

  public static final String ACTION_TITLE = "Run buck uninstall";
  public static final String ACTION_DESCRIPTION = "Run buck uninstall command";

  public BuckUninstallAction() {
    super(ACTION_TITLE, ACTION_DESCRIPTION, AllIcons.Actions.Delete);
  }

  @Override
  public void update(AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      e.getPresentation().setEnabled(!BuckBuildManager.getInstance(project).isBuilding());
    }
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    BuckBuildManager buildManager = BuckBuildManager.getInstance(e.getProject());
    String target = buildManager.getCurrentSavedTarget(e.getProject());
    if (target == null) {
      buildManager.showNoTargetMessage(e.getProject());
      return;
    }

    BuckBuildCommandHandler handler = new BuckBuildCommandHandler(
        e.getProject(),
        e.getProject().getBaseDir(),
        BuckCommand.UNINSTALL);
    handler.command().addParameter(target);
    buildManager.runBuckCommand(handler, ACTION_TITLE);
  }
}
