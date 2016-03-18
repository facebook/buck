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

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

public class WeightedListeningExecutorServiceTest {

  @Test
  public void submit() {
    WeightedListeningExecutorService service =
        new WeightedListeningExecutorService(
            new ListeningSemaphore(1),
            1,
            newDirectExecutorService());
    AtomicBoolean first = submitSetBool(service, 1);
    assertTrue(first.get());
  }

  @Test
  public void blockedSubmit() {
    WeightedListeningExecutorService service =
        new WeightedListeningExecutorService(
            new ListeningSemaphore(1),
            1,
            newDirectExecutorService());
    AtomicBoolean first = submitSetBool(service, 2);
    assertFalse(first.get());
  }

  @Test
  public void cancelled() {
    ListeningSemaphore semaphore = new ListeningSemaphore(1);
    ExplicitRunExecutorService wrappedService = new ExplicitRunExecutorService();
    WeightedListeningExecutorService service =
        new WeightedListeningExecutorService(semaphore, 1, wrappedService);
    final AtomicBoolean flag = new AtomicBoolean(false);
    ListenableFuture<Void> future =
        service.submit(
            new Callable<Void>() {
              @Override
              public Void call() throws Exception {
                flag.set(true);
                return null;
              }
            });
    assertFalse(future.isDone());
    assertThat(semaphore.availablePermits(), Matchers.equalTo(0));
    future.cancel(/* mayInterruptIfRunning */ false);
    wrappedService.run();
    assertTrue(future.isCancelled());
    assertFalse(flag.get());
    assertThat(semaphore.availablePermits(), Matchers.equalTo(1));
  }

  private AtomicBoolean submitSetBool(WeightedListeningExecutorService service, int weight) {
    final AtomicBoolean bool = new AtomicBoolean(false);
    service.submit(
        new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            bool.set(true);
            return null;
          }
        },
        weight);
    return bool;
  }

}
