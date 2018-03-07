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

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ListeningMultiSemaphoreTest {
  @Test
  public void testCreatingWithMaximumValues() {
    ResourceAmounts values = ResourceAmounts.of(2, 10, 0, 0);
    ListeningMultiSemaphore array = getFairListeningMultiSemaphore(values);
    assertThat(array.getAvailableResources(), Matchers.equalTo(values));
    assertThat(array.getMaximumValues(), Matchers.equalTo(values));
    assertThat(array.getQueueLength(), Matchers.equalTo(0));
  }

  @Test
  public void testAcquiringResources() {
    ResourceAmounts values = amountsOfCpu(2);
    ListeningMultiSemaphore array = getFairListeningMultiSemaphore(values);

    ListenableFuture<Void> future = array.acquire(amountsOfCpu(1));
    assertThat(future.isDone(), Matchers.equalTo(true));
    assertThat(array.getQueueLength(), Matchers.equalTo(0));
    assertThat(array.getAvailableResources(), Matchers.equalTo(amountsOfCpu(1)));
  }

  @Test
  public void testReleasingResources() {
    ResourceAmounts values = amountsOfCpu(2);
    ListeningMultiSemaphore array = getFairListeningMultiSemaphore(values);

    array.acquire(amountsOfCpu(1));
    array.release(amountsOfCpu(1));
    assertThat(array.getAvailableResources(), Matchers.equalTo(values));
  }

  @Test
  public void testProcessingPendingQueue() {
    ResourceAmounts values = amountsOfCpu(7);
    ListeningMultiSemaphore array = getFairListeningMultiSemaphore(values);

    ListenableFuture<Void> f1 = array.acquire(amountsOfCpu(3));
    assertThat(f1.isDone(), Matchers.equalTo(true));
    assertThat(array.getAvailableResources(), Matchers.equalTo(amountsOfCpu(4)));

    ListenableFuture<Void> f2 = array.acquire(amountsOfCpu(5));
    assertThat(f2.isDone(), Matchers.equalTo(false));
    assertThat(array.getQueueLength(), Matchers.equalTo(1));
    assertThat(array.getAvailableResources(), Matchers.equalTo(amountsOfCpu(4)));

    AtomicBoolean f2HasBeenReleased = new AtomicBoolean(false);
    f2.addListener(() -> f2HasBeenReleased.set(true), MoreExecutors.newDirectExecutorService());

    array.release(amountsOfCpu(3));
    assertThat(f2HasBeenReleased.get(), Matchers.equalTo(true));
    assertThat(array.getAvailableResources(), Matchers.equalTo(amountsOfCpu(2)));

    array.release(amountsOfCpu(5));
    assertThat(array.getAvailableResources(), Matchers.equalTo(array.getMaximumValues()));
  }

  @Test
  public void testProcessingPendingQueueWithCancelledFuturesReleasesPendingItems() {
    ResourceAmounts values = amountsOfCpu(7);
    ListeningMultiSemaphore array = getFairListeningMultiSemaphore(values);

    // this step acquired some resources.
    ListenableFuture<Void> f1 = array.acquire(amountsOfCpu(5));
    assertThat(f1.isDone(), Matchers.equalTo(true));
    assertThat(array.getAvailableResources(), Matchers.equalTo(amountsOfCpu(2)));

    // then, this step tried to acquire, but we cancel this future. When pending queue is being
    // processed later, this acquisition should not happen - future is cancelled!
    ListenableFuture<Void> toBeCancelled = array.acquire(amountsOfCpu(5));
    assertThat(toBeCancelled.isDone(), Matchers.equalTo(false));
    assertThat(array.getQueueLength(), Matchers.equalTo(1));
    assertThat(array.getAvailableResources(), Matchers.equalTo(amountsOfCpu(2)));
    toBeCancelled.cancel(true);
    AtomicBoolean toBeCancelledIsReleased = new AtomicBoolean(false);
    toBeCancelled.addListener(
        () -> toBeCancelledIsReleased.set(toBeCancelled.isCancelled()),
        MoreExecutors.newDirectExecutorService());

    // this should be released, because previous future is cancelled,
    // so resources should become free
    ListenableFuture<Void> toBeReleaseAfterCancellation = array.acquire(amountsOfCpu(6));
    assertThat(toBeReleaseAfterCancellation.isDone(), Matchers.equalTo(false));
    assertThat(array.getQueueLength(), Matchers.equalTo(2));
    assertThat(array.getAvailableResources(), Matchers.equalTo(amountsOfCpu(2)));
    // this should happen
    AtomicBoolean toBeReleaseAfterCancellationIsReleased = new AtomicBoolean(false);
    toBeReleaseAfterCancellation.addListener(
        () ->
            toBeReleaseAfterCancellationIsReleased.set(toBeReleaseAfterCancellation.isCancelled()),
        MoreExecutors.newDirectExecutorService());

    // release resources acquired by f1
    array.release(amountsOfCpu(5));
    // isCancelled():
    assertThat(toBeCancelledIsReleased.get(), Matchers.equalTo(true));
    // !isCancelled():
    assertThat(toBeReleaseAfterCancellationIsReleased.get(), Matchers.equalTo(false));
    assertThat(array.getQueueLength(), Matchers.equalTo(0));
    assertThat(array.getAvailableResources(), Matchers.equalTo(amountsOfCpu(1)));

    array.release(amountsOfCpu(6));
    assertThat(array.getAvailableResources(), Matchers.equalTo(array.getMaximumValues()));
  }

  @Test
  public void testAcquiringAndReleasingMultipleResources() {
    ResourceAmounts values = amountsOfCpuAndMemory(7, 7);
    ListeningMultiSemaphore array = getFairListeningMultiSemaphore(values);

    ListenableFuture<Void> cpuOnly = array.acquire(amountsOfCpu(5));
    ListenableFuture<Void> memOnly = array.acquire(amountsOfMemory(5));

    assertThat(cpuOnly.isDone(), Matchers.equalTo(true));
    assertThat(memOnly.isDone(), Matchers.equalTo(true));
    assertThat(array.getAvailableResources(), Matchers.equalTo(ResourceAmounts.of(2, 2, 0, 0)));

    ListenableFuture<Void> cpuAndMem1 = array.acquire(amountsOfCpuAndMemory(4, 4));
    assertThat(cpuAndMem1.isDone(), Matchers.equalTo(false));

    ListenableFuture<Void> cpuAndMem2 = array.acquire(amountsOfCpuAndMemory(2, 2));
    assertThat(cpuAndMem2.isDone(), Matchers.equalTo(true));
    assertThat(array.getAvailableResources(), Matchers.equalTo(amountsOfCpuAndMemory(0, 0)));

    ListenableFuture<Void> cpuAndMem3 = array.acquire(amountsOfCpuAndMemory(3, 3));
    assertThat(cpuAndMem3.isDone(), Matchers.equalTo(false));

    assertThat(array.getQueueLength(), Matchers.equalTo(2));

    array.release(amountsOfCpu(5));
    assertThat(cpuAndMem1.isDone(), Matchers.equalTo(false));
    assertThat(cpuAndMem3.isDone(), Matchers.equalTo(false));
    assertThat(array.getAvailableResources(), Matchers.equalTo(amountsOfCpuAndMemory(5, 0)));

    array.release(amountsOfMemory(5));
    assertThat(cpuAndMem1.isDone(), Matchers.equalTo(true));
    assertThat(cpuAndMem3.isDone(), Matchers.equalTo(false));
    assertThat(array.getAvailableResources(), Matchers.equalTo(amountsOfCpuAndMemory(1, 1)));

    assertThat(array.getQueueLength(), Matchers.equalTo(1));

    array.release(amountsOfCpuAndMemory(2, 2));
    assertThat(cpuAndMem3.isDone(), Matchers.equalTo(true));
    assertThat(array.getAvailableResources(), Matchers.equalTo(amountsOfCpuAndMemory(0, 0)));
  }

  @Test
  public void testCappingToMaximumAmounts() {
    ListeningMultiSemaphore semaphore =
        new ListeningMultiSemaphore(amountsOfCpu(5), ResourceAllocationFairness.FAST);

    // Try to acquire more permits than we have, which should block.
    ListenableFuture<Void> first = semaphore.acquire(amountsOfCpu(100500));
    assertThat(semaphore.getAvailableResources(), Matchers.equalTo(amountsOfCpu(0)));
    assertThat(first.isDone(), Matchers.equalTo(true));

    semaphore.release(amountsOfCpu(100500));
    assertThat(semaphore.getAvailableResources(), Matchers.equalTo(semaphore.getMaximumValues()));
  }

  @Test
  public void fastFairness() {
    ListeningMultiSemaphore semaphore =
        new ListeningMultiSemaphore(amountsOfCpu(4), ResourceAllocationFairness.FAST);

    semaphore.acquire(amountsOfCpu(2));

    // Try to acquire more permits than we have, which should block.
    ListenableFuture<Void> first = semaphore.acquire(amountsOfCpu(4));
    assertThat(semaphore.getAvailableResources(), Matchers.equalTo(amountsOfCpu(2)));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(1));
    assertThat(first.isDone(), Matchers.equalTo(false));

    // Acquire a single permit and verify it goes through.
    ListenableFuture<Void> second = semaphore.acquire(amountsOfCpu(1));
    assertThat(semaphore.getAvailableResources(), Matchers.equalTo(amountsOfCpu(1)));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(1));
    assertThat(second.isDone(), Matchers.equalTo(true));
  }

  private ListeningMultiSemaphore getFairListeningMultiSemaphore(ResourceAmounts values) {
    return new ListeningMultiSemaphore(values, ResourceAllocationFairness.FAIR);
  }

  private static ResourceAmounts amountsOfCpu(int cpu) {
    return amountsOfCpuAndMemory(cpu, 0);
  }

  private static ResourceAmounts amountsOfMemory(int memory) {
    return amountsOfCpuAndMemory(0, memory);
  }

  private static ResourceAmounts amountsOfCpuAndMemory(int cpu, int memory) {
    return ResourceAmounts.builder()
        .setCpu(cpu)
        .setMemory(memory)
        .setDiskIO(0)
        .setNetworkIO(0)
        .build();
  }
}
