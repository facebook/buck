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

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * Test implementation of {@link BackgroundTaskManager}. Behaves same as single-threaded blocking
 * mode on {@link AsyncBackgroundTaskManager} except that exceptions are caught and saved
 * internally. Internal list of tasks is also accessible.
 */
public class TestBackgroundTaskManager extends AsyncBackgroundTaskManager {

  private final Map<BackgroundTask<?>, Future<Void>> tasks;

  public TestBackgroundTaskManager() {
    super(true, 1);
    tasks = new ConcurrentHashMap<>();
  }

  @Override
  Future<Void> schedule(ManagedBackgroundTask<?> task) {
    Future<Void> f = super.schedule(task);
    tasks.put(task.getTask(), f);
    return f;
  }

  /**
   * Get list of completed tasks for test purposes. Returns client-visible {@link BackgroundTask}.
   *
   * @return Task list
   */
  public ImmutableMap<BackgroundTask<?>, Future<Void>> getScheduledTasksToTest() {
    return ImmutableMap.copyOf(tasks);
  }
}
