/*
 * Copyright 2016-present Facebook, Inc.
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
import com.facebook.buck.intellij.ideabuck.build.BuckCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.ResultCallbackBuckHandler;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import java.util.function.Consumer;

public class BuckAuditOwner {
  public static final String ACTION_TITLE = "Run buck audit owner";

  private BuckAuditOwner() {}

  public static void execute(
      final Project project, final Consumer<String> futureCallback, final String... targets) {
    ApplicationManager.getApplication()
        .executeOnPooledThread(
            new Runnable() {
              @Override
              public void run() {
                BuckBuildManager buildManager = BuckBuildManager.getInstance(project);
                BuckModule buckModule = project.getComponent(BuckModule.class);

                StringBuilder targetsString = new StringBuilder();
                for (String target : targets) {
                  targetsString.append(target);
                  targetsString.append(", ");
                }
                buckModule.attach(targetsString.toString());

                BuckCommandHandler handler =
                    new ResultCallbackBuckHandler(
                        project, project.getBaseDir(), BuckCommand.AUDIT_OWNER, futureCallback);
                for (String target : targets) {
                  handler.command().addParameter(target);
                }
                buildManager.runBuckCommand(handler, ACTION_TITLE);
              }
            });
  }
}
