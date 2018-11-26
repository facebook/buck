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

package com.facebook.buck.util.concurrent;

import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

/**
 * JobLimiter is a simple mechanism for queuing asynchronous work such that only a certain number of
 * items are active at a time.
 */
public class JobLimiter {
  private final Semaphore semaphore;
  private final ConcurrentLinkedQueue<PendingJob<?>> queue = new ConcurrentLinkedQueue<>();

  public JobLimiter(int limit) {
    Preconditions.checkArgument(limit >= 0);
    this.semaphore = new Semaphore(limit);
  }

  /**
   * Adds a new task to the queue. JobLimiter uses the provided service only for invoking the
   * callable itself.
   */
  public <V> ListenableFuture<V> schedule(
      ListeningExecutorService service, ThrowingSupplier<ListenableFuture<V>, Exception> callable) {
    // To help prevent stack overflows either this callable needs to be forced to be async, or the
    // release() call when it's finished needs to be. It's easier to do it here.
    ThrowingSupplier<ListenableFuture<V>, Exception> safeCallable =
        () -> Futures.submitAsync(callable::get, service);

    if (semaphore.tryAcquire()) {
      return send(safeCallable);
    }
    ListenableFuture<V> enqueued = enqueue(safeCallable);

    // It's possible that all running jobs finished after we failed to acquire the semaphore and
    // before we enqueued the callable. To not get stuck in the queue forever, try again.
    if (semaphore.tryAcquire()) {
      release();
    }
    return enqueued;
  }

  private static class PendingJob<T> {
    private final ThrowingSupplier<ListenableFuture<T>, Exception> callable;

    private final SettableFuture<T> future = SettableFuture.create();

    public PendingJob(ThrowingSupplier<ListenableFuture<T>, Exception> callable) {
      this.callable = callable;
    }

    private void send(JobLimiter jobLimiter) {
      future.setFuture(jobLimiter.send(callable));
    }
  }

  private <V> ListenableFuture<V> enqueue(
      ThrowingSupplier<ListenableFuture<V>, Exception> callable) {
    PendingJob<V> job = new PendingJob<>(callable);
    queue.add(job);
    return job.future;
  }

  private <V> ListenableFuture<V> send(ThrowingSupplier<ListenableFuture<V>, Exception> callable) {
    ListenableFuture<V> future;
    try {
      future = callable.get();
    } catch (Throwable e) {
      future = Futures.immediateFailedFuture(e);
    }
    Futures.addCallback(future, MoreFutures.finallyCallback(this::release));
    return future;
  }

  private void release() {
    PendingJob<?> job = queue.poll();
    if (job == null) {
      semaphore.release();
    } else {
      job.send(this);
    }
  }
}
