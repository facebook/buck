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

import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.Matchers;
import org.junit.Test;

public class WeightedListeningExecutorServiceTest {

  @Test
  public void submit() {
    WeightedListeningExecutorService service =
        new WeightedListeningExecutorService(
            new ListeningMultiSemaphore(
                ResourceAmounts.of(1, 0, 0, 0), ResourceAllocationFairness.FAIR),
            ResourceAmounts.of(1, 0, 0, 0),
            newDirectExecutorService());
    AtomicBoolean first = submitSetBool(service, ResourceAmounts.of(1, 0, 0, 0));
    assertTrue(first.get());
  }

  @Test
  @SuppressWarnings("PMD.EmptyWhileStmt")
  public void blockedSubmit() {
    WeightedListeningExecutorService service =
        new WeightedListeningExecutorService(
            new ListeningMultiSemaphore(
                ResourceAmounts.of(1, 0, 0, 0), ResourceAllocationFairness.FAIR),
            ResourceAmounts.of(1, 0, 0, 0),
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()));
    service.submit(
        new Runnable() {
          @Override
          public void run() {
            while (true) {
              /* block forever */
            }
          }
        },
        ResourceAmounts.of(1, 0, 0, 0));
    AtomicBoolean second = submitSetBool(service, ResourceAmounts.of(1, 0, 0, 0));
    assertFalse(second.get());
  }

  @Test
  public void cancelled() {
    ListeningMultiSemaphore semaphore =
        new ListeningMultiSemaphore(
            ResourceAmounts.of(1, 0, 0, 0), ResourceAllocationFairness.FAIR);
    ExplicitRunExecutorService wrappedService = new ExplicitRunExecutorService();
    WeightedListeningExecutorService service =
        new WeightedListeningExecutorService(
            semaphore, ResourceAmounts.of(1, 0, 0, 0), wrappedService);
    AtomicBoolean flag = new AtomicBoolean(false);
    ListenableFuture<Void> future =
        service.submit(
            new Callable<Void>() {
              @Override
              public Void call() {
                flag.set(true);
                return null;
              }
            });
    assertFalse(future.isDone());
    assertThat(semaphore.getAvailableResources(), Matchers.equalTo(ResourceAmounts.zero()));
    future.cancel(/* mayInterruptIfRunning */ false);
    wrappedService.run();
    assertTrue(future.isCancelled());
    assertFalse(flag.get());
    assertThat(semaphore.getAvailableResources(), Matchers.equalTo(ResourceAmounts.of(1, 0, 0, 0)));
  }

  private AtomicBoolean submitSetBool(
      WeightedListeningExecutorService service, ResourceAmounts amounts) {
    AtomicBoolean bool = new AtomicBoolean(false);
    service.submit(
        new Callable<Void>() {
          @Override
          public Void call() {
            bool.set(true);
            return null;
          }
        },
        amounts);
    return bool;
  }
}
