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

import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareTask.TaskStatus;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * A worker to be ran in a thread to manage {@link DefaultDepsAwareTask} and their deps in an
 * efficient manner. This implementation uses a stack for each worker to reduce contention on the
 * global queue.
 *
 * <p>This worker providers smarter scheduling of tasks and their dependencies, and attempts to
 * eagerly compute dependencies locally.
 *
 * <p>Blocking operations that are ran in the {@link DefaultDepsAwareTask} will block the thread,
 * and its corresponding worker.
 */
class DefaultDepsAwareWorkerWithLocalStack extends AbstractDepsAwareWorker {

  private final Deque<DefaultDepsAwareTask<?>> localStack = new ArrayDeque<>();

  DefaultDepsAwareWorkerWithLocalStack(LinkedBlockingDeque<DefaultDepsAwareTask<?>> sharedQueue) {
    super(sharedQueue);
  }

  @Override
  protected DefaultDepsAwareTask<?> takeTask() throws InterruptedException {
    if (localStack.isEmpty()) {
      return sharedQueue.take();
    }
    return localStack.pop();
  }

  @Override
  protected boolean eval(DefaultDepsAwareTask<?> task) throws InterruptedException {
    /**
     * The {@link TaskStatus} is used to synchronize between tasks.
     *
     * <p>{@link DefaultDepsAwareTask}s in the queue should always have a status of {@link
     * TaskStatus#SCHEDULED}. This is atomically set and used to ensure tasks that are already
     * {@link TaskStatus#STARTED} is not rescheduled to the front of the queue. Tasks that are
     * already {@link TaskStatus#SCHEDULED} should not be resubmitted to the queue. Completed tasks
     * should be {@link TaskStatus#DONE} to avoid recomputation by another worker.
     */
    if (!task.compareAndSetStatus(TaskStatus.SCHEDULED, TaskStatus.STARTED)) {
      return false;
    }

    ImmutableSet<? extends DefaultDepsAwareTask<?>> deps;
    try {
      deps = task.getDependencies();
    } catch (Exception e) {
      task.getFuture().completeExceptionally(e);
      Preconditions.checkState(task.compareAndSetStatus(TaskStatus.STARTED, TaskStatus.DONE));
      return true;
    }

    boolean depsDone = true;
    for (DefaultDepsAwareTask<?> dep : deps) {
      if (dep.getStatus() != TaskStatus.DONE) {
        depsDone = false;
        if (dep.getStatus() == TaskStatus.STARTED) {
          continue;
        } else {
          localStack.push(dep);
          if (dep.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED)) {
            sharedQueue.putFirst(dep);
          }
        }
      }
      if (propagateException(task, dep)) {
        Verify.verify(task.compareAndSetStatus(TaskStatus.STARTED, TaskStatus.DONE));
        return true;
      }
    }

    if (!depsDone) {
      Preconditions.checkState(task.compareAndSetStatus(TaskStatus.STARTED, TaskStatus.SCHEDULED));
      localStack.add(task);
      sharedQueue.put(task);
      return false;
    }
    task.call();
    Preconditions.checkState(task.compareAndSetStatus(TaskStatus.STARTED, TaskStatus.DONE));
    return true;
  }

  private boolean propagateException(DefaultDepsAwareTask<?> task, DefaultDepsAwareTask<?> dep)
      throws InterruptedException {
    CompletableFuture<?> depResult = dep.getFuture();
    if (!depResult.isCompletedExceptionally()) {
      return false;
    }
    try {
      depResult.get();
      Preconditions.checkState(false, "Should have completed exceptionally");
    } catch (ExecutionException e) {
      task.getFuture().completeExceptionally(e.getCause());
    }
    return true;
  }
}
