/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.intellij.ideabuck.configurations;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

class BuckTestExecutionState extends AbstractExecutionState<BuckTestConfiguration> {

  public BuckTestExecutionState(BuckTestConfiguration configuration, Project project) {
    super(configuration, project);
  }

  @Nullable
  @Override
  public ExecutionResult execute(Executor executor, @NotNull ProgramRunner runner)
      throws ExecutionException {
    ProcessHandler processHandler = runBuildCommand(executor);
    TestConsoleProperties properties =
        new BuckTestConsoleProperties(
            processHandler, mProject, mConfiguration, "Buck test", executor);
    ConsoleView console =
        SMTestRunnerConnectionUtil.createAndAttachConsole("buck test", processHandler, properties);
    return new DefaultExecutionResult(console, processHandler, AnAction.EMPTY_ARRAY);
  }

  private ProcessHandler runBuildCommand(Executor executor) {
    BuckModule buckModule = mProject.getComponent(BuckModule.class);
    String targets = mConfiguration.data.targets;
    String additionalParams = mConfiguration.data.additionalParams;
    String testSelectors = mConfiguration.data.testSelectors;
    String buckExecutablePath = mConfiguration.data.buckExecutablePath;
    String title = "Buck Test " + targets;

    buckModule.attach(targets);

    BuckBuildCommandHandler handler =
        new BuckBuildCommandHandler(
            mProject, BuckCommand.TEST, /* doStartNotify */ false, buckExecutablePath) {
          @Override
          protected void notifyLines(Key outputType, Iterable<String> lines) {
            super.notifyLines(outputType, lines);
            findAndAttachToDebuggableLines(outputType, lines, title);
          }
        };
    if (!targets.isEmpty()) {
      handler.command().addParameters(parseParamsIntoList(targets));
    }
    if (!testSelectors.isEmpty()) {
      handler.command().addParameter("--test-selectors");
      handler.command().addParameter(testSelectors);
    }
    if (!additionalParams.isEmpty()) {
      handler.command().addParameters(parseParamsIntoList(additionalParams));
    }
    if (executor.getId().equals(DefaultDebugExecutor.EXECUTOR_ID)) {
      handler.command().addParameter("--debug");
    }
    handler.start();
    OSProcessHandler result = handler.getHandler();
    openBuckToolWindowPostExecution(result, title);
    return result;
  }
}
