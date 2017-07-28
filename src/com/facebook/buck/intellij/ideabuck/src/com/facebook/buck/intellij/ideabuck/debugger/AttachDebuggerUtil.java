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

package com.facebook.buck.intellij.ideabuck.debugger;

import com.facebook.buck.intellij.ideabuck.build.BuckCommandHandler;
import com.intellij.debugger.DebugEnvironment;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.DefaultDebugEnvironment;
import com.intellij.debugger.engine.JavaDebugProcess;
import com.intellij.debugger.engine.RemoteStateState;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import org.jetbrains.annotations.NotNull;

public class AttachDebuggerUtil {
  protected static final Logger LOG = Logger.getInstance(BuckCommandHandler.class);

  public static void attachDebugger(String title, String port, Project mProject) {
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
