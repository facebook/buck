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

package com.facebook.buck.intellij.plugin.debugger;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.Client;
import com.android.ddmlib.IDevice;
import com.facebook.buck.intellij.plugin.config.BuckExecutableDetector;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.remote.RemoteConfiguration;
import com.intellij.execution.remote.RemoteConfigurationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

import org.jetbrains.annotations.NonNls;

public class AndroidDebugger {
  private static final String ADB_PATH = BuckExecutableDetector.getAdbExecutable();
  @NonNls
  private static final String RUN_CONFIGURATION_NAME_PATTERN = "Android Debugger (%s)";

  private AndroidDebugger() {}

  public static void init() {
    if (AndroidDebugBridge.getBridge() == null ||
        !(AndroidDebugBridge.getBridge().hasInitialDeviceList() &&
            AndroidDebugBridge.getBridge().isConnected())) {
      AndroidDebugBridge.initIfNeeded(/* clientSupport */ true);
      AndroidDebugBridge.createBridge(ADB_PATH, false);
    }
  }

  public static void attachDebugger(final String packageName, final Project project) {
    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        IDevice[] devices = AndroidDebugBridge.getBridge().getDevices();
        IDevice device = devices[0];

        Client client;
        // It takes some time to get the updated client process list, so wait for it
        do {
          client = device.getClient(packageName);
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
          }
        } while (client == null);

        String debugPort = String.valueOf(client.getDebuggerListenPort());
        final RunnerAndConfigurationSettings settings =
            createRunConfiguration(project, debugPort);
        ProgramRunnerUtil.executeConfiguration(
            project,
            settings,
            DefaultDebugExecutor.getDebugExecutorInstance());
      }
    });
  }

  private static RunnerAndConfigurationSettings createRunConfiguration
      (Project project, String debugPort) {
    final RemoteConfigurationType remoteConfigurationType = RemoteConfigurationType.getInstance();

    final ConfigurationFactory factory = remoteConfigurationType.getFactory();
    final RunnerAndConfigurationSettings runSettings =
        RunManager.getInstance(project)
            .createRunConfiguration(getRunConfigurationName(debugPort), factory);
    final RemoteConfiguration configuration = (RemoteConfiguration) runSettings.getConfiguration();

    configuration.HOST = "localhost";
    configuration.PORT = debugPort;
    configuration.USE_SOCKET_TRANSPORT = true;
    configuration.SERVER_MODE = false;

    return runSettings;
  }

  private static String getRunConfigurationName(String debugPort) {
    return String.format(RUN_CONFIGURATION_NAME_PATTERN, debugPort);
  }
}
