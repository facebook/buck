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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MoreFuturesTest {

  @Test
  public void testIsSuccess() {
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

  /**
   * Verifies that isSuccess will not throw InterruptedException, but will preserve the thread's
   * interrupted state.  Note that due to unavoidable race conditions, there is a chance that
   * this test could pass even if the code were wrong.  It should never spuriously fail, though.
   */
  @Test
  public void testInterruption() throws InterruptedException {
    // Problems encountered on the background thread, which will be put into a failure message
    // on the foreground thread.
    final List<String> backgroundThreadProblems = Lists.newArrayList();
    // Set to true after the background thread first sees that the future was successful.
    final AtomicBoolean sawSuccessfulFuture = new AtomicBoolean();
    // Set to true when the background thread sees that it was interrupted.
    final AtomicBoolean sawInterruption = new AtomicBoolean();
    // Used to spin the CPU to minimize the chance of a spurious test pass.
    final AtomicInteger doNotOptimizeMe = new AtomicInteger();
    final int spinCount = 256;

    // Future that the background thread will test.
    // Don't make this into an immediate future, since they never throw InterruptedException.
    final SettableFuture<Void> future = SettableFuture.create();
    future.set(null);

    Runnable testingRunner = new Runnable() {
      @Override
      public void run() {
        try {
          // Check the future once and verify that it is successful.
          if (!MoreFutures.isSuccess(future)) {
            backgroundThreadProblems.add("Initial check was false.");
            return;
          }
          // Make sure we have not been interrupted yet.
          if (Thread.interrupted()) {
            backgroundThreadProblems.add("Was interrupted early.");
            return;
          }
          sawSuccessfulFuture.set(true);

          // Could put some sort of timeout here, but we'll leave that to the test runner.
          while (true) {

            // Spin some CPU to minimize the chance of interrupting after we check the future.
            for (int i = 0; i < spinCount; i++) {
              doNotOptimizeMe.incrementAndGet();
            }

            // Let isSuccess check the interrupted flag.  It should not throw.
            MoreFutures.isSuccess(future);
            // The old version of isSuccess could pass this test spuriously if we got interrupted
            // right here.  The doNotOptimizeMe loop above minimizes the chances of that.
            // See if we have been interrupted, and try again if not.
            if (Thread.interrupted()) {
              sawInterruption.set(true);
              break;
            }
          }

        } catch (Throwable t) {
          backgroundThreadProblems.add(t.toString());
        }
      }
    };

    // Start the background thread.
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.execute(testingRunner);
    // Wait for it to enter the main loop.
    while (!sawSuccessfulFuture.get()) {
      // Appease PMD.
      sawSuccessfulFuture.get();
    }
    // Interrupt it and wait for it to finish.
    List<Runnable> preempted = executor.shutdownNow();
    executor.awaitTermination(1, TimeUnit.SECONDS);

    assertTrue(sawSuccessfulFuture.get());
    assertTrue(sawInterruption.get());
    assertTrue(preempted.isEmpty());
    if (!backgroundThreadProblems.isEmpty()) {
      String msg = Joiner.on('\n').join(Iterables.concat(
          ImmutableList.of("Problems on background thread:"),
          backgroundThreadProblems,
          ImmutableList.of("")));
      fail(msg);
    }
  }

  @Test
  public void testGetFailure() {
    Throwable failure = new Throwable();
    ListenableFuture<Object> failedFuture = Futures.immediateFailedFuture(failure);
    assertEquals(failure, MoreFutures.getFailure(failedFuture));
  }

  @Test(expected = NullPointerException.class)
  public void testGetFailureRejectsNullFuture() {
    MoreFutures.getFailure(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testGetFailureRequiresSatisfiedFuture() {
    MoreFutures.getFailure(SettableFuture.create());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testGetFailureRequiresUnsuccessfulFuture() {
    ListenableFuture<Object> success = Futures.immediateFuture(new Object());
    MoreFutures.getFailure(success);
  }

  @Test(expected = IllegalStateException.class)
  public void testGetFailureRequiresNonCancelledFuture() {
    ListenableFuture<?> canceledFuture = SettableFuture.create();
    canceledFuture.cancel(/* mayInterruptIfRunning */ true);
    MoreFutures.getFailure(canceledFuture);
  }
}
