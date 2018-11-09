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
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckCommandHandler;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.facebook.buck.intellij.ideabuck.ui.BuckUIManager;
import com.facebook.buck.intellij.ideabuck.ui.components.BuckToolWindow;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.intellij.debugger.DebugEnvironment;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.DefaultDebugEnvironment;
import com.intellij.debugger.engine.JavaDebugProcess;
import com.intellij.debugger.engine.RemoteStateState;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

class TestExecutionState implements RunProfileState {
  protected static final Logger LOG = Logger.getInstance(BuckCommandHandler.class);

  public static final Pattern DEBUG_SUSPEND_PATTERN =
      Pattern.compile(
          "Debugging. Suspending JVM. Connect a JDWP debugger to port (\\d+) to proceed.");
  final TestConfiguration mConfiguration;
  final Project mProject;

  public TestExecutionState(TestConfiguration mConfiguration, Project project) {
    this.mConfiguration = mConfiguration;
    this.mProject = project;
  }

  @Nullable
  @Override
  public ExecutionResult execute(Executor executor, @NotNull ProgramRunner runner)
      throws ExecutionException {
    final ProcessHandler processHandler = runBuildCommand(executor);
    final TestConsoleProperties properties =
        new BuckTestConsoleProperties(
            processHandler, mProject, mConfiguration, "Buck test", executor);
    final ConsoleView console =
        SMTestRunnerConnectionUtil.createAndAttachConsole("buck test", processHandler, properties);
    return new DefaultExecutionResult(console, processHandler, AnAction.EMPTY_ARRAY);
  }

  private ProcessHandler runBuildCommand(Executor executor) {
    final BuckModule buckModule = mProject.getComponent(BuckModule.class);
    final String targets = mConfiguration.data.targets;
    final String additionalParams = mConfiguration.data.additionalParams;
    final String testSelectors = mConfiguration.data.testSelectors;
    final String title = "Buck Test " + targets;

    buckModule.attach(targets);

    final BuckBuildCommandHandler handler =
        new BuckBuildCommandHandler(mProject, BuckCommand.TEST, /* doStartNotify */ false) {
          @Override
          protected void notifyLines(Key outputType, Iterable<String> lines) {
            super.notifyLines(outputType, lines);
            if (outputType != ProcessOutputTypes.STDERR) {
              return;
            }
            for (String line : lines) {
              final Matcher matcher = DEBUG_SUSPEND_PATTERN.matcher(line);
              if (matcher.find()) {
                final String port = matcher.group(1);
                attachDebugger(title, port);
              }
            }
          }
        };
    if (!targets.isEmpty()) {
      List<String> params =
          Splitter.on(CharMatcher.whitespace())
              .trimResults()
              .omitEmptyStrings()
              .splitToList(targets);
      handler.command().addParameters(params);
    }
    if (!testSelectors.isEmpty()) {
      handler.command().addParameter("--test-selectors");
      handler.command().addParameter(testSelectors);
    }
    if (!additionalParams.isEmpty()) {
      for (String param : additionalParams.split("\\s")) {
        handler.command().addParameter(param);
      }
    }
    if (executor.getId().equals(DefaultDebugExecutor.EXECUTOR_ID)) {
      handler.command().addParameter("--debug");
    }
    handler.start();
    final OSProcessHandler result = handler.getHandler();
    schedulePostExecutionActions(result, title);
    return result;
  }

  private void schedulePostExecutionActions(final OSProcessHandler result, final String title) {
    final ProgressManager manager = ProgressManager.getInstance();
    ApplicationManager.getApplication()
        .invokeLater(
            () -> {
              manager.run(
                  new Task.Backgroundable(mProject, title, true) {
                    @Override
                    public void run(@NotNull final ProgressIndicator indicator) {
                      try {
                        result.waitFor();
                      } finally {
                        indicator.cancel();
                      }
                      BuckToolWindow buckToolWindow =
                          BuckUIManager.getInstance(mProject).getBuckToolWindow();
                      if (!buckToolWindow.isRunToolWindowVisible()) {
                        buckToolWindow.showRunToolWindow();
                      }
                    }
                  });
            });
  }

  private void attachDebugger(String title, String port) {
    final RemoteConnection remoteConnection =
        new RemoteConnection(/* useSockets */ true, "localhost", port, /* serverMode */ false);
    final RemoteStateState state = new RemoteStateState(mProject, remoteConnection);
    final String name = title + " debugger (" + port + ")";
    final ConfigurationFactory cfgFactory =
        ConfigurationTypeUtil.findConfigurationType("Remote").getConfigurationFactories()[0];
    RunnerAndConfigurationSettings runSettings =
        RunManager.getInstance(mProject).createRunConfiguration(name, cfgFactory);
    final Executor debugExecutor = DefaultDebugExecutor.getDebugExecutorInstance();
    final ExecutionEnvironment env =
        new ExecutionEnvironmentBuilder(mProject, debugExecutor)
            .runProfile(runSettings.getConfiguration())
            .build();
    final int pollTimeout = 3000;
    final DebugEnvironment environment =
        new DefaultDebugEnvironment(env, state, remoteConnection, pollTimeout);

    ApplicationManager.getApplication()
        .invokeLater(
            () -> {
              try {
                final DebuggerSession debuggerSession =
                    DebuggerManagerEx.getInstanceEx(mProject).attachVirtualMachine(environment);
                if (debuggerSession == null) {
                  return;
                }
                XDebuggerManager.getInstance(mProject)
                    .startSessionAndShowTab(
                        name,
                        null,
                        new XDebugProcessStarter() {
                          @Override
                          @NotNull
                          public XDebugProcess start(@NotNull XDebugSession session) {
                            return JavaDebugProcess.create(session, debuggerSession);
                          }
                        });
              } catch (ExecutionException e) {
                LOG.error(
                    "failed to attach to debugger on port "
                        + port
                        + " with polling timeout "
                        + pollTimeout);
              }
            });
  }
}
