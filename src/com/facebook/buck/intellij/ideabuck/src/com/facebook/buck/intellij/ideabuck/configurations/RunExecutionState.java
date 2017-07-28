/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.configurations;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.facebook.buck.intellij.ideabuck.ui.BuckToolWindowFactory;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckBuildEndConsumer;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class RunExecutionState implements RunProfileState {
  final BuckRunConfiguration mConfiguration;
  final Project mProject;

  public RunExecutionState(BuckRunConfiguration mConfiguration, Project project) {
    this.mConfiguration = mConfiguration;
    this.mProject = project;
  }

  @Nullable
  @Override
  public ExecutionResult execute(Executor executor, @NotNull ProgramRunner programRunner)
      throws ExecutionException {
    final ConsoleView console = new ConsoleViewImpl(mProject, false);
    final ProcessHandler processHandler = runBuildCommand();
    console.attachToProcess(processHandler);
    return new DefaultExecutionResult(console, processHandler, AnAction.EMPTY_ARRAY);
  }

  private ProcessHandler runBuildCommand() {
    final BuckModule buckModule = mProject.getComponent(BuckModule.class);
    final String target = mConfiguration.data.target;
    final String additionalParams = mConfiguration.data.additionalParams;
    final String title = "Buck Run " + target;

    buckModule.attach(target);

    final BuckBuildCommandHandler handler =
        new BuckBuildCommandHandler(
            mProject, mProject.getBaseDir(), BuckCommand.RUN, /* doStartNotify */ false);

    if (!target.isEmpty()) {
      handler.command().addParameter(target);
    }
    if (!additionalParams.isEmpty()) {
      for (String param : additionalParams.split("\\s")) {
        handler.command().addParameter(param);
      }
    }

    MessageBusConnection mConnection = mProject.getMessageBus().connect();
    mConnection.subscribe(
        BuckBuildEndConsumer.BUCK_BUILD_END,
        new BuckBuildEndConsumer() {
          @Override
          public void consumeBuildEnd(long timestamp) {
            if (!BuckToolWindowFactory.isRunToolWindowVisible(mProject)) {
              BuckToolWindowFactory.showRunToolWindow(mProject);
            }
            mConnection.disconnect();
          }
        });

    handler.start();
    final OSProcessHandler result = handler.getHandler();
    return result;
  }
}
