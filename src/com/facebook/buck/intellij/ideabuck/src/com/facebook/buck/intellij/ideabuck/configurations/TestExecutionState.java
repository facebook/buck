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

package com.facebook.buck.intellij.ideabuck.configurations;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

class TestExecutionState implements RunProfileState {
  final TestConfiguration mConfiguration;
  final Project mProject;

  public TestExecutionState(TestConfiguration mConfiguration, Project project) {
    this.mConfiguration = mConfiguration;
    this.mProject = project;
  }

  @Nullable
  @Override
  public ExecutionResult execute(
      Executor executor, @NotNull ProgramRunner runner) throws ExecutionException {
    final ProcessHandler processHandler = new NopProcessHandler();
    final TestConsoleProperties properties = new BuckTestConsoleProperties(
        processHandler,
        mProject,
        mConfiguration, "Buck test", executor
    );
    final ConsoleView console = SMTestRunnerConnectionUtil.createAndAttachConsole(
        "buck test",
        processHandler,
        properties);
    runBuildCommand();
    return new DefaultExecutionResult(console, processHandler, AnAction.EMPTY_ARRAY);
  }

  private void runBuildCommand() {
    final BuckBuildManager buildManager = BuckBuildManager.getInstance(mProject);
    final BuckModule buckModule = mProject.getComponent(BuckModule.class);
    final String target = mConfiguration.data.target;
    final String additionalParams = mConfiguration.data.additionalParams;
    final String testSelectors = mConfiguration.data.testSelectors;

    buckModule.attach(target);

    final BuckBuildCommandHandler handler = new BuckBuildCommandHandler(
        mProject,
        mProject.getBaseDir(),
        BuckCommand.TEST);
    if (!target.isEmpty()) {
      handler.command().addParameter(target);
    }
    if (!testSelectors.isEmpty()) {
      handler.command()
          .addParameter("--test-selectors");
      handler.command()
          .addParameter(testSelectors);
    }
    if (!additionalParams.isEmpty()) {
      for (String param : additionalParams.split("\\s")) {
        handler.command()
            .addParameter(param);
      }

    }
    ApplicationManager.getApplication()
        .executeOnPooledThread(() -> {
          buildManager.runBuckCommandWhileConnectedToBuck(
              handler,
              "Buck test " + target,
              buckModule);
        });
  }
}
