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

import java.util.concurrent.LinkedBlockingDeque;

abstract class AbstractDepsAwareWorker {

  protected final LinkedBlockingDeque<DefaultDepsAwareTask<?>> sharedQueue;

  AbstractDepsAwareWorker(LinkedBlockingDeque<DefaultDepsAwareTask<?>> sharedQueue) {
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
    try {
      boolean completedOne = false;

      while (!completedOne) {
        DefaultDepsAwareTask<?> task = takeTask();
        completedOne = eval(task);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  protected abstract DefaultDepsAwareTask<?> takeTask() throws InterruptedException;

  /**
   * Performs the actual evaluation of the task.
   *
   * <p>The implementation must ensure that the task status is properly set.
   */
  protected abstract boolean eval(DefaultDepsAwareTask<?> task) throws InterruptedException;
}
