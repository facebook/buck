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
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.Callable;

/**
 * Task to be ran in the default implementation of {@link
 * com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor} for performing work with
 * dependency awareness.
 *
 * <p>This work is contains additional functionality for integrating with the implementation of
 * {@link com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor}.
 */
class DefaultDepsAwareTask<T> extends AbstractDepsAwareTask<T, DefaultDepsAwareTask<T>> {

  private DefaultDepsAwareTask(
      Callable<T> callable, DepsAwareTask.DepsSupplier<DefaultDepsAwareTask<T>> depsSupplier) {
    super(callable, depsSupplier);
  }

  /** @return a new Task to be ran without any dependencies */
  static <U> DefaultDepsAwareTask<U> of(Callable<U> callable) {
    return of(callable, DepsAwareTask.DepsSupplier.of(() -> ImmutableSet.of()));
  }

  /** @return a new Task to be ran */
  static <U> DefaultDepsAwareTask<U> of(
      Callable<U> callable, DepsAwareTask.DepsSupplier<DefaultDepsAwareTask<U>> depsSupplier) {
    return new DefaultDepsAwareTask<>(callable, depsSupplier);
  }
}
