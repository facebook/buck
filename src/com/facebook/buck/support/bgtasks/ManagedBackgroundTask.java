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
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/**
 * Wrapper class for {@link BackgroundTask} that includes data generated/ managed by the {@link
 * BackgroundTaskManager}, e.g. task ID.
 */
class ManagedBackgroundTask {

  private final BackgroundTask<?> task;
  private final TaskId id;
  private boolean toCancel;

  protected ManagedBackgroundTask(BackgroundTask<?> task, BuildId buildId) {
    this.task = task;
    this.id = TaskId.of(task.getName(), buildId);
    this.toCancel = false;
  }

  public BackgroundTask<?> getTask() {
    return task;
  }

  public TaskId getId() {
    return id;
  }

  public boolean getToCancel() {
    return toCancel;
  }

  protected void markToCancel() {
    toCancel = true;
  }

  /** Task ID object for {@link ManagedBackgroundTask}. */
  @Value.Immutable(builder = false)
  @BuckStyleImmutable
  abstract static class AbstractTaskId {
    @Value.Parameter
    abstract String name();

    @Value.Parameter
    abstract BuildId buildId();
  }
}
