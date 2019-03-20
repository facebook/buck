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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Task to be ran in toposort based implementation of {@link
 * com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor}.
 *
 * <p>In this implementation, each task keeps a list of all tasks that depends on it, and an {@code
 * numOutStandingDependencies} counter of how many dependencies has yet to be completed.
 */
class ToposortBasedDepsAwareTask<T>
    extends AbstractDepsAwareTask<T, ToposortBasedDepsAwareTask<T>> {
  AtomicLong numOutStandingDependencies = new AtomicLong();
  ConcurrentLinkedQueue<ToposortBasedDepsAwareTask<T>> dependants = new ConcurrentLinkedQueue<>();

  private ToposortBasedDepsAwareTask(
      Callable<T> callable,
      DepsAwareTask.DepsSupplier<ToposortBasedDepsAwareTask<T>> depsSupplier) {
    super(callable, depsSupplier);
  }

  public static <T> ToposortBasedDepsAwareTask<T> of(Callable<T> callable) {
    return of(callable, DepsAwareTask.DepsSupplier.of(ImmutableSet::of));
  }

  public static <T> ToposortBasedDepsAwareTask<T> of(
      Callable<T> callable,
      DepsAwareTask.DepsSupplier<ToposortBasedDepsAwareTask<T>> depsSupplier) {
    return new ToposortBasedDepsAwareTask<>(callable, depsSupplier);
  }

  /**
   * Registers the given task as depending on this one, and updates the task's dependency counters
   */
  void registerDependant(ToposortBasedDepsAwareTask<T> task) {
    if (status.get() == TaskStatus.DONE) {
      return;
    }
    dependants.add(task);
    task.numOutStandingDependencies.incrementAndGet();

    if (status.get() == TaskStatus.DONE && dependants.remove(task)) {
      task.numOutStandingDependencies.decrementAndGet();
    }
  }

  /**
   * @return update the dependents outstanding dependency counters and returns a list of dependents
   *     who is ready to be rescheduled
   */
  ImmutableList<ToposortBasedDepsAwareTask<T>> reportCompletionToDependents() {
    Preconditions.checkState(status.get() == TaskStatus.DONE);
    ImmutableList.Builder<ToposortBasedDepsAwareTask<T>> toReschedule =
        ImmutableList.builderWithExpectedSize(dependants.size());
    for (ToposortBasedDepsAwareTask<T> task = dependants.poll();
        task != null;
        task = dependants.poll()) {
      long d = task.numOutStandingDependencies.decrementAndGet();
      if (d == 0) {
        toReschedule.add(task);
      }
    }
    return toReschedule.build();
  }
}
