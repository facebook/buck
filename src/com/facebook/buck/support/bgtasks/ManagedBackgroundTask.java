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
import com.facebook.buck.core.util.log.Logger;
import com.google.common.util.concurrent.SettableFuture;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Wrapper class for {@link BackgroundTask} that includes data generated/ managed by the {@link
 * BackgroundTaskManager}, e.g. task ID.
 *
 * @param <T> the type of the argument to the {@link BackgroundTask}
 */
class ManagedBackgroundTask<T> {

  private static Logger LOG = Logger.get(ManagedBackgroundTask.class);

  private final BackgroundTask<T> task;
  private final TaskId id;
  private boolean toCancel;
  private final SettableFuture<Void> future;

  protected ManagedBackgroundTask(BackgroundTask<T> task, BuildId buildId) {
    this.task = task;
    this.id = TaskId.of(task.getName(), buildId);
    this.toCancel = false;
    this.future = SettableFuture.create();
  }

  BackgroundTask<T> getTask() {
    // TODO(bobyf): this should just be private
    return task;
  }

  TaskId getId() {
    return id;
  }

  boolean getToCancel() {
    return toCancel;
  }

  void markToCancel() {
    toCancel = true;
    future.cancel(true);
  }

  /** Runs the action for this task with the given arguments. */
  void run() {
    try {
      task.getAction().run(task.getActionArgs());
      future.set(null);
    } catch (InterruptedException e) {
      future.cancel(true);
      LOG.warn(e, "Task %s interrupted.", getId());
    } catch (Throwable e) {
      future.setException(e);
      LOG.warn(e, "%s while running task %s", e.getClass().getName(), getId());
    }
  }

  Class<?> getActionClass() {
    return task.getAction().getClass();
  }

  Optional<Timeout> getTimeout() {
    return task.getTimeout();
  }

  boolean getShouldCancelOnRepeat() {
    return task.getShouldCancelOnRepeat();
  }

  SettableFuture<Void> getFuture() {
    return future;
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
