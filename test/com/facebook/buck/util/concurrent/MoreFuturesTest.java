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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.junit.Test;

public class MoreFuturesTest {

  @Test
  public void testIsSuccess() throws InterruptedException {
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
  public void testGetFailure() throws InterruptedException {
    Throwable failure = new Throwable();
    ListenableFuture<Object> failedFuture = Futures.immediateFailedFuture(failure);
    assertEquals(failure, MoreFutures.getFailure(failedFuture));
  }

  @Test(expected = NullPointerException.class)
  public void testGetFailureRejectsNullFuture() throws InterruptedException {
    MoreFutures.getFailure(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testGetFailureRequiresSatisfiedFuture() throws InterruptedException {
    MoreFutures.getFailure(SettableFuture.create());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testGetFailureRequiresUnsuccessfulFuture() throws InterruptedException {
    ListenableFuture<Object> success = Futures.immediateFuture(new Object());
    MoreFutures.getFailure(success);
  }

  @Test(expected = IllegalStateException.class)
  public void testGetFailureRequiresNonCancelledFuture() throws InterruptedException {
    ListenableFuture<?> canceledFuture = SettableFuture.create();
    canceledFuture.cancel(/* mayInterruptIfRunning */ true);
    MoreFutures.getFailure(canceledFuture);
  }
}
