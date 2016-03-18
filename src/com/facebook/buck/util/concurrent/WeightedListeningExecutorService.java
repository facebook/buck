/*
 * Copyright 2016-present Facebook, Inc.
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

import com.google.common.util.concurrent.AbstractListeningExecutorService;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A {@link ListeningExecutorService} which gates execution using a {@link ListeningSemaphore} and
 * allows custom weights to be assigned to submitted tasks.
 *
 * NOTE: If futures for submitted jobs are cancelled while they are running, it's possible that the
 * semaphore will be released for that cancelled job before it is finished, meaning more jobs may be
 * scheduled than expected.
 */
public class WeightedListeningExecutorService extends AbstractListeningExecutorService {

  private final ListeningSemaphore semaphore;
  private final int defaultWeight;
  private final ListeningExecutorService delegate;

  public WeightedListeningExecutorService(
      ListeningSemaphore semaphore,
      int defaultWeight,
      ListeningExecutorService delegate) {
    this.semaphore = semaphore;
    this.defaultWeight = defaultWeight;
    this.delegate = delegate;
  }

  private <T> ListenableFuture<T> withSemaphore(
      final int weight,
      final Callable<T> callable) {
    ListenableFuture<T> future =
        Futures.transformAsync(
            semaphore.acquire(weight),
            new AsyncFunction<Void, T>() {
              @Override
              public ListenableFuture<T> apply(@Nullable Void input) {
                try {
                  return Futures.immediateFuture(callable.call());
                } catch (Throwable thrown) {
                  return Futures.immediateFailedFuture(thrown);
                }
              }
            },
            delegate);
    future.addListener(
        new Runnable() {
          @Override
          public void run() {
            semaphore.release(weight);
          }
        },
        com.google.common.util.concurrent.MoreExecutors.directExecutor());
    return future;
  }

  public ListenableFuture<?> submit(final Runnable task, int weight) {
    return submit(task, null, weight);
  }

  @Nonnull
  @Override
  public ListenableFuture<?> submit(Runnable task) {
    return submit(task, defaultWeight);
  }

  public <T> ListenableFuture<T> submit(
      final Runnable task,
      @Nullable final T result,
      int weight) {
    return withSemaphore(
        weight,
        new Callable<T>() {
          @Override
          public T call() throws Exception {
            task.run();
            return result;
          }
        });
  }

  @Nonnull
  @Override
  public <T> ListenableFuture<T> submit(Runnable task, @Nullable T result) {
    return submit(task, result, defaultWeight);
  }

  public <T> ListenableFuture<T> submit(Callable<T> task, int weight) {
    return withSemaphore(weight, task);
  }

  @Nonnull
  @Override
  public <T> ListenableFuture<T> submit(Callable<T> task) {
    return submit(task, defaultWeight);
  }

  @Override
  public final boolean awaitTermination(long timeout, @Nonnull TimeUnit unit)
      throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  @Override
  public final boolean isShutdown() {
    return delegate.isShutdown();
  }

  @Override
  public final boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public final void shutdown() {
    delegate.shutdown();
  }

  @Override
  public final List<Runnable> shutdownNow() {
    return delegate.shutdownNow();
  }

  @Override
  public final void execute(@Nonnull Runnable command) {
    submit(command);
  }

}
