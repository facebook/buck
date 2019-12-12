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
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager.Notification;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.types.Unit;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Scope class for client-side use of {@link BackgroundTaskManager}. Scope handles scheduling and
 * start/end notifications for individual builds, passing tasks/notifications to the manager for
 * scheduling.
 *
 * <p>This lives for the duration of the command, and will schedule the tasks to be run on {@link
 * #close()}. {@link #close()} should only occur on command exit.
 */
public class TaskManagerCommandScope implements Scope {

  private static final Logger LOG = Logger.get(TaskManagerCommandScope.class);

  private final boolean blocking;
  private final BackgroundTaskManager manager;
  private final BuildId buildId;
  private final ConcurrentLinkedQueue<ManagedBackgroundTask<?>> scheduledTasks =
      new ConcurrentLinkedQueue<>();

  private volatile boolean isClosed = false;

  protected TaskManagerCommandScope(
      BackgroundTaskManager manager, BuildId buildId, boolean blocking) {
    this.blocking = blocking;
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
    if (isClosed) {
      // TODO(bobyf): look to see if its safe to throw here instead of silently ignoring.
      return;
    }
    ManagedBackgroundTask<?> managedTask = new ManagedBackgroundTask<>(task, buildId);
    scheduledTasks.add(managedTask);
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

  ImmutableMap<BackgroundTask<?>, Future<Unit>> getScheduledTasksResults() {
    return scheduledTasks.stream()
        .collect(
            ImmutableMap.toImmutableMap(
                ManagedBackgroundTask::getTask, ManagedBackgroundTask::getFuture));
  }

  @Override
  public synchronized void close() {
    if (!isClosed) {
      isClosed = true;
      manager.notify(Notification.COMMAND_END);
    }

    if (blocking) {
      awaitTasksToComplete();
    }
  }

  private void awaitTasksToComplete() {
    ImmutableList<Future<?>> futures =
        scheduledTasks.stream()
            .map(ManagedBackgroundTask::getFuture)
            .collect(ImmutableList.toImmutableList());

    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (InterruptedException e) {
        LOG.info("Waiting for background tasks was interrupted");
        return;
      } catch (ExecutionException e) {
        LOG.warn(e.getCause(), "Exception occurred executing background task");
      }
    }
  }
}
