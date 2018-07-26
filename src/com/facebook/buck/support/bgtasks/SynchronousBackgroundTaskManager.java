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

import com.facebook.buck.log.Logger;
import java.util.ArrayList;
import java.util.List;

/**
 * Synchronous implementation of {@link BackgroundTaskManager}. Scheduled tasks are run
 * serially/synchronously upon notification of command exit.
 */
public class SynchronousBackgroundTaskManager implements BackgroundTaskManager {

  private static final Logger LOG = Logger.get(SynchronousBackgroundTaskManager.class);
  private List<ManagedBackgroundTask> scheduledTasks;
  private List<ManagedBackgroundTask> finishedTasks;
  private final boolean onDaemon;

  public SynchronousBackgroundTaskManager(boolean onDaemon) {
    this.onDaemon = onDaemon;
    this.scheduledTasks = new ArrayList<>();
    this.finishedTasks = new ArrayList<>();
  }

  public boolean isOnDaemon() {
    return onDaemon;
  }

  @Override
  public String schedule(BackgroundTask<?> task, String taskName) {
    ManagedBackgroundTask managedTask = ManagedBackgroundTask.of(task, taskName);
    scheduledTasks.add(managedTask);
    return managedTask.getTaskId();
  }

  /**
   * Runs a task. Exceptions are caught and logged.
   *
   * @param managedTask Task to run
   */
  void runTask(ManagedBackgroundTask managedTask) {
    try {
      BackgroundTask<?> task = managedTask.getTask();
      task.run();
    } catch (Exception e) {
      LOG.error(
          e,
          "%s while running task %s: %s",
          e.getClass().getName(),
          managedTask.getTaskId(),
          e.getMessage());
    }
  }

  @Override
  public void notify(Notification code) {
    switch (code) {
      case COMMAND_START:
        // called when command begins
        break;

      case COMMAND_END:
        while (scheduledTasks.size() > 0) {
          ManagedBackgroundTask task = scheduledTasks.remove(0);
          runTask(task);
          finishedTasks.add(task);
        }
    }
  }

  protected List<ManagedBackgroundTask> getScheduledTasks() {
    return scheduledTasks;
  }

  protected List<ManagedBackgroundTask> getFinishedTasks() {
    return finishedTasks;
  }
}
