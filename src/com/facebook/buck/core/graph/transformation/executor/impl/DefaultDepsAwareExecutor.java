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

import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareTask;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * A specialized Executor that executes {@link DepsAwareTask}. This executor will attempt to
 * maintain maximum concurrency, while completing dependencies of each supplied work first.
 */
public class DefaultDepsAwareExecutor<T> implements DepsAwareExecutor<T, DefaultDepsAwareTask<T>> {

  private final LinkedBlockingDeque<DefaultDepsAwareTask<?>> workQueue;
  private final Future<?>[] workers;
  private volatile boolean isShutdown = false;

  private static final Logger LOG = Logger.get(DefaultDepsAwareExecutor.class);

  private DefaultDepsAwareExecutor(
      Future<?>[] workers, LinkedBlockingDeque<DefaultDepsAwareTask<?>> workQueue) {
    this.workers = workers;
    this.workQueue = workQueue;
  }

  /**
   * Creates a {@link DefaultDepsAwareExecutor} from the given {@link ForkJoinPool}. The executor
   * will have the same parallelism as the backing {@link ForkJoinPool}.
   */
  public static <U> DefaultDepsAwareExecutor<U> from(ForkJoinPool executorService) {
    return from(executorService, executorService.getParallelism());
  }

  /**
   * Creates a {@link DefaultDepsAwareExecutor} from the given {@link ExecutorService}, maintaining
   * up to the specified parallelism. It is up to the user to ensure that the backing {@link
   * ExecutorService} can support the specified parallelism.
   */
  public static <U> DefaultDepsAwareExecutor<U> from(
      ExecutorService executorService, int parallelism) {

    LinkedBlockingDeque<DefaultDepsAwareTask<?>> workQueue = new LinkedBlockingDeque<>();
    Future<?>[] workers = startWorkers(executorService, parallelism, workQueue);
    DefaultDepsAwareExecutor<U> executor = new DefaultDepsAwareExecutor<>(workers, workQueue);

    return executor;
  }

  @Override
  public void shutdownNow() {
    isShutdown = true;
    for (int i = 0; i < workers.length; i++) {
      workers[i].cancel(true);
    }
  }

  @Override
  public boolean isShutdown() {
    return isShutdown;
  }

  @Override
  public DefaultDepsAwareTask<T> createTask(
      Callable<T> callable, Supplier<ImmutableSet<DefaultDepsAwareTask<T>>> depsSupplier) {
    return DefaultDepsAwareTask.of(callable, depsSupplier);
  }

  @Override
  public DefaultDepsAwareTask<T> createTask(
      Callable<T> callable,
      ThrowingSupplier<ImmutableSet<DefaultDepsAwareTask<T>>, Exception> depsSupplier) {
    return DefaultDepsAwareTask.ofThrowing(callable, depsSupplier);
  }

  @Override
  public DefaultDepsAwareTask<T> createTask(Callable<T> callable) {
    return DefaultDepsAwareTask.of(callable);
  }

  @Override
  public Future<T> submit(DefaultDepsAwareTask<T> task) {
    if (isShutdown) {
      throw new RejectedExecutionException("Executor has already been shutdown");
    }
    if (!workQueue.offer(task)) {
      throw new RejectedExecutionException("Failed to schedule new work. Queue is full");
    }
    return task.getResultFuture();
  }

  @Override
  public ImmutableList<Future<T>> submitAll(Collection<DefaultDepsAwareTask<T>> tasks) {
    ImmutableList.Builder<Future<T>> futures = ImmutableList.builderWithExpectedSize(tasks.size());
    for (DefaultDepsAwareTask<T> w : tasks) {
      futures.add(submit(w));
    }
    return futures.build();
  }

  private static Future<?>[] startWorkers(
      ExecutorService executorService,
      int parallelism,
      LinkedBlockingDeque<DefaultDepsAwareTask<?>> workQueue) {
    Future<?>[] workers = new Future<?>[parallelism];
    for (int i = 0; i < workers.length; i++) {
      DefaultDepsAwareWorker worker = new DefaultDepsAwareWorker(workQueue);
      workers[i] = executorService.submit(() -> runWorker(worker));
    }
    return workers;
  }

  private static void runWorker(DefaultDepsAwareWorker worker) {
    try {
      worker.loopForever();
    } catch (InterruptedException e) {
      LOG.info("Worker was interrupted");
    }
  }
}
