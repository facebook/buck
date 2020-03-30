/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util.concurrent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.types.Pair;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class MoreFuturesTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void isSuccessReturnsTrueOnlyWhenFutureSuccessful() throws InterruptedException {
    SettableFuture<Object> unresolvedFuture = SettableFuture.create();
    assertFalse(MoreFutures.isSuccess(unresolvedFuture));

    SettableFuture<Object> failedFuture = SettableFuture.create();
    failedFuture.setException(new RuntimeException());
    assertFalse(MoreFutures.isSuccess(failedFuture));

    SettableFuture<Object> cancelledFuture = SettableFuture.create();
    cancelledFuture.cancel(/* mayInterruptIfRunning */ true);
    assertFalse(MoreFutures.isSuccess(cancelledFuture));

    SettableFuture<Object> resolvedFuture = SettableFuture.create();
    resolvedFuture.set(new Object());
    assertTrue(MoreFutures.isSuccess(resolvedFuture));
  }

  @Test
  public void getFailureReturnsTheFailingException() throws InterruptedException {
    Throwable failure = new Throwable();
    ListenableFuture<Object> failedFuture = Futures.immediateFailedFuture(failure);
    assertEquals(failure, MoreFutures.getFailure(failedFuture));
  }

  @Test
  public void getFailureRejectsNullFuture() throws InterruptedException {
    expectedException.expect(NullPointerException.class);
    MoreFutures.getFailure(null);
  }

  @Test
  public void getFailureRequiresSatisfiedFuture() throws InterruptedException {
    expectedException.expect(IllegalArgumentException.class);
    MoreFutures.getFailure(SettableFuture.create());
  }

  @Test
  public void getFailureRequiresUnsuccessfulFuture() throws InterruptedException {
    ListenableFuture<Object> success = Futures.immediateFuture(new Object());

    expectedException.expect(IllegalArgumentException.class);
    MoreFutures.getFailure(success);
  }

  @Test
  public void getFailureRequiresNonCancelledFuture() throws InterruptedException {
    ListenableFuture<?> canceledFuture = SettableFuture.create();
    canceledFuture.cancel(/* mayInterruptIfRunning */ true);

    expectedException.expect(IllegalStateException.class);
    MoreFutures.getFailure(canceledFuture);
  }

  @Test
  public void getFuturesUncheckedInterruptiblyBlocksUntilFutureCompletes() {
    SettableFuture<Boolean> future = SettableFuture.create();

    ExecutorService executor = MostExecutors.newSingleThreadExecutor("test");
    executor.submit(() -> future.set(true));

    assertTrue(MoreFutures.getUncheckedInterruptibly(future));

    executor.shutdownNow();
  }

  @Test
  public void getFuturesUncheckedInterruptiblyThrowsCancelledExceptionWhenFutureCancelled() {
    SettableFuture<?> future = SettableFuture.create();

    future.cancel(true);

    expectedException.expect(CancellationException.class);
    MoreFutures.getUncheckedInterruptibly(future);
  }

  @Test
  public void combineFuturesSucceed() throws InterruptedException, ExecutionException {
    SettableFuture<String> firstFuture = SettableFuture.create();
    SettableFuture<Integer> secondFuture = SettableFuture.create();

    ListeningExecutorService executor = MoreExecutors.newDirectExecutorService();

    ListenableFuture<Pair<String, Integer>> combinedFuture =
        MoreFutures.combinedFutures(firstFuture, secondFuture, executor);

    assertFalse(combinedFuture.isDone());

    executor.submit(() -> firstFuture.set("test"));

    assertFalse(combinedFuture.isDone());

    executor.submit(() -> secondFuture.set(42));

    assertTrue(combinedFuture.isDone());

    combinedFuture.get().getFirst().equals("test");
    combinedFuture.get().getSecond().equals(42);
  }

  @Test
  public void combineFuturesFailWhenOneFails() throws InterruptedException {
    SettableFuture<String> firstFuture = SettableFuture.create();
    SettableFuture<Integer> secondFuture = SettableFuture.create();

    ListeningExecutorService executor = MoreExecutors.newDirectExecutorService();

    ListenableFuture<Pair<String, Integer>> combinedFuture =
        MoreFutures.combinedFutures(firstFuture, secondFuture, executor);

    assertFalse(combinedFuture.isDone());

    executor.submit(() -> firstFuture.setException(new Exception()));

    assertTrue(combinedFuture.isDone());
    assertFalse(MoreFutures.isSuccess(combinedFuture));
  }
}
