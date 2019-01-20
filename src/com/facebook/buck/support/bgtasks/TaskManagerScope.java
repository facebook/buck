/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.support.bgtasks;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager.Notification;
import com.facebook.buck.util.Scope;
import com.google.common.collect.ImmutableList;

/**
 * Scope class for client-side use of {@link BackgroundTaskManager}. Scope handles scheduling and
 * start/end notifications for individual builds, passing tasks/notifications to the manager for
 * scheduling.
 */
public class TaskManagerScope implements Scope {

  private final BackgroundTaskManager manager;
  private final BuildId buildId;

  protected TaskManagerScope(BackgroundTaskManager manager, BuildId buildId) {
    this.manager = manager;
    this.buildId = buildId;
    manager.notify(Notification.COMMAND_START);
  }

  /**
   * Schedule a task to be run by a {@link BackgroundTaskManager}.
   *
   * @param task task to be run
   */
  public void schedule(BackgroundTask<?> task) {
    ManagedBackgroundTask managedTask = new ManagedBackgroundTask(task, buildId);
    manager.schedule(managedTask);
  }

  /**
   * Schedule a list of tasks to be run by a {@link BackgroundTaskManager}.
   *
   * @param taskList list of tasks to be run
   */
  public void schedule(ImmutableList<? extends BackgroundTask<?>> taskList) {
    for (BackgroundTask<?> task : taskList) {
      schedule(task);
    }
  }

  public BackgroundTaskManager getManager() {
    return manager;
  }

  @Override
  public void close() {
    manager.notify(Notification.COMMAND_END);
  }
}
