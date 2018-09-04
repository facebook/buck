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

package com.facebook.buck.intellij.ideabuck.debugger;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.Client;
import com.android.ddmlib.IDevice;
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
  // Have the maximum retry time of 1 second
  private static final int MAX_RETRIES = 8;
  private static final int RETRY_TIME = 10;
  private static final long ADB_CONNECT_TIMEOUT_MS = 5000;
  private static final long ADB_CONNECT_TIME_STEP_MS = ADB_CONNECT_TIMEOUT_MS / 10;
  @NonNls private static final String RUN_CONFIGURATION_NAME_PATTERN = "Android Debugger (%s)";

  private AndroidDebugger() {}

  /** Initializes an {@link AndroidDebugBridge} using the given adb. */
  public static void init(String adbExecutable) throws InterruptedException {
    if (AndroidDebugBridge.getBridge() == null
        || !isAdbInitialized(AndroidDebugBridge.getBridge())) {
      AndroidDebugBridge.initIfNeeded(/* clientSupport */ true);
      AndroidDebugBridge.createBridge(adbExecutable, false);
    }

    long start = System.currentTimeMillis();
    while (!isAdbInitialized(AndroidDebugBridge.getBridge())) {
      long timeLeft = start + ADB_CONNECT_TIMEOUT_MS - System.currentTimeMillis();
      if (timeLeft <= 0) {
        break;
      }
      Thread.sleep(ADB_CONNECT_TIME_STEP_MS);
    }
  }

  private static boolean isAdbInitialized(AndroidDebugBridge adb) {
    return adb.isConnected() && adb.hasInitialDeviceList();
  }

  public static void disconnect() {
    if (AndroidDebugBridge.getBridge() != null
        && isAdbInitialized(AndroidDebugBridge.getBridge())) {
      AndroidDebugBridge.disconnectBridge();
      AndroidDebugBridge.terminate();
    }
  }

  public static void attachDebugger(final String packageName, final Project project)
      throws InterruptedException {
    IDevice[] devices = AndroidDebugBridge.getBridge().getDevices();
    if (devices.length == 0) {
      return;
    }
    IDevice device = devices[0];

    Client client;
    int currentRetryNumber = 0;
    int currentRetryTime = RETRY_TIME;
    // It takes some time to get the updated client process list, so wait for it
    do {
      client = device.getClient(packageName);
      currentRetryTime *= 2;
      Thread.sleep(currentRetryTime);
      currentRetryNumber++;
    } while (client == null && currentRetryNumber < MAX_RETRIES);

    if (client == null) {
      throw new RuntimeException(
          "Connecting to the adb debug server timed out."
              + "Can't find package with name "
              + packageName
              + ".");
    }

    String debugPort = String.valueOf(client.getDebuggerListenPort());
    final RunnerAndConfigurationSettings settings = createRunConfiguration(project, debugPort);

    ApplicationManager.getApplication()
        .invokeLater(
            new Runnable() {
              @Override
              public void run() {
                // Needs read access which is available on the read thread.
                ProgramRunnerUtil.executeConfiguration(
                    project, settings, DefaultDebugExecutor.getDebugExecutorInstance());
              }
            });
  }

  private static RunnerAndConfigurationSettings createRunConfiguration(
      Project project, String debugPort) {
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
