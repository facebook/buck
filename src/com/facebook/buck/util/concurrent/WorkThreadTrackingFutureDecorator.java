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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Implementation of WorkThreadTrackingFuture that wraps a future instrumented with work thread
 * tracking.
 */
public final class WorkThreadTrackingFutureDecorator<T, FUTURE extends Future<T>>
    implements WorkThreadTrackingFuture<T> {
  @Nullable private volatile Thread workThread;
  private final FUTURE delegate;

  WorkThreadTrackingFutureDecorator(
      Function<WorkThreadTrackingFuture.WorkThreadTracker, FUTURE> futureFactory) {
    this.delegate = futureFactory.apply(new WorkThreadTracker());
  }

  @Override
  public T get() throws InterruptedException, ExecutionException {
    if (isBeingWorkedOnByCurrentThread()) {
      throw new RecursiveGetException();
    }
    return delegate.get();
  }

  @Override
  public T get(long timeout, @Nonnull TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    if (isBeingWorkedOnByCurrentThread()) {
      throw new RecursiveGetException();
    }
    return delegate.get(timeout, unit);
  }

  @Override
  public boolean isBeingWorkedOnByCurrentThread() {
    return Thread.currentThread() == workThread;
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    return delegate.cancel(mayInterruptIfRunning);
  }

  @Override
  public boolean isCancelled() {
    return delegate.isCancelled();
  }

  @Override
  public boolean isDone() {
    return delegate.isDone();
  }

  class WorkThreadTracker implements WorkThreadTrackingFuture.WorkThreadTracker {
    @Override
    public Scope start() {
      workThread = Thread.currentThread();
      return () -> {
        Preconditions.checkState(
            workThread == Thread.currentThread(),
            "Work should currently be performed by the current thread.");
        workThread = null;
      };
    }
  }
}
