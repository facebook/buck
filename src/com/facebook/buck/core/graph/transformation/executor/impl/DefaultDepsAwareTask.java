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
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Task to be ran in the default implementation of {@link
 * com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor} for performing work with
 * dependency awareness.
 *
 * <p>This work is contains additional functionality for integrating with the implementation of
 * {@link com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor}.
 */
class DefaultDepsAwareTask<T> extends DepsAwareTask<T, DefaultDepsAwareTask<T>> {

  /** Status of this task. */
  enum TaskStatus {
    NOT_STARTED,
    STARTED,
    DONE
  }

  private final AtomicReference<TaskStatus> status = new AtomicReference<>(TaskStatus.NOT_STARTED);

  private DefaultDepsAwareTask(
      Callable<T> callable,
      ThrowingSupplier<ImmutableSet<DefaultDepsAwareTask<T>>, Exception> depsSupplier) {
    super(callable, depsSupplier);
  }

  /** @return a new Task to be ran without any dependencies */
  static <U> DefaultDepsAwareTask<U> of(Callable<U> callable) {
    return of(callable, () -> ImmutableSet.of());
  }

  /** @return a new Task to be ran */
  static <U> DefaultDepsAwareTask<U> of(
      Callable<U> callable, Supplier<ImmutableSet<DefaultDepsAwareTask<U>>> depsSupplier) {
    return ofThrowing(callable, ThrowingSupplier.fromSupplier(depsSupplier));
  }

  /**
   * constructs a task from a callable with the specified dependencies, where dependency discovery
   * could throw
   */
  public static <U> DefaultDepsAwareTask<U> ofThrowing(
      Callable<U> callable,
      ThrowingSupplier<ImmutableSet<DefaultDepsAwareTask<U>>, Exception> depsSupplier) {
    return new DefaultDepsAwareTask<>(callable, depsSupplier);
  }

  ImmutableSet<DefaultDepsAwareTask<T>> getDependencies() throws Exception {
    return getDepsSupplier().get();
  }

  void call() {
    try {
      result.complete(getCallable().call());
    } catch (Exception e) {
      result.completeExceptionally(e);
    }
  }

  TaskStatus getStatus() {
    return Objects.requireNonNull(status.get());
  }

  boolean compareAndSetStatus(TaskStatus expect, TaskStatus update) {
    return status.compareAndSet(expect, update);
  }

  CompletableFuture<T> getFuture() {
    return result;
  }
}
