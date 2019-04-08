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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;

abstract class AbstractDepsAwareWorker<TaskType extends AbstractDepsAwareTask<?, TaskType>> {

  protected final LinkedBlockingDeque<TaskType> sharedQueue;

  AbstractDepsAwareWorker(LinkedBlockingDeque<TaskType> sharedQueue) {
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
      TaskType task = takeTask();
      completedOne = eval(task);
    }
  }

  protected abstract TaskType takeTask() throws InterruptedException;

  /**
   * Performs the actual evaluation of the task.
   *
   * <p>The implementation must ensure that the task status is properly set.
   */
  protected abstract boolean eval(TaskType task) throws InterruptedException;

  /** propagate an exception for the dependency to the current task by throwing it */
  protected void propagateException(TaskType task) throws InterruptedException, ExecutionException {
    CompletableFuture<?> depResult = task.getFuture();
    if (!depResult.isCompletedExceptionally()) {
      return;
    }
    depResult.get();
    Verify.verify(false, "Should have completed exceptionally");
  }

  protected void completeWithException(TaskType task, Throwable e) {
    if (e instanceof ExecutionException) {
      e = e.getCause();
    }
    task.getFuture().completeExceptionally(e);
    Verify.verify(task.compareAndSetStatus(TaskStatus.STARTED, TaskStatus.DONE));
  }
}
