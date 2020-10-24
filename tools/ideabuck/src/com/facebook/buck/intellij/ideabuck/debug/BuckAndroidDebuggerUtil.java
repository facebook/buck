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

import com.android.ddmlib.Client;
import com.android.tools.idea.run.AndroidProcessHandler;
import com.android.tools.idea.run.editor.AndroidDebugger;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.android.actions.AndroidProcessChooserDialog;
import org.jetbrains.annotations.NotNull;

/**
 * This contains methods from {@link org.jetbrains.android.actions.AndroidConnectDebuggerAction},
 * done since we want to reuse the private methods while modifying functionality
 */
public final class BuckAndroidDebuggerUtil {

  private BuckAndroidDebuggerUtil() {}

  public static Client getClientAndAttachDebugger(@NotNull Project project) {
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
    return null;
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
