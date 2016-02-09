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

package com.facebook.buck.intellij.plugin.actions;


import com.facebook.buck.intellij.plugin.build.BuckQueryCommandHandler;
import com.facebook.buck.intellij.plugin.build.BuckCommandHandler;
import com.facebook.buck.intellij.plugin.build.BuckBuildManager;
import com.facebook.buck.intellij.plugin.build.BuckCommand;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

/**
 * Run buck targets command.
 */
public class BuckQueryAction {
    public static final String ACTION_TITLE = "Run buck query";

    private BuckQueryAction() {
    }

    public static void execute(final Project project, final String target) {
        ApplicationManager.getApplication().invokeLater(
                new Runnable() {
                    public void run() {
                        BuckBuildManager buildManager = BuckBuildManager.getInstance(project);

                        BuckCommandHandler handler = new BuckQueryCommandHandler(
                                project,
                                project.getBaseDir(),
                                BuckCommand.QUERY);
                        handler.command().addParameter("--json");
                        handler.command().addParameter(target);

                        buildManager.runBuckCommand(handler, ACTION_TITLE);
                    }
                });
    }
}
