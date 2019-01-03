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
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareTask.TaskStatus;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

public class AbstractDepsAwareExecutor<T> implements DepsAwareExecutor<T, DefaultDepsAwareTask<T>> {

  private static final Logger LOG = Logger.get(AbstractDepsAwareExecutor.class);
  protected final BlockingDeque<DefaultDepsAwareTask<?>> workQueue;
  protected final Future<?>[] workers;
  private volatile boolean isShutdown = false;

  public AbstractDepsAwareExecutor(
      BlockingDeque<DefaultDepsAwareTask<?>> workQueue, Future<?>[] workers) {
    this.workQueue = workQueue;
    this.workers = workers;
  }

  protected static void runWorker(AbstractDepsAwareWorker worker) {
    try {
      worker.loopForever();
    } catch (InterruptedException e) {
      LOG.info("Worker was interrupted");
    }
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
    if (!task.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED)) {
      return task.getResultFuture();
    }
    if (!workQueue.offer(task)) {
      Verify.verify(task.compareAndSetStatus(TaskStatus.SCHEDULED, TaskStatus.NOT_SCHEDULED));
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
}
