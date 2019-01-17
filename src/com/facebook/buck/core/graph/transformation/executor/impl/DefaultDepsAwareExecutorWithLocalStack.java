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
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Supplier;

/**
 * A specialized Executor that executes {@link DepsAwareTask}. This executor will attempt to
 * maintain maximum concurrency, while completing dependencies of each supplied work first.
 *
 * <p>This implementation uses workers that keep a local stack of tasks to avoid contention in the
 * global queue.
 */
public class DefaultDepsAwareExecutorWithLocalStack<T>
    extends AbstractDepsAwareExecutor<T, DefaultDepsAwareTask<T>> {

  private DefaultDepsAwareExecutorWithLocalStack(
      Future<?>[] workers, BlockingDeque<DefaultDepsAwareTask<T>> workQueue) {
    super(workQueue, workers);
  }

  /**
   * Creates a {@link DefaultDepsAwareExecutorWithLocalStack} from the given {@link ForkJoinPool}.
   * The executor will have the same parallelism as the backing {@link ForkJoinPool}.
   */
  public static <U> DefaultDepsAwareExecutorWithLocalStack<U> from(ForkJoinPool executorService) {
    return from(executorService, executorService.getParallelism());
  }

  /**
   * Creates a {@link DefaultDepsAwareExecutorWithLocalStack} from the given {@link
   * ExecutorService}, maintaining up to the specified parallelism. It is up to the user to ensure
   * that the backing {@link ExecutorService} can support the specified parallelism.
   */
  public static <U> DefaultDepsAwareExecutorWithLocalStack<U> from(
      ExecutorService executorService, int parallelism) {

    LinkedBlockingDeque<DefaultDepsAwareTask<U>> workQueue = new LinkedBlockingDeque<>();
    Future<?>[] workers = startWorkers(executorService, parallelism, workQueue);
    DefaultDepsAwareExecutorWithLocalStack<U> executor =
        new DefaultDepsAwareExecutorWithLocalStack<>(workers, workQueue);

    return executor;
  }

  @Override
  public DefaultDepsAwareTask<T> createTask(
      Callable<T> callable, Supplier<ImmutableSet<DefaultDepsAwareTask<T>>> depsSupplier) {
    return DefaultDepsAwareTask.of(callable, depsSupplier);
  }

  @Override
  public DefaultDepsAwareTask<T> createThrowingTask(
      Callable<T> callable,
      ThrowingSupplier<ImmutableSet<DefaultDepsAwareTask<T>>, Exception> depsSupplier) {
    return DefaultDepsAwareTask.ofThrowing(callable, depsSupplier);
  }

  @Override
  public DefaultDepsAwareTask<T> createTask(Callable<T> callable) {
    return DefaultDepsAwareTask.of(callable);
  }

  private static <U> Future<?>[] startWorkers(
      ExecutorService executorService,
      int parallelism,
      LinkedBlockingDeque<DefaultDepsAwareTask<U>> workQueue) {
    Future<?>[] workers = new Future<?>[parallelism];
    for (int i = 0; i < workers.length; i++) {
      DefaultDepsAwareWorkerWithLocalStack<U> worker =
          new DefaultDepsAwareWorkerWithLocalStack<>(workQueue);
      workers[i] = executorService.submit(() -> runWorker(worker));
    }
    return workers;
  }
}
