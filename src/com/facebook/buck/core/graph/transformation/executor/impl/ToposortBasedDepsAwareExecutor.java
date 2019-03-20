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

import com.facebook.buck.core.graph.transformation.executor.DepsAwareTask.DepsSupplier;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Supplier;

/**
 * A specialized Executor that executes. This executor will attempt to maintain maximum concurrency,
 * while completing dependencies of each supplied work first.
 */
public class ToposortBasedDepsAwareExecutor<T>
    extends AbstractDepsAwareExecutor<T, ToposortBasedDepsAwareTask<T>> {

  private ToposortBasedDepsAwareExecutor(
      LinkedBlockingDeque<ToposortBasedDepsAwareTask<T>> workQueue,
      Future<?>[] workers,
      ExecutorService executorService) {
    super(workQueue, workers, executorService);
  }

  /** Creates a {@link ToposortBasedDepsAwareExecutor} with the given {@code numOfThreads} */
  public static <U> ToposortBasedDepsAwareExecutor<U> of(int numOfThreads) {
    ExecutorService executorService = Executors.newFixedThreadPool(numOfThreads);
    LinkedBlockingDeque<ToposortBasedDepsAwareTask<U>> workQueue = new LinkedBlockingDeque<>();
    Future<?>[] workers =
        startWorkers(executorService, numOfThreads, workQueue, ToposortDepsAwareWorker::new);
    ToposortBasedDepsAwareExecutor<U> executor =
        new ToposortBasedDepsAwareExecutor<>(workQueue, workers, executorService);

    return executor;
  }

  @Override
  public ToposortBasedDepsAwareTask<T> createTask(
      Callable<T> callable, Supplier<ImmutableSet<ToposortBasedDepsAwareTask<T>>> depsSupplier) {
    return ToposortBasedDepsAwareTask.of(
        callable, DepsSupplier.of(ThrowingSupplier.fromSupplier(depsSupplier)));
  }

  @Override
  public ToposortBasedDepsAwareTask<T> createThrowingTask(
      Callable<T> callable,
      ThrowingSupplier<ImmutableSet<ToposortBasedDepsAwareTask<T>>, Exception> prereqSupplier,
      ThrowingSupplier<ImmutableSet<ToposortBasedDepsAwareTask<T>>, Exception> depsSupplier) {
    return ToposortBasedDepsAwareTask.of(callable, DepsSupplier.of(prereqSupplier, depsSupplier));
  }

  @Override
  public ToposortBasedDepsAwareTask<T> createTask(Callable<T> callable) {
    return ToposortBasedDepsAwareTask.of(callable);
  }
}
