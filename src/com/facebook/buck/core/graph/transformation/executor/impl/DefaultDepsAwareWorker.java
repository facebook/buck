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
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.CompletableFuture;
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
class DefaultDepsAwareWorker {

  private final LinkedBlockingDeque<DefaultDepsAwareTask<?>> sharedQueue;

  DefaultDepsAwareWorker(LinkedBlockingDeque<DefaultDepsAwareTask<?>> sharedQueue) {
    this.sharedQueue = sharedQueue;
  }

  /** Runs the scheduled loop forever until shutdown */
  void loopForever() throws InterruptedException {
    while (!Thread.currentThread().isInterrupted()) {
      loopOnce();
    }
  }

  /** Runs one job, blocking for the task */
  void loopOnce() throws InterruptedException {
    boolean completedOne = false;
    while (!completedOne) {
      DefaultDepsAwareTask<?> task = sharedQueue.take();
      completedOne = eval(task);
    }
  }

  private boolean eval(DefaultDepsAwareTask<?> task) throws InterruptedException {
    if (!task.compareAndSetStatus(TaskStatus.NOT_STARTED, TaskStatus.STARTED)) {
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
          sharedQueue.putFirst(dep);
        }
      }
      if (propagateException(task, dep)) {
        return true;
      }
    }

    if (!depsDone) {
      Preconditions.checkState(
          task.compareAndSetStatus(TaskStatus.STARTED, TaskStatus.NOT_STARTED));
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
