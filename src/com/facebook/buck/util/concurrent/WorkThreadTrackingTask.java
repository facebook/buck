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
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import sun.misc.Unsafe;

/**
 * A {@link java.util.concurrent.ForkJoinTask} which keeps track of the thread running its
 * computation.
 *
 * <p>This task performs {@link #compute()} in a thread safe manner, guaranteeing only one
 * computation will happen even when called between multiple threads.
 */
public class WorkThreadTrackingTask<T> extends ForkJoinTask<T> {

  @Nullable protected volatile Thread workThread;

  // The work to be performed. This field should be set to null when the work no longer need to
  // be performed in order to avoid any lambda captures from being retained.
  @Nullable private Supplier<T> work;

  private final SettableFuture<T> result = SettableFuture.create();

  public WorkThreadTrackingTask(Supplier<T> work) {
    this.work = work;
  }

  /**
   * computes and completes this task, which can be called outside of the forkjoin framework, or by
   * threads that did not originally fork the task.
   *
   * <p>Exceptions are propagated via UncheckedExecutionExceptions
   *
   * @return the result of the task
   */
  @Nullable
  public final T externalCompute() {
    complete(compute());
    return getRawResult();
  }

  @Nullable
  protected final T compute() {
    /**
     * Since we now manually implement work stealing in the ForkJoinPool through callined {@link
     * #externalCompute()}, fork join framework no longer guarantees synchronization around the
     * task. So we manually synchronize it.
     */
    try {
      Thread currentThread = Thread.currentThread();
      if (UNSAFE.compareAndSwapObject(this, WORKTHREAD, null, currentThread)
          || workThread == currentThread) {
        try (Scope ignored = () -> workThread = null) {
          if (result.isDone()) {
            return getRawResult();
          }
          T res = Objects.requireNonNull(work).get();
          Preconditions.checkState(
              getRawResult() == null || getRawResult() == result,
              "A value for this task has already been created: %s",
              getRawResult());
          result.set(res);
          return res;
        } catch (RuntimeException e) {
          throw e;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      return Futures.getUnchecked(result);
    } finally {
      work = null;
      workThread = null;
    }
  }

  public final boolean isBeingWorkedOnByCurrentThread() {
    return Thread.currentThread() == workThread;
  }

  @Override
  public void complete(T value) {
    Preconditions.checkState(
        getRawResult() == null || getRawResult() == value,
        "A value for this task has already been created: %s, but got %s",
        getRawResult(),
        value);
    super.complete(value);
    workThread = null;
    work = null;
  }

  @Override
  @Nullable
  public T getRawResult() {
    return result.isDone() ? Futures.getUnchecked(result) : null;
  }

  @Override
  protected void setRawResult(T value) {
    result.set(value);
  }

  @Override
  protected boolean exec() {
    compute();
    return true;
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

  // TODO(bobyf): replace with VarHandle in java11
  /**
   * Use unsafe which only allocates one object when class loading, as opposed to Atomic which
   * allocates an object per instance of Atomic.
   */
  private static final long WORKTHREAD;

  private static final Unsafe UNSAFE;

  static {
    try {
      Field f = Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      UNSAFE = (Unsafe) f.get(null);
      WORKTHREAD =
          UNSAFE.objectFieldOffset(WorkThreadTrackingTask.class.getDeclaredField("workThread"));
    } catch (Exception e) {
      throw new Error(e);
    }
  }
}
