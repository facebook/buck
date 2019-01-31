/*
 * Copyright 2019-present Facebook, Inc.
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
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableSet;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

abstract class AbstractDepsAwareTask<T, TaskType extends AbstractDepsAwareTask<T, TaskType>>
    extends DepsAwareTask<T, TaskType> {

  private final AtomicReference<TaskStatus> status =
      new AtomicReference<>(TaskStatus.NOT_SCHEDULED);

  AbstractDepsAwareTask(Callable<T> callable, DepsSupplier<TaskType> depsSupplier) {
    super(callable, depsSupplier);
  }

  ImmutableSet<TaskType> getPrereqs() throws Exception {
    return getDepsSupplier().getPrereq();
  }

  ImmutableSet<TaskType> getDependencies() throws Exception {
    return getDepsSupplier().get();
  }

  void call() {
    try {
      Preconditions.checkState(status.get() == TaskStatus.STARTED);
      result.complete(getCallable().call());
    } catch (Exception e) {
      result.completeExceptionally(e);
    } finally {

      Verify.verify(compareAndSetStatus(TaskStatus.STARTED, TaskStatus.DONE));
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

  /** Status of this task. */
  enum TaskStatus {
    NOT_SCHEDULED,
    SCHEDULED,
    STARTED,
    DONE
  }
}
