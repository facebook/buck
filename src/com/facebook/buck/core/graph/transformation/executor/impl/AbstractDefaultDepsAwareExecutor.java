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
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Function;
import java.util.function.Supplier;

abstract class AbstractDefaultDepsAwareExecutor<T>
    extends AbstractDepsAwareExecutor<T, DefaultDepsAwareTask<T>> {

  protected AbstractDefaultDepsAwareExecutor(
      BlockingDeque<DefaultDepsAwareTask<T>> workQueue, Future<?>[] workers) {
    super(workQueue, workers);
  }

  @Override
  public DefaultDepsAwareTask<T> createTask(
      Callable<T> callable, Supplier<ImmutableSet<DefaultDepsAwareTask<T>>> depsSupplier) {
    return DefaultDepsAwareTask.of(
        callable, DepsSupplier.of(ThrowingSupplier.fromSupplier(depsSupplier)));
  }

  @Override
  public DefaultDepsAwareTask<T> createThrowingTask(
      Callable<T> callable,
      ThrowingSupplier<ImmutableSet<DefaultDepsAwareTask<T>>, Exception> prereqSupplier,
      ThrowingSupplier<ImmutableSet<DefaultDepsAwareTask<T>>, Exception> depsSupplier) {
    return DefaultDepsAwareTask.of(callable, DepsSupplier.of(prereqSupplier, depsSupplier));
  }

  @Override
  public DefaultDepsAwareTask<T> createTask(Callable<T> callable) {
    return DefaultDepsAwareTask.of(callable);
  }

  protected static <U, V extends AbstractDepsAwareWorker<DefaultDepsAwareTask<U>>>
      Future<?>[] startWorkers(
          ExecutorService executorService,
          int parallelism,
          LinkedBlockingDeque<DefaultDepsAwareTask<U>> workQueue,
          Function<LinkedBlockingDeque<DefaultDepsAwareTask<U>>, V> workerFunction) {
    Future<?>[] workers = new Future<?>[parallelism];
    for (int i = 0; i < workers.length; i++) {
      V worker = workerFunction.apply(workQueue);
      workers[i] = executorService.submit(() -> runWorker(worker));
    }
    return workers;
  }
}
