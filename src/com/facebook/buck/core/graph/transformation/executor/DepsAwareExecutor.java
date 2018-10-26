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

package com.facebook.buck.core.graph.transformation.executor;

import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * A specialized Executor that executes {@link DepsAwareTask}. This executor will attempt to
 * maintain maximum concurrency, while completing dependencies of each supplied work first.
 *
 * <p>It is guaranteed that the dependencies from {@link DepsAwareTask#getDepsSupplier()} will be
 * completed before the work itself is executed.
 *
 * <p>In terms of thread safety, it is guaranteed that for the same {@link DepsAwareTask}, its deps
 * supplier from {@link DepsAwareTask#getDepsSupplier()} will be executed before its callable from
 * {@link DepsAwareTask#getCallable()} is executed. That is, the supplier execution "happens before"
 * the callable execution, but potentially in different threads, where "happens before" is the
 * standard event ordering a -> b. There is an at least once guarantee on the number of invocations
 * of the deps supplier, and an exactly once guarantee on the callable. There is no guarantee of
 * synchronization between different instances of {@link DepsAwareTask} other than that its
 * dependencies will be completed before the callable is evaluated.
 */
public interface DepsAwareExecutor<
    ResultType, TaskType extends DepsAwareTask<ResultType, TaskType>> {

  /**
   * Shuts down the executor workers immediately. This does not shutdown the underlying executor
   * backing the {@link DepsAwareExecutor}
   */
  void shutdownNow();

  /** @return true iff {@link #shutdownNow()} has been called */
  boolean isShutdown();

  /** @return a new {@link DepsAwareTask} that can be executed in this executor */
  TaskType createTask(Callable<ResultType> callable, Supplier<ImmutableSet<TaskType>> depsSupplier);

  /** @see #createTask(Callable, Supplier) */
  TaskType createTask(
      Callable<ResultType> callable,
      ThrowingSupplier<ImmutableSet<TaskType>, Exception> depsSupplier);

  /** @see #createTask(Callable, Supplier) */
  TaskType createTask(Callable<ResultType> callable);

  /**
   * @param task the {@link DepsAwareTask} to run
   * @return a future of the result
   */
  Future<ResultType> submit(TaskType task);

  /** Same as {@link #submit(DepsAwareTask)} except for multiple tasks. */
  ImmutableList<Future<ResultType>> submitAll(Collection<TaskType> tasks);
}
