/*
 * Copyright 2012-present Facebook, Inc.
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.Uninterruptibles;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public class MoreFutures {

  /** Utility class: do not instantiate. */
  private MoreFutures() {}

  /**
   * Invoke multiple callables on the provided executor and wait for all to return successfully.
   * An exception is thrown (immediately) if any callable fails.
   * @param executorService Executor service.
   * @param callables Callables to call.
   * @return List of values from each invoked callable in order if all callables execute without
   *     throwing.
   * @throws ExecutionException If any callable throws an exception, the first of such is wrapped
   *     in an ExecutionException and thrown.  Access via
   *     {@link java.util.concurrent.ExecutionException#getCause()}}.
   */
  public static <V> List<V> getAllUninterruptibly(
      ListeningExecutorService executorService,
      Iterable<Callable<V>> callables) throws ExecutionException {
    // Invoke.
    ImmutableList.Builder<ListenableFuture<V>> futures = ImmutableList.builder();
    for (Callable<V> callable : callables) {
      ListenableFuture<V> future = executorService.submit(callable);
      futures.add(future);
    }

    // Wait for completion.
    ListenableFuture<List<V>> allAsOne = Futures.allAsList(futures.build());
    return Uninterruptibles.getUninterruptibly(allAsOne);
  }

  /**
   * Create a convenience method for checking whether a future completed successfully because this
   * does not appear to be possible to do in a more direct way:
   * https://groups.google.com/forum/?fromgroups=#!topic/guava-discuss/rofEhagKnOc.
   *
   * @return true if the specified future has been resolved without throwing an exception or being
   *     cancelled.
   */
  public static boolean isSuccess(ListenableFuture<?> future) {
    if (!future.isDone()) {
      return false;
    }

    try {
      // Try to get the future, but ignore (and preserve) the thread interrupted state.
      // This should be fast because we know the future is already complete.
      Uninterruptibles.getUninterruptibly(future);
      return true;
    } catch (ExecutionException e) {
      // The computation threw an exception, so it did not complete successfully.
      return false;
    } catch (CancellationException e) {
      // The computation was cancelled, so it did not complete successfully.
      return false;
    }
  }

  /**
   * Returns the failure for a {@link ListenableFuture}.
   * @param future Must have completed unsuccessfully.
   */
  public static Throwable getFailure(ListenableFuture<?> future) {
    Preconditions.checkArgument(future.isDone());
    Preconditions.checkArgument(!isSuccess(future));
    try {
      future.get();
      throw new IllegalStateException("get() should have thrown an exception");
    } catch (ExecutionException e) {
      return e.getCause();
    } catch (CancellationException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }
}
