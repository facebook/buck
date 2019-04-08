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

package com.facebook.buck.core.graph.transformation.executor.impl;

import com.facebook.buck.core.graph.transformation.executor.impl.AbstractDepsAwareTask.TaskStatus;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * A worker to be ran in a thread to manage {@link DefaultDepsAwareTask} and their deps in an
 * efficient manner.
 *
 * <p>This worker providers smarter scheduling of tasks and their dependencies, and attempts to
 * eagerly compute dependencies locally.
 *
 * <p>Blocking operations that are ran in the {@link DefaultDepsAwareTask} will block the thread,
 * and its corresponding worker.
 */
class DefaultDepsAwareWorker<T> extends AbstractDepsAwareWorker<DefaultDepsAwareTask<T>> {

  /**
   * The {@link TaskStatus} is used to synchronize between tasks.
   *
   * <p>{@link DefaultDepsAwareTask}s in the queue should always have a status of {@link
   * TaskStatus#SCHEDULED}. This is atomically set and used to ensure tasks that are already {@link
   * TaskStatus#STARTED} is not rescheduled to the front of the queue. Tasks that are already {@link
   * TaskStatus#SCHEDULED} should not be resubmitted to the queue. Completed tasks should be {@link
   * TaskStatus#DONE} to avoid recomputation by another worker.
   */
  DefaultDepsAwareWorker(LinkedBlockingDeque<DefaultDepsAwareTask<T>> sharedQueue) {
    super(sharedQueue);
  }

  @Override
  protected DefaultDepsAwareTask<T> takeTask() throws InterruptedException {
    return sharedQueue.take();
  }

  @Override
  protected boolean eval(DefaultDepsAwareTask<T> task) throws InterruptedException {
    if (!task.compareAndSetStatus(TaskStatus.SCHEDULED, TaskStatus.STARTED)) {
      return false;
    }

    ImmutableSet<DefaultDepsAwareTask<T>> prereqs;
    try {
      prereqs = task.getPrereqs();
    } catch (Throwable e) {
      completeWithException(task, e);
      return true;
    }
    boolean prereqsDone;
    try {
      prereqsDone = checkTasksReadyOrSchedule(prereqs);
    } catch (Throwable e) {
      completeWithException(task, e);
      return true;
    }
    if (!prereqsDone) {
      Verify.verify(task.compareAndSetStatus(TaskStatus.STARTED, TaskStatus.SCHEDULED));
      sharedQueue.put(task);
      return false;
    }

    ImmutableSet<DefaultDepsAwareTask<T>> deps;
    try {
      deps = task.getDependencies();
    } catch (Throwable e) {
      completeWithException(task, e);
      return true;
    }

    boolean depsDone;
    try {
      depsDone = checkTasksReadyOrSchedule(deps);
    } catch (Throwable e) {
      completeWithException(task, e);
      return true;
    }

    if (!depsDone) {
      Verify.verify(task.compareAndSetStatus(TaskStatus.STARTED, TaskStatus.SCHEDULED));
      sharedQueue.put(task);
      return false;
    }
    task.call();
    return true;
  }

  private boolean checkTasksReadyOrSchedule(ImmutableSet<DefaultDepsAwareTask<T>> tasksToCheck)
      throws InterruptedException, ExecutionException {
    boolean result = true;

    for (DefaultDepsAwareTask<T> task : tasksToCheck) {
      if (task.getStatus() != TaskStatus.DONE) {
        result = false;
        if (task.getStatus() == TaskStatus.STARTED) {
          continue;
        } else if (task.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED)) {
          sharedQueue.putFirst(task);
        }
      }
      propagateException(task);
    }
    return result;
  }
}
