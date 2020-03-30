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

package com.facebook.buck.support.bgtasks;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.util.types.Unit;
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

  private final Map<BackgroundTask<?>, Future<Unit>> tasks;

  public static TestBackgroundTaskManager of() {
    TestBackgroundTaskManager manager = new TestBackgroundTaskManager();
    manager.startScheduling();
    return manager;
  }

  private TestBackgroundTaskManager() {
    super(1);
    tasks = new ConcurrentHashMap<>();
  }

  public TaskManagerCommandScope getNewScope(BuildId buildId) {
    return getNewScope(buildId, true);
  }

  @Override
  Future<Unit> schedule(ManagedBackgroundTask<?> task) {
    Future<Unit> f = super.schedule(task);
    tasks.put(task.getTask(), f);
    return f;
  }

  /**
   * Get list of completed tasks for test purposes. Returns client-visible {@link BackgroundTask}.
   *
   * @return Task list
   */
  public ImmutableMap<BackgroundTask<?>, Future<Unit>> getScheduledTasksToTest() {
    return ImmutableMap.copyOf(tasks);
  }
}
