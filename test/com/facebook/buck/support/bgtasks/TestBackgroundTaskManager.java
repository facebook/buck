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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test implementation of {@link BackgroundTaskManager}. Behaves same as single-threaded blocking
 * mode on {@link AsyncBackgroundTaskManager} except that exceptions are caught and saved
 * internally. Internal list of tasks is also accessible.
 */
public class TestBackgroundTaskManager extends AsyncBackgroundTaskManager {

  private final Map<ManagedBackgroundTask, Optional<Exception>> statuses;

  public TestBackgroundTaskManager() {
    super(true, 1);
    this.statuses = new HashMap<>();
  }

  @Override
  void runTask(ManagedBackgroundTask managedTask) {
    BackgroundTask<?> task = managedTask.getTask();
    try {
      task.run();
      statuses.put(managedTask, Optional.empty());
    } catch (Exception e) {
      statuses.put(managedTask, Optional.of(e));
    }
  }

  /**
   * Get map of tasks to any exceptions caught. Returns map of client-visible {@link BackgroundTask}
   * instead of {@link ManagedBackgroundTask}.
   *
   * @return Task-exception map
   */
  public Map<BackgroundTask<?>, Optional<Exception>> getTaskErrors() {
    Map<BackgroundTask<?>, Optional<Exception>> output = new HashMap<>();
    for (Map.Entry<ManagedBackgroundTask, Optional<Exception>> entry : statuses.entrySet()) {
      output.put(entry.getKey().getTask(), entry.getValue());
    }
    return output;
  }

  /**
   * Get list of completed tasks for test purposes. Returns client-visible {@link BackgroundTask}.
   *
   * @return Task list
   */
  public List<BackgroundTask<?>> getScheduledTasksToTest() {
    List<BackgroundTask<?>> output = new ArrayList<>();
    for (ManagedBackgroundTask mTask : getScheduledTasks()) {
      output.add(mTask.getTask());
    }
    return output;
  }
}
