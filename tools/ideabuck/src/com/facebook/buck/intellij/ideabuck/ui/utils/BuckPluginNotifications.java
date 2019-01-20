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
package com.facebook.buck.intellij.ideabuck.ui.utils;

import com.facebook.buck.intellij.ideabuck.ui.BuckUIManager;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.SystemNotifications;
import javax.swing.event.HyperlinkEvent;
import org.jetbrains.annotations.NotNull;

public class BuckPluginNotifications {
  private static final String GROUP_DISPLAY_ID = "BuckNotification";

  private BuckPluginNotifications() {}

  public static void notifyActionToolbar(final Project project) {
    if (!PropertiesComponent.getInstance().isValueSet(GROUP_DISPLAY_ID)) {
      Notifications.Bus.notify(
          new Notification(
              GROUP_DISPLAY_ID,
              "Buck Plugin",
              "<html><a href=''>Enable</a> the toolbar to easily access the buck plugin actions."
                  + "<br>You can enable/disable it at any time by pressing on View > Toolbar "
                  + "in the menu.</html>",
              NotificationType.INFORMATION,
              new NotificationListener() {
                @Override
                public void hyperlinkUpdate(
                    @NotNull Notification notification, @NotNull HyperlinkEvent hyperlinkEvent) {
                  BuckUIManager.getInstance(project).getBuckToolWindow().showMainToolbar();
                }
              }),
          project);
      PropertiesComponent.getInstance().setValue(GROUP_DISPLAY_ID, "true");
    }
  }

  public static void notifySystemCommandFinished(String commandName, boolean processExitStatus) {
    SystemNotifications.getInstance()
        .notify(
            GROUP_DISPLAY_ID,
            StringUtil.capitalize(commandName) + " Finished",
            processExitStatus ? "Successful" : "Failed");
  }
}
