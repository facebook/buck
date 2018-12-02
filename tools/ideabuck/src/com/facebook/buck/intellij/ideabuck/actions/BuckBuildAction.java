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

import com.facebook.buck.intellij.ideabuck.build.BuckBuildCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;

/** Run buck build command. */
public class BuckBuildAction extends BuckBaseAction {

  public static final String ACTION_TITLE = "Run buck build";
  public static final String ACTION_DESCRIPTION = "Run buck build command";

  public BuckBuildAction() {
    super(ACTION_TITLE, ACTION_DESCRIPTION, BuckIcons.ACTION_RUN);
  }

  @Override
  public void executeOnPooledThread(final AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) {
      return;
    }
    BuckBuildManager buildManager = BuckBuildManager.getInstance(project);

    String target = buildManager.getCurrentSavedTarget(project);
    // Initiate a buck build
    BuckModule buckModule = project.getComponent(BuckModule.class);
    buckModule.attach(target);

    if (target == null) {
      buildManager.showNoTargetMessage(project);
      return;
    }

    BuckBuildCommandHandler handler = new BuckBuildCommandHandler(project, BuckCommand.BUILD);
    handler.command().addParameter(target);
    buildManager.runBuckCommandWhileConnectedToBuck(handler, ACTION_TITLE, buckModule);
  }
}
