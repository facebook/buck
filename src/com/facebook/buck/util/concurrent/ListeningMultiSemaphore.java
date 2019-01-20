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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * A semaphore using {@link ListenableFuture}s for acquisition of different resource types rather
 * than blocking.
 */
public class ListeningMultiSemaphore {

  private ResourceAmounts usedValues;
  private final ResourceAmounts maximumValues;
  private final List<ListeningSemaphoreArrayPendingItem> pending = new LinkedList<>();
  private final ResourceAllocationFairness fairness;

  public ListeningMultiSemaphore(
      ResourceAmounts availableResources, ResourceAllocationFairness fairness) {
    this.usedValues = ResourceAmounts.zero();
    this.maximumValues = availableResources;
    this.fairness = fairness;
  }

  /**
   * Returns the future which will be completed by the moment when resources will be acquired.
   * Future may be returned already completed. You should subscribe to the future and perform your
   * resource requiring job once the future will be completed and not cancelled. When you finish
   * your job, you must release acquired resources by calling release() method below.
   *
   * @param resources Resource amounts that need to be acquired. If they are higher than maximum
   *     amounts, they will be capped to them.
   * @return Future that will be completed once resource will be acquired.
   */
  public synchronized ListenableFuture<Void> acquire(ResourceAmounts resources) {
    if (resources.equals(ResourceAmounts.zero())) {
      return Futures.immediateFuture(null);
    }

    resources = capResourceAmounts(resources);
    if (!checkIfResourcesAvailable(resources)) {
      SettableFuture<Void> pendingFuture = SettableFuture.create();
      pending.add(ListeningSemaphoreArrayPendingItem.of(pendingFuture, resources));
      return pendingFuture;
    }
    increaseUsedResources(resources);
    return Futures.immediateFuture(null);
  }

  /**
   * Releases previously acquired resources.
   *
   * @param resources Resource amounts that need to be released. This argument should match one you
   *     used during resource acquiring.
   */
  public void release(ResourceAmounts resources) {
    if (resources.equals(ResourceAmounts.zero())) {
      return;
    }

    resources = capResourceAmounts(resources);
    decreaseUsedResources(resources);
    processPendingFutures(getPendingItemsThatCanBeProcessed());
  }

  private synchronized ImmutableList<ListeningSemaphoreArrayPendingItem>
      getPendingItemsThatCanBeProcessed() {
    ImmutableList.Builder<ListeningSemaphoreArrayPendingItem> builder = ImmutableList.builder();

    Iterator<ListeningSemaphoreArrayPendingItem> iterator = pending.iterator();
    while (!getAvailableResources().equals(ResourceAmounts.zero()) && iterator.hasNext()) {
      ListeningSemaphoreArrayPendingItem item = iterator.next();
      if (checkIfResourcesAvailable(item.getResources())) {
        builder.add(item);
        increaseUsedResources(item.getResources());
        iterator.remove();
      } else if (!fairnessAllowsReordering()) {
        break;
      }
    }
    return builder.build();
  }

  public synchronized ResourceAmounts getAvailableResources() {
    return maximumValues.subtract(usedValues);
  }

  public synchronized ResourceAmounts getMaximumValues() {
    return maximumValues;
  }

  public synchronized int getQueueLength() {
    return pending.size();
  }

  /**
   * We assume that if requested amounts are larger than we have in maximumValues, then intention
   * was to request all possible resources. This method caps the given resources to the maximum
   * possible value. Without capping the deadlock will happen.
   */
  private ResourceAmounts capResourceAmounts(ResourceAmounts amounts) {
    return ResourceAmounts.of(
        Math.min(amounts.getCpu(), maximumValues.getCpu()),
        Math.min(amounts.getMemory(), maximumValues.getMemory()),
        Math.min(amounts.getDiskIO(), maximumValues.getDiskIO()),
        Math.min(amounts.getNetworkIO(), maximumValues.getNetworkIO()));
  }

  private synchronized boolean checkIfResourcesAvailable(ResourceAmounts resources) {
    Preconditions.checkState(
        resources.allValuesLessThanOrEqual(maximumValues),
        "Resource amounts (%s) must be capped to the maximum amounts (%s)",
        resources,
        maximumValues);
    return usedValues.append(resources).allValuesLessThanOrEqual(maximumValues);
  }

  private synchronized void increaseUsedResources(ResourceAmounts resources) {
    usedValues = usedValues.append(resources);
  }

  private synchronized void decreaseUsedResources(ResourceAmounts resources) {
    ResourceAmounts updatedAmounts = usedValues.subtract(resources);
    Preconditions.checkArgument(
        !updatedAmounts.containsValuesLessThan(ResourceAmounts.zero()),
        "Cannot increase available resources by %s. Current: %s, Maximum: %s",
        resources,
        usedValues,
        maximumValues);
    usedValues = updatedAmounts;
  }

  private void processPendingFutures(ImmutableList<ListeningSemaphoreArrayPendingItem> items) {
    ResourceAmounts failedAmounts = ResourceAmounts.zero();

    for (ListeningSemaphoreArrayPendingItem item : items) {
      if (!item.getFuture().set(null)) {
        failedAmounts = failedAmounts.append(item.getResources());
      }
    }

    if (!failedAmounts.equals(ResourceAmounts.zero())) {
      release(failedAmounts);
    }
  }

  private boolean fairnessAllowsReordering() {
    return fairness == ResourceAllocationFairness.FAST;
  }
}
