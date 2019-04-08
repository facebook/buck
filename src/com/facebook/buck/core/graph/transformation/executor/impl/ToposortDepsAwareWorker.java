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

import com.facebook.buck.core.graph.transformation.executor.impl.AbstractDepsAwareTask.TaskStatus;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * A worker to be ran in a thread to manage {@link ToposortBasedDepsAwareTask} and their deps in an
 * efficient manner.
 *
 * <p>This worker providers smarter scheduling of tasks and their dependencies, and attempts to
 * perform topological sort on the tasks, only enqueing a task to the global queue once all its
 * tracked dependencies are completed.
 *
 * <p>Blocking operations that are ran in the {@link ToposortBasedDepsAwareTask} will block the
 * thread, and its corresponding worker.
 */
class ToposortDepsAwareWorker<T> extends AbstractDepsAwareWorker<ToposortBasedDepsAwareTask<T>> {

  /**
   * The {@link TaskStatus} is used to synchronize between tasks.
   *
   * <p>{@link DefaultDepsAwareTask}s in the queue should always have a status of {@link
   * TaskStatus#SCHEDULED}. This is atomically set and used to ensure tasks that are already {@link
   * TaskStatus#STARTED} is not rescheduled to the front of the queue. Tasks that are already {@link
   * TaskStatus#SCHEDULED} should not be resubmitted to the queue. Completed tasks should be {@link
   * TaskStatus#DONE} to avoid recomputation by another worker.
   */
  ToposortDepsAwareWorker(LinkedBlockingDeque<ToposortBasedDepsAwareTask<T>> sharedQueue) {
    super(sharedQueue);
  }

  @Override
  protected ToposortBasedDepsAwareTask<T> takeTask() throws InterruptedException {
    return sharedQueue.take();
  }

  @Override
  protected boolean eval(ToposortBasedDepsAwareTask<T> task) throws InterruptedException {
    if (!task.compareAndSetStatus(
        ToposortBasedDepsAwareTask.TaskStatus.SCHEDULED,
        ToposortBasedDepsAwareTask.TaskStatus.STARTED)) {
      return false;
    }

    ImmutableSet<? extends ToposortBasedDepsAwareTask<T>> prereqs;
    try {
      prereqs = task.getPrereqs();
    } catch (Throwable e) {
      task.getFuture().completeExceptionally(e);
      return true;
    }

    ImmutableList<ToposortBasedDepsAwareTask<T>> notDoneTasks;
    try {
      notDoneTasks = checkTasksReadyOrReschedule(prereqs);
    } catch (Throwable e) {
      completeWithException(task, e);
      return true;
    }

    if (!notDoneTasks.isEmpty()) {
      notDoneTasks.forEach(depTask -> depTask.registerDependant(task));
      // I1. task becomes NOT_SCHEDULED only when all its deps are registered
      Verify.verify(task.compareAndSetStatus(TaskStatus.STARTED, TaskStatus.NOT_SCHEDULED));

      if (task.numOutStandingDependencies.get() != 0
          || !task.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.STARTED)) {
        return true;
      }
    }

    ImmutableSet<? extends ToposortBasedDepsAwareTask<T>> deps;
    try {
      deps = task.getDependencies();
    } catch (Throwable e) {
      task.getFuture().completeExceptionally(e);
      return true;
    }

    try {
      notDoneTasks = checkTasksReadyOrReschedule(deps);
    } catch (Throwable e) {
      completeWithException(task, e);
      return true;
    }

    if (!notDoneTasks.isEmpty()) {
      notDoneTasks.forEach(depTask -> depTask.registerDependant(task));
      // I1. task becomes NOT_SCHEDULED only when all its deps are registered
      Verify.verify(task.compareAndSetStatus(TaskStatus.STARTED, TaskStatus.NOT_SCHEDULED));

      if (task.numOutStandingDependencies.get() != 0
          || !task.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.STARTED)) {
        return true;
      }
    }

    task.call();
    ImmutableList<ToposortBasedDepsAwareTask<T>> toReschedule = task.reportCompletionToDependents();

    for (ToposortBasedDepsAwareTask<T> taskToSchedule : toReschedule) {
      if (taskToSchedule.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED)) {
        // at this point, we know that all its deps have been registered, which means its
        // numOutStandingDependencies can only decrease (see I1)
        if (taskToSchedule.numOutStandingDependencies.get() != 0) {
          // the task may have been incorrectly returned one of its dependents were not done
          // registering dependents before the completion of this task. Check the task count again
          // and don't reschedule if it still has dependents left.
          Verify.verify(
              taskToSchedule.compareAndSetStatus(TaskStatus.SCHEDULED, TaskStatus.NOT_SCHEDULED));
          // before setting the taskToSchedule back to NOT_SCHEDULED, its possible that its
          // dependents have been completed, but got blocked from scheduling it due to this task
          // having set its status. Now that its unblocked, we should recheck its
          // numOuStandingDependencies.
          if (taskToSchedule.numOutStandingDependencies.get() != 0) {
            continue;
          }
          if (!taskToSchedule.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED)) {
            continue;
          }
        }
        sharedQueue.putFirst(taskToSchedule);
      }
    }
    return true;
  }

  private ImmutableList<ToposortBasedDepsAwareTask<T>> checkTasksReadyOrReschedule(
      ImmutableSet<? extends ToposortBasedDepsAwareTask<T>> tasks)
      throws InterruptedException, ExecutionException {

    ImmutableList.Builder<ToposortBasedDepsAwareTask<T>> notDoneDepsBuilder =
        ImmutableList.builderWithExpectedSize(tasks.size());
    for (ToposortBasedDepsAwareTask<T> task : tasks) {
      TaskStatus status = task.getStatus();
      if (status != TaskStatus.DONE) {
        notDoneDepsBuilder.add(task);
        if (task.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED)) {
          sharedQueue.putFirst(task);
        }
      }
      propagateException(task);
    }

    return notDoneDepsBuilder.build();
  }
}
