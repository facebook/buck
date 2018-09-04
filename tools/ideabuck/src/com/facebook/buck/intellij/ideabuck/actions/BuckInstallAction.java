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
import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider;
import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.Icon;

/** Run buck install command. */
public class BuckInstallAction extends BuckBaseAction {

  public static final String ACTION_TITLE = "Run buck install";
  public static final String ACTION_DESCRIPTION = "Run buck install command";
  public static final Icon ICON = BuckIcons.ACTION_INSTALL;

  public BuckInstallAction() {
    this(ACTION_TITLE, ACTION_DESCRIPTION, ICON);
  }

  public BuckInstallAction(String actionTitle, String actionDescription, Icon icon) {
    super(actionTitle, actionDescription, icon);
  }

  @Override
  public void executeOnPooledThread(final AnActionEvent e) {
    Project project = e.getProject();
    BuckBuildManager buildManager = BuckBuildManager.getInstance(project);
    String target = buildManager.getCurrentSavedTarget(project);
    BuckModule buckModule = project.getComponent(BuckModule.class);
    buckModule.attach(target);

    if (target == null) {
      buildManager.showNoTargetMessage(project);
      return;
    }

    BuckProjectSettingsProvider settingsProvider = BuckProjectSettingsProvider.getInstance(project);
    BuckBuildCommandHandler handler =
        new BuckBuildCommandHandler(project, project.getBaseDir(), BuckCommand.INSTALL);
    if (settingsProvider.isUseCustomizedInstallSetting()) {
      // Split the whole command line into different parameters.
      String commands = settingsProvider.getCustomizedInstallSettingCommand();
      Matcher matcher = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(commands);
      while (matcher.find()) {
        handler.command().addParameter(matcher.group(1));
      }
    } else {
      if (settingsProvider.isRunAfterInstall()) {
        handler.command().addParameter("-r");
      }
      if (settingsProvider.isMultiInstallMode()) {
        handler.command().addParameter("-x");
      }
      if (settingsProvider.isUninstallBeforeInstalling()) {
        handler.command().addParameter("-u");
      }
    }
    handler.command().addParameter(target);
    buildManager.runBuckCommandWhileConnectedToBuck(handler, ACTION_TITLE, buckModule);
  }
}
