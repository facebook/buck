/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.concurrent;

import com.facebook.buck.util.Scope;
import com.google.common.base.Preconditions;
import java.util.Objects;
import java.util.concurrent.RecursiveTask;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * A {@link java.util.concurrent.ForkJoinTask} which keeps track of the thread running its
 * computation.
 */
public class WorkThreadTrackingTask<T> extends RecursiveTask<T> {
  @Nullable protected Thread workThread;

  // The work to be performed. This field should be set to null when the work no longer need to
  // be performed in order to avoid any lambda captures from being retained.
  //
  // Synchronization is not required, the ForkJoin framework should prevent any races, and the
  // timing of the value being set to null is unimportant.
  @Nullable private Supplier<T> work;

  public WorkThreadTrackingTask(Supplier<T> work) {
    this.work = work;
  }

  @Override
  protected final T compute() {
    try (Scope ignored = () -> workThread = null) {
      workThread = Thread.currentThread();
      // The work function should only be invoked while the task is not complete.
      // This condition should be guaranteed by the ForkJoin framework.
      T result = Objects.requireNonNull(work).get();
      Preconditions.checkState(
          getRawResult() == null || getRawResult() == result,
          "A value for this task has already been created: %s",
          getRawResult());
      return result;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      work = null;
    }
  }

  public final boolean isBeingWorkedOnByCurrentThread() {
    return Thread.currentThread() == workThread;
  }

  @Override
  public void complete(T value) {
    Preconditions.checkState(
        getRawResult() == null || getRawResult() == value,
        "A value for this task has already been created: %s",
        getRawResult());
    super.complete(value);
    workThread = null;
    work = null;
  }

  /** @return an already completed task */
  public static <T> WorkThreadTrackingTask<T> completed(T value) {
    WorkThreadTrackingTask<T> task =
        new WorkThreadTrackingTask<>(
            () -> {
              throw new AssertionError("This task should be directly completed.");
            });
    task.complete(value);
    return task;
  }
}
