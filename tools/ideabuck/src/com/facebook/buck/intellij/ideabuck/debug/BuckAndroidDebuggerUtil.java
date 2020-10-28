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

package com.facebook.buck.intellij.ideabuck.debug;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.Client;
import com.android.ddmlib.IDevice;
import com.android.tools.idea.run.AndroidProcessHandler;
import com.android.tools.idea.run.editor.AndroidDebugger;
import com.android.tools.idea.run.editor.AndroidJavaDebugger;
import com.google.common.base.Strings;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.util.Arrays;
import java.util.Optional;
import javax.annotation.Nullable;
import org.jetbrains.android.actions.AndroidProcessChooserDialog;
import org.jetbrains.android.sdk.AndroidSdkUtils;
import org.jetbrains.annotations.NotNull;

/**
 * This contains methods from {@link org.jetbrains.android.actions.AndroidConnectDebuggerAction},
 * done since we want to reuse the private methods while modifying functionality
 */
public final class BuckAndroidDebuggerUtil {

  private BuckAndroidDebuggerUtil() {}

  public static Client getClientAndAttachDebugger(@NotNull Project project, String processName) {
    Client clientFromProcessName = getDebugClient(project, processName);
    if (clientFromProcessName == null) {
      if (!Strings.isNullOrEmpty(processName)) {
        Messages.showErrorDialog(
            project,
            "No process exists with process name: " + processName + ", please select a process.",
            "No Client Found for Debugging");
      }
      final AndroidProcessChooserDialog dialog = new AndroidProcessChooserDialog(project, true);
      dialog.show();
      if (dialog.getExitCode() == DialogWrapper.OK_EXIT_CODE) {
        Client client = dialog.getClient();
        if (client == null) {
          return null;
        }
        AppExecutorUtil.getAppExecutorService()
            .execute(() -> closeOldSessionAndRun(project, dialog.getAndroidDebugger(), client));
        return client;
      }
    } else {
      AndroidDebugger javaDebugger = getJavaDebugger(project);
      if (javaDebugger == null) {
        return null;
      }
      AppExecutorUtil.getAppExecutorService()
          .execute(() -> closeOldSessionAndRun(project, javaDebugger, clientFromProcessName));
      return clientFromProcessName;
    }
    return null;
  }

  @Nullable
  private static Client getDebugClient(@NotNull Project project, String processName) {
    if (Strings.isNullOrEmpty(processName)) {
      return null;
    }
    return Optional.ofNullable(getSelectedDevice(project))
        .map(iDevice -> iDevice.getClient(processName))
        .orElse(null);
  }

  @Nullable
  private static IDevice getSelectedDevice(@NotNull Project project) {
    return Optional.ofNullable(AndroidSdkUtils.getDebugBridge(project))
        .map(AndroidDebugBridge::getDevices)
        .flatMap(iDevices -> Arrays.stream(iDevices).findFirst())
        .orElse(null);
  }

  @Nullable
  private static AndroidDebugger getJavaDebugger(@NotNull Project project) {
    return AndroidDebugger.EP_NAME.getExtensionList().stream()
        .filter(
            debugger ->
                debugger.supportsProject(project) && debugger instanceof AndroidJavaDebugger)
        .findFirst()
        .orElse(null);
  }

  private static void closeOldSessionAndRun(
      @NotNull Project project, @NotNull AndroidDebugger androidDebugger, @NotNull Client client) {
    terminateRunSessions(project, client);
    androidDebugger.attachToClient(project, client);
  }

  // Disconnect any active run sessions to the same client
  private static void terminateRunSessions(
      @NotNull Project project, @NotNull Client selectedClient) {
    int pid = selectedClient.getClientData().getPid();

    // find if there are any active run sessions to the same client, and terminate them if so
    for (ProcessHandler handler : ExecutionManager.getInstance(project).getRunningProcesses()) {
      if (handler instanceof AndroidProcessHandler) {
        Client client = ((AndroidProcessHandler) handler).getClient(selectedClient.getDevice());
        if (client != null && client.getClientData().getPid() == pid) {
          handler.notifyTextAvailable(
              "Disconnecting run session: a new debug session will be established.\n",
              ProcessOutputTypes.STDOUT);
          handler.detachProcess();
          break;
        }
      }
    }
  }
}
