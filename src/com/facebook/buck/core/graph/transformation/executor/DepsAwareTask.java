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
 * <p>The task uses {@link DepsSupplier} to mark what dependencies are needed.
 *
 * @param <T> the result type of the task
 * @param <Impl> The implementation type of
 */
public abstract class DepsAwareTask<T, Impl extends DepsAwareTask<T, Impl>> {

  protected final CompletableFuture<T> result = new CompletableFuture<>();
  private final Callable<T> callable;
  private final DepsSupplier<Impl> depsSupplier;

  protected DepsAwareTask(Callable<T> callable, DepsSupplier<Impl> depsSupplier) {
    this.callable = callable;
    this.depsSupplier = depsSupplier;
  }

  /**
   * The dependency information of a task.
   *
   * <p>The {@code prereqSupplier} is a supplier that returns a set of tasks necessary to compute
   * {@code depsSupplier}. The {@code depsSupplier} is a supplier that returns the dependencies.
   *
   * @param <Impl> the task type
   */
  public static class DepsSupplier<Impl> {

    private final ThrowingSupplier<ImmutableSet<Impl>, Exception> depsSupplier;
    private final ThrowingSupplier<ImmutableSet<Impl>, Exception> prereqSupplier;

    private DepsSupplier(
        ThrowingSupplier<ImmutableSet<Impl>, Exception> prereqSupplier,
        ThrowingSupplier<ImmutableSet<Impl>, Exception> depsSupplier) {
      this.depsSupplier = depsSupplier;
      this.prereqSupplier = prereqSupplier;
    }

    /** @return a single stage {@link DepsSupplier} */
    public static <U> DepsSupplier<U> of(ThrowingSupplier<ImmutableSet<U>, Exception> depSupplier) {
      return of(depSupplier, ImmutableSet::of);
    }

    /**
     * @return a two staged {@link DepsSupplier}, where {@code prereqSupplier} is the initial
     *     dependency computation, and {@code depSupplier} is the dependency computation that occurs
     *     after the tasks from the initial dependency computation are complete.
     */
    public static <U> DepsSupplier<U> of(
        ThrowingSupplier<ImmutableSet<U>, Exception> prereqSupplier,
        ThrowingSupplier<ImmutableSet<U>, Exception> depSupplier) {
      return new DepsSupplier<>(prereqSupplier, depSupplier);
    }

    public ImmutableSet<Impl> get() throws Exception {
      return depsSupplier.get();
    }

    public ImmutableSet<Impl> getPrereq() throws Exception {
      return prereqSupplier.get();
    }
  }

  /** @return the function to run for this task */
  public Callable<T> getCallable() {
    return callable;
  }

  /** @return a function to generate the dependencies */
  public DepsSupplier<Impl> getDepsSupplier() {
    return depsSupplier;
  }

  /** @return a future of the result of this task */
  public Future<T> getResultFuture() {
    return result;
  }
}
