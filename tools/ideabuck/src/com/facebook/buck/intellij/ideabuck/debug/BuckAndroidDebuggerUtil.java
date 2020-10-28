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
import com.android.ddmlib.IShellEnabledDevice;
import com.android.tools.idea.run.AndroidDevice;
import com.android.tools.idea.run.AndroidProcessHandler;
import com.android.tools.idea.run.editor.AndroidDebugger;
import com.android.tools.idea.run.editor.AndroidJavaDebugger;
import com.google.common.base.Strings;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.messages.MessagesService;
import com.intellij.openapi.util.Key;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
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

  @Nullable
  public static Client getClientAndAttachDebugger(
      @NotNull Project project, String processName, IDevice device) {
    Client clientFromProcessName = device != null ? device.getClient(processName) : null;
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
  public static Key<Boolean> getDeployToLocalKey() {
    AnAction action = ActionManager.getInstance().getAction("DeviceAndSnapshotComboBox");
    if (action == null) {
      return null;
    }
    try {
      // This field is only available at runtime
      Field field = action.getClass().getField("DEPLOYS_TO_LOCAL_DEVICE");
      return (Key<Boolean>) field.get(null);
    } catch (IllegalAccessException | NoSuchFieldException e) {
      return null;
    }
  }

  @Nullable
  public static IDevice getSelectedDevice(Project project) {
    // If this key is not null, it means we can enable the device selector
    return getDeployToLocalKey() != null
        ? getSelectedDeviceFromDropDown(project)
        : getDeviceFromDebugBridge(project);
  }

  @Nullable
  private static IDevice getSelectedDeviceFromDropDown(Project project) {
    AndroidDevice androidDevice = getSelectedAndroidDeviceFromDropDown(project);
    if (androidDevice == null) {
      Messages.showErrorDialog(
          project, "Please start an emulator or plug in your phone", "No Device Detected/Selected");
      return null;
    } else if (!androidDevice.isRunning()) {
      Messages.showErrorDialog(
          "<html>The selected device <b>"
              + androidDevice.getName()
              + "</b> is offline. Make sure it is started or connected.</html>",
          "Device Not Connected/Started");
      return null;
    }
    try {
      return androidDevice.getLaunchedDevice().get();
    } catch (InterruptedException | ExecutionException e) {
      Messages.showErrorDialog(
          "<html>Unable to connect to <b>"
              + androidDevice.getName()
              + "</b> because of exception: "
              + e.getMessage()
              + "</html>",
          "Connection Failed");
      return null;
    }
  }

  @Nullable
  public static AndroidDevice getSelectedAndroidDeviceFromDropDown(Project project) {
    AnAction action = ActionManager.getInstance().getAction("DeviceAndSnapshotComboBox");
    if (action == null) {
      return null;
    }
    return getAccessibleSelectedDeviceMethod(action.getClass())
        .map(selectedDeviceMethod -> invokeMethod(selectedDeviceMethod, action, project))
        .flatMap(
            device ->
                getAccessibleGetAndroidDeviceMethod(device.getClass().getSuperclass())
                    .map(getAndroidDeviceMethod -> invokeMethod(getAndroidDeviceMethod, device)))
        .map(AndroidDevice.class::cast)
        .orElse(null);
  }

  @Nullable
  private static IDevice getDeviceFromDebugBridge(Project project) {
    List<IDevice> availableDevices = getDeviceList(project);
    if (availableDevices.isEmpty()) {
      Messages.showErrorDialog(
          "Please start an emulator or plug in your phone", "No Device Detected");
      return null;
    }
    if (availableDevices.size() > 1) {
      String[] devicesNames =
          availableDevices.stream().map(IShellEnabledDevice::getName).toArray(String[]::new);
      int selectedIndex =
          MessagesService.getInstance()
              .showChooseDialog(
                  project,
                  null,
                  "Choose a device to install the target",
                  "Multiple Devices Detected",
                  devicesNames,
                  devicesNames[0],
                  null);
      if (selectedIndex != -1) {
        return availableDevices.get(selectedIndex);
      }
    } else {
      return availableDevices.get(0);
    }
    return null;
  }

  private static List<IDevice> getDeviceList(Project project) {
    AndroidDebugBridge debugBridge = AndroidSdkUtils.getDebugBridge(project);
    return debugBridge == null ? Collections.emptyList() : Arrays.asList(debugBridge.getDevices());
  }

  @Nullable
  private static Object invokeMethod(Method method, Object object, Object... args) {
    try {
      return method.invoke(object, args);
    } catch (IllegalAccessException | InvocationTargetException e) {
      return null;
    }
  }

  private static Optional<Method> getAccessibleSelectedDeviceMethod(Class<?> clazz) {
    try {
      Method method = clazz.getDeclaredMethod("getSelectedDevice", Project.class);
      method.setAccessible(true);
      return Optional.of(method);
    } catch (NoSuchMethodException e) {
      return Optional.empty();
    }
  }

  private static Optional<Method> getAccessibleGetAndroidDeviceMethod(Class<?> clazz) {
    try {
      Method method = clazz.getDeclaredMethod("getAndroidDevice");
      method.setAccessible(true);
      return Optional.of(method);
    } catch (NoSuchMethodException e) {
      return Optional.empty();
    }
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
