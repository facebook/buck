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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.graph.transformation.executor.DepsAwareTask.DepsSupplier;
import com.facebook.buck.core.graph.transformation.executor.impl.AbstractDepsAwareTask.TaskStatus;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ToposortBasedDepsAwareTaskTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void discoverPrereqsCallsSupplier() throws Exception {
    LongAdder longAdder = new LongAdder();

    ToposortBasedDepsAwareTask<?> task1 =
        ToposortBasedDepsAwareTask.of(
            () -> null,
            DepsSupplier.of(
                () -> {
                  longAdder.increment();
                  return ImmutableSet.of();
                }));
    assertEquals(ImmutableSet.of(), task1.getPrereqs());

    assertEquals(1, longAdder.intValue());
    ToposortBasedDepsAwareTask<?> task2 =
        ToposortBasedDepsAwareTask.of(
            () -> null,
            DepsSupplier.of(
                () -> {
                  longAdder.increment();
                  return ImmutableSet.of(task1);
                }));

    assertEquals(ImmutableSet.of(task1), ImmutableSet.copyOf(task2.getPrereqs()));
    assertEquals(2, longAdder.intValue());
  }

  @Test
  public void discoverDepsCallsSupplier() throws Exception {
    LongAdder longAdder = new LongAdder();

    ToposortBasedDepsAwareTask<?> task1 =
        ToposortBasedDepsAwareTask.of(
            () -> null,
            DepsSupplier.of(
                ImmutableSet::of,
                () -> {
                  longAdder.increment();
                  return ImmutableSet.of();
                }));
    assertEquals(ImmutableSet.of(), task1.getDependencies());

    assertEquals(1, longAdder.intValue());
    ToposortBasedDepsAwareTask<?> task2 =
        ToposortBasedDepsAwareTask.of(
            () -> null,
            DepsSupplier.of(
                ImmutableSet::of,
                () -> {
                  longAdder.increment();
                  return ImmutableSet.of(task1);
                }));

    assertEquals(ImmutableSet.of(task1), ImmutableSet.copyOf(task2.getDependencies()));
    assertEquals(2, longAdder.intValue());
  }

  @Test
  public void evaluateCompletesFutureNormally() throws ExecutionException, InterruptedException {
    ToposortBasedDepsAwareTask<Integer> task = ToposortBasedDepsAwareTask.of(() -> 1);
    Future<Integer> f = task.getResultFuture();
    Verify.verify(task.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.STARTED));
    task.call();

    assertTrue(f.isDone());
    assertEquals(1, (int) f.get());
  }

  @Test
  public void evaluateCompletesFutureExceptionally()
      throws ExecutionException, InterruptedException {
    Exception exception = new Exception();
    expectedException.expectCause(Matchers.sameInstance(exception));

    ToposortBasedDepsAwareTask<Integer> task =
        ToposortBasedDepsAwareTask.of(
            () -> {
              throw exception;
            });
    Future<Integer> f = task.getResultFuture();
    Verify.verify(task.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.STARTED));
    task.call();

    assertTrue(f.isDone());
    f.get();
  }

  @Test
  public void registerDependencyIncreasesDependencyCounter() {

    ToposortBasedDepsAwareTask<Integer> task1 = ToposortBasedDepsAwareTask.of(() -> null);

    ToposortBasedDepsAwareTask<Integer> task2 = ToposortBasedDepsAwareTask.of(() -> null);

    ToposortBasedDepsAwareTask<Integer> task3 = ToposortBasedDepsAwareTask.of(() -> null);

    task2.registerDependant(task1);
    task3.registerDependant(task1);
    task3.registerDependant(task2);

    assertEquals(2, task1.numOutStandingDependencies.get());
    assertEquals(1, task2.numOutStandingDependencies.get());
    assertEquals(0, task3.numOutStandingDependencies.get());
  }

  @Test
  public void registerDependencyWhenTaskDoneDoesNotIncreasesDependencyCounter() {

    ToposortBasedDepsAwareTask<Integer> task1 = ToposortBasedDepsAwareTask.of(() -> null);
    task1.status.set(TaskStatus.STARTED);
    ToposortBasedDepsAwareTask<Integer> task2 = ToposortBasedDepsAwareTask.of(() -> null);
    task2.status.set(TaskStatus.STARTED);
    ToposortBasedDepsAwareTask<Integer> task3 = ToposortBasedDepsAwareTask.of(() -> null);
    task3.status.set(TaskStatus.STARTED);

    task1.call();
    task2.call();
    task3.call();

    task2.registerDependant(task1);
    task3.registerDependant(task1);
    task3.registerDependant(task2);

    assertEquals(0, task1.numOutStandingDependencies.get());
    assertEquals(0, task2.numOutStandingDependencies.get());
    assertEquals(0, task3.numOutStandingDependencies.get());

    assertTrue(task1.reportCompletionToDependents().isEmpty());
    assertTrue(task2.reportCompletionToDependents().isEmpty());
    assertTrue(task3.reportCompletionToDependents().isEmpty());
    assertEquals(0, task1.numOutStandingDependencies.get());
    assertEquals(0, task2.numOutStandingDependencies.get());
    assertEquals(0, task3.numOutStandingDependencies.get());
  }

  @Test
  public void decrementDependenciesOnRegisteredDependentsDecrementsCounter() {

    ToposortBasedDepsAwareTask<Integer> task1 = ToposortBasedDepsAwareTask.of(() -> null);
    task1.status.set(TaskStatus.STARTED);
    ToposortBasedDepsAwareTask<Integer> task2 = ToposortBasedDepsAwareTask.of(() -> null);
    task2.status.set(TaskStatus.STARTED);
    ToposortBasedDepsAwareTask<Integer> task3 = ToposortBasedDepsAwareTask.of(() -> null);
    task3.status.set(TaskStatus.STARTED);

    task2.registerDependant(task1);
    task3.registerDependant(task1);
    task3.registerDependant(task2);

    task1.call();
    task2.call();
    task3.call();

    assertTrue(task1.reportCompletionToDependents().isEmpty());
    // nothing should change
    assertEquals(2, task1.numOutStandingDependencies.get());
    assertEquals(1, task2.numOutStandingDependencies.get());
    assertEquals(0, task3.numOutStandingDependencies.get());

    assertTrue(task2.reportCompletionToDependents().isEmpty());
    assertEquals(1, task1.numOutStandingDependencies.get());
    assertEquals(1, task2.numOutStandingDependencies.get());
    assertEquals(0, task3.numOutStandingDependencies.get());

    assertThat(task3.reportCompletionToDependents(), Matchers.containsInAnyOrder(task1, task2));
    // nothing should change
    assertEquals(0, task1.numOutStandingDependencies.get());
    assertEquals(0, task2.numOutStandingDependencies.get());
    assertEquals(0, task3.numOutStandingDependencies.get());
  }
}
