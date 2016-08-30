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

import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public class ListeningMultiSemaphoreTest {
  @Test
  public void testCreatingWithMaximumValues() {
    ImmutableMap<String, Integer> values = ImmutableMap.of("cpu", 2, "memory", 10);
    ListeningMultiSemaphore array = new ListeningMultiSemaphore(values);
    assertThat(array.getAvailableResources(), Matchers.equalTo(values));
    assertThat(array.getMaximumValues(), Matchers.equalTo(values));
    assertThat(array.getQueueLength(), Matchers.equalTo(0));
  }

  @Test
  public void testAcquiringResources() {
    ImmutableMap<String, Integer> values = ImmutableMap.of("cpu", 2);
    ListeningMultiSemaphore array = new ListeningMultiSemaphore(values);

    ListenableFuture<Void> future = array.acquire(ImmutableMap.of("cpu", 1));
    assertThat(future.isDone(), Matchers.equalTo(true));
    assertThat(array.getQueueLength(), Matchers.equalTo(0));
    assertThat(
        array.getAvailableResources(),
        Matchers.equalTo(ImmutableMap.of("cpu", 1)));
  }

  @Test
  public void testReleasingResources() {
    ImmutableMap<String, Integer> values = ImmutableMap.of("cpu", 2);
    ListeningMultiSemaphore array = new ListeningMultiSemaphore(values);

    array.acquire(ImmutableMap.of("cpu", 1));
    array.release(ImmutableMap.of("cpu", 1));
    assertThat(array.getAvailableResources(), Matchers.equalTo(values));
  }

  @Test
  public void testProcessingPendingQueue() {
    ImmutableMap<String, Integer> values = ImmutableMap.of("cpu", 7);
    ListeningMultiSemaphore array = new ListeningMultiSemaphore(values);

    ListenableFuture<Void> f1 = array.acquire(ImmutableMap.of("cpu", 3));
    assertThat(f1.isDone(), Matchers.equalTo(true));
    assertThat(array.getAvailableResources(), Matchers.equalTo(ImmutableMap.of("cpu", 4)));

    ListenableFuture<Void> f2 = array.acquire(ImmutableMap.of("cpu", 5));
    assertThat(f2.isDone(), Matchers.equalTo(false));
    assertThat(array.getQueueLength(), Matchers.equalTo(1));
    assertThat(array.getAvailableResources(), Matchers.equalTo(ImmutableMap.of("cpu", 4)));

    final AtomicBoolean f2HasBeenReleased = new AtomicBoolean(false);
    f2.addListener(new Runnable() {
      @Override
      public void run() {
        f2HasBeenReleased.set(true);
      }
    }, MoreExecutors.newDirectExecutorService());

    array.release(ImmutableMap.of("cpu", 3));
    assertThat(f2HasBeenReleased.get(), Matchers.equalTo(true));
    assertThat(array.getAvailableResources(), Matchers.equalTo(ImmutableMap.of("cpu", 2)));

    array.release(ImmutableMap.of("cpu", 5));
    assertThat(array.getAvailableResources(), Matchers.equalTo(array.getMaximumValues()));
  }

  @Test
  public void testProcessingPendingQueueWithCancelledFuturesReleasesPendingItems() {
    ImmutableMap<String, Integer> values = ImmutableMap.of("cpu", 7);
    ListeningMultiSemaphore array = new ListeningMultiSemaphore(values);

    // this step acquired some resources.
    ListenableFuture<Void> f1 = array.acquire(ImmutableMap.of("cpu", 5));
    assertThat(f1.isDone(), Matchers.equalTo(true));
    assertThat(array.getAvailableResources(), Matchers.equalTo(ImmutableMap.of("cpu", 2)));

    // then, this step tried to acquire, but we cancel this future. When pending queue is being
    // processed later, this acquisition should not happen - future is cancelled!
    final ListenableFuture<Void> toBeCancelled = array.acquire(ImmutableMap.of("cpu", 5));
    assertThat(toBeCancelled.isDone(), Matchers.equalTo(false));
    assertThat(array.getQueueLength(), Matchers.equalTo(1));
    assertThat(array.getAvailableResources(), Matchers.equalTo(ImmutableMap.of("cpu", 2)));
    toBeCancelled.cancel(true);
    final AtomicBoolean toBeCancelledIsReleased = new AtomicBoolean(false);
    toBeCancelled.addListener(new Runnable() {
      @Override
      public void run() {
        toBeCancelledIsReleased.set(toBeCancelled.isCancelled());
      }
    }, MoreExecutors.newDirectExecutorService());

    // this should be released, because previous future is cancelled,
    // so resources should become free
    final ListenableFuture<Void> toBeReleaseAfterCancellation =
        array.acquire(ImmutableMap.of("cpu", 6));
    assertThat(toBeReleaseAfterCancellation.isDone(), Matchers.equalTo(false));
    assertThat(array.getQueueLength(), Matchers.equalTo(2));
    assertThat(array.getAvailableResources(), Matchers.equalTo(ImmutableMap.of("cpu", 2)));
    // this should happen
    final AtomicBoolean toBeReleaseAfterCancellationIsReleased = new AtomicBoolean(false);
    toBeReleaseAfterCancellation.addListener(new Runnable() {
      @Override
      public void run() {
        toBeReleaseAfterCancellationIsReleased.set(toBeReleaseAfterCancellation.isCancelled());
      }
    }, MoreExecutors.newDirectExecutorService());


    // release resources acquired by f1
    array.release(ImmutableMap.of("cpu", 5));
    // isCancelled():
    assertThat(toBeCancelledIsReleased.get(), Matchers.equalTo(true));
    // !isCancelled():
    assertThat(toBeReleaseAfterCancellationIsReleased.get(), Matchers.equalTo(false));
    assertThat(array.getQueueLength(), Matchers.equalTo(0));
    assertThat(array.getAvailableResources(), Matchers.equalTo(ImmutableMap.of("cpu", 1)));

    array.release(ImmutableMap.of("cpu", 6));
    assertThat(array.getAvailableResources(), Matchers.equalTo(array.getMaximumValues()));
  }

  @Test
  public void testAcquiringAndReleasingMultipleResources() {
    ImmutableMap<String, Integer> values = ImmutableMap.of("cpu", 7, "mem", 7);
    ListeningMultiSemaphore array = new ListeningMultiSemaphore(values);

    ListenableFuture<Void> cpuOnly = array.acquire(ImmutableMap.of("cpu", 5));
    ListenableFuture<Void> memOnly = array.acquire(ImmutableMap.of("mem", 5));

    assertThat(cpuOnly.isDone(), Matchers.equalTo(true));
    assertThat(memOnly.isDone(), Matchers.equalTo(true));
    assertThat(
        array.getAvailableResources(),
        Matchers.equalTo(ImmutableMap.of("cpu", 2, "mem", 2)));

    ListenableFuture<Void> cpuAndMem1 = array.acquire(ImmutableMap.of("cpu", 4, "mem", 4));
    assertThat(cpuAndMem1.isDone(), Matchers.equalTo(false));

    ListenableFuture<Void> cpuAndMem2 = array.acquire(ImmutableMap.of("cpu", 2, "mem", 2));
    assertThat(cpuAndMem2.isDone(), Matchers.equalTo(true));
    assertThat(
        array.getAvailableResources(),
        Matchers.equalTo(ImmutableMap.of("cpu", 0, "mem", 0)));

    ListenableFuture<Void> cpuAndMem3 = array.acquire(ImmutableMap.of("cpu", 3, "mem", 3));
    assertThat(cpuAndMem3.isDone(), Matchers.equalTo(false));

    assertThat(array.getQueueLength(), Matchers.equalTo(2));

    array.release(ImmutableMap.of("cpu", 5));
    assertThat(cpuAndMem1.isDone(), Matchers.equalTo(false));
    assertThat(cpuAndMem3.isDone(), Matchers.equalTo(false));
    assertThat(
        array.getAvailableResources(),
        Matchers.equalTo(ImmutableMap.of("cpu", 5, "mem", 0)));

    array.release(ImmutableMap.of("mem", 5));
    assertThat(cpuAndMem1.isDone(), Matchers.equalTo(true));
    assertThat(cpuAndMem3.isDone(), Matchers.equalTo(false));
    assertThat(
        array.getAvailableResources(),
        Matchers.equalTo(ImmutableMap.of("cpu", 1, "mem", 1)));

    assertThat(array.getQueueLength(), Matchers.equalTo(1));

    array.release(ImmutableMap.of("cpu", 2, "mem", 2));
    assertThat(cpuAndMem3.isDone(), Matchers.equalTo(true));
    assertThat(
        array.getAvailableResources(),
        Matchers.equalTo(ImmutableMap.of("cpu", 0, "mem", 0)));
  }

  @Test
  public void testAcquiringNonExistentResources() throws Exception {
    ImmutableMap<String, Integer> values = ImmutableMap.of("cpu", 7, "mem", 7);
    ListeningMultiSemaphore array = new ListeningMultiSemaphore(values);

    try {
      array.acquire(ImmutableMap.of("ABCD", 7));
    } catch (IllegalArgumentException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              "ListeningMultiSemaphore ([mem, cpu]) was not initialized with " +
                  "the following resource names: [ABCD]"));
      return;
    }

    throw new Exception("IllegalArgumentException should be thrown");
  }
}
