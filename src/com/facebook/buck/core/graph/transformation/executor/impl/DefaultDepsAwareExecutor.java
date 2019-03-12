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

import com.facebook.buck.core.graph.transformation.executor.DepsAwareTask;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * A specialized Executor that executes {@link DepsAwareTask}. This executor will attempt to
 * maintain maximum concurrency, while completing dependencies of each supplied work first.
 */
public class DefaultDepsAwareExecutor<T> extends AbstractDefaultDepsAwareExecutor<T> {

  private DefaultDepsAwareExecutor(
      BlockingDeque<DefaultDepsAwareTask<T>> workQueue,
      Future<?>[] workers,
      ExecutorService executorService) {
    super(workQueue, workers, executorService);
  }

  /** Creates a {@link DefaultDepsAwareExecutor} with given {@code numberOfThreads}. */
  public static <U> DefaultDepsAwareExecutor<U> of(int numberOfThreads) {
    ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
    LinkedBlockingDeque<DefaultDepsAwareTask<U>> workQueue = new LinkedBlockingDeque<>();
    Future<?>[] workers =
        startWorkers(executorService, numberOfThreads, workQueue, DefaultDepsAwareWorker::new);
    return new DefaultDepsAwareExecutor<>(workQueue, workers, executorService);
  }
}
