/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.intellij.plugin.build;

import com.facebook.buck.intellij.plugin.ui.BuckToolWindowFactory;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationType;

/**
 * Buck build result notification
 * Currently we only show notifications when build fails and buck tool window is not visible
 */
public class BuckBuildNotification extends Notification {

  public static final NotificationGroup NOTIFICATION_GROUP_ID = NotificationGroup.toolWindowGroup(
      "Buck Build Messages", BuckToolWindowFactory.TOOL_WINDOW_ID);

  public BuckBuildNotification(
      String groupDisplayId,
      String title,
      String content,
      NotificationType type) {
    super(groupDisplayId, title, content, type);
  }

  public static BuckBuildNotification createBuildFailedNotification(
      String buildCommand,
      String description) {
    String title = "Buck " + buildCommand + " failed";
    return new BuckBuildNotification(
        NOTIFICATION_GROUP_ID.getDisplayId(),
        title, description,
        NotificationType.ERROR);
  }
}
