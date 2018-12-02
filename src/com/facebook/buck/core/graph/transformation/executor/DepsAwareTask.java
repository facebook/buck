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
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Task to be ran in a {@link DepsAwareExecutor}. This task will offer dependency discovery to the
 * executor so that the executor can perform better scheduling.
 *
 * @param <T> the result type of the task
 * @param <Impl> The implementation type of
 */
public abstract class DepsAwareTask<T, Impl extends DepsAwareTask<T, Impl>> {

  protected final CompletableFuture<T> result = new CompletableFuture<>();
  private final Callable<T> callable;
  private final ThrowingSupplier<ImmutableSet<Impl>, Exception> depsSupplier;

  protected DepsAwareTask(
      Callable<T> callable, ThrowingSupplier<ImmutableSet<Impl>, Exception> depsSupplier) {
    this.callable = callable;
    this.depsSupplier = depsSupplier;
  }

  /** @return the function to run for this task */
  public Callable<T> getCallable() {
    return callable;
  }

  /** @return a function to generate the dependencies */
  public ThrowingSupplier<ImmutableSet<Impl>, Exception> getDepsSupplier() {
    return depsSupplier;
  }

  /** @return a future of the result of this task */
  public Future<T> getResultFuture() {
    return result;
  }
}
