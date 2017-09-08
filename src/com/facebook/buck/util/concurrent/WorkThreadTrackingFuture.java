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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import javax.annotation.Nonnull;

/** A {@link Future} which keeps track of the thread running its computation. */
public interface WorkThreadTrackingFuture<T> extends Future<T> {

  /**
   * {@inheritDoc}
   *
   * @throws RecursiveGetException when the current thread attempts to wait for a computation being
   *     performed by itself.
   */
  @Override
  T get() throws InterruptedException, ExecutionException;

  /**
   * {@inheritDoc}
   *
   * @throws RecursiveGetException when the current thread attempts to wait for a computation being
   *     performed by itself.
   */
  @Override
  T get(long timeout, @Nonnull TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException;

  /** Returns whether the future is being computed by the current thread. */
  boolean isBeingWorkedOnByCurrentThread();

  /** Returns an instance already completed with the given value. */
  static <T> WorkThreadTrackingFuture<T> completedFuture(T value) {
    return new WorkThreadTrackingCompletedFuture<>(value);
  }

  /**
   * Returns a {@code WorkThreadTrackingFuture} that adds work tracking to another future.
   *
   * <p>The factory function should use the passed in WorkTracker to notify this instance when the
   * work has started and finished. For example, to decorate a CompletableFuture:
   *
   * <pre>{@code
   * WorkThreadTrackingFuture.create(workTracker -> CompletableFuture.supplyAsync(() -> {
   *   try (Scope scope = workTracker.start()) {
   *     return doWork();
   *   }
   * }));
   * }</pre>
   */
  static <T, FUTURE extends Future<T>> WorkThreadTrackingFutureDecorator<T, FUTURE> create(
      Function<WorkThreadTracker, FUTURE> futureFactory) {
    return new WorkThreadTrackingFutureDecorator<>(futureFactory);
  }

  /** Tracker to notify the future when computation has started and finished. */
  interface WorkThreadTracker {

    /**
     * Notify that work has started.
     *
     * @return Scope that should be kept in a try-with-resources to notify when work is finished.
     */
    Scope start();
  }

  class RecursiveGetException extends IllegalStateException {
    public RecursiveGetException() {
      super("WorkThreadTrackingFuture.get() called by thread computing the future.");
    }
  }
}
