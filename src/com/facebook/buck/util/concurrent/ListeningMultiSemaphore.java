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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Wrapper around several ListeningSemaphore to manage the acquisition and release
 * of different resource types.
 */
public class ListeningMultiSemaphore {

  private final Map<String, Integer> actualCounters;
  private final ImmutableMap<String, Integer> maximumValues;
  private final List<ListeningSemaphoreArrayPendingItem> pending = new LinkedList<>();

  public ListeningMultiSemaphore(ImmutableMap<String, Integer> availableResources) {
    this.actualCounters = new HashMap<>();
    ImmutableMap.Builder<String, Integer> maximumValuesBuilder = ImmutableMap.builder();
    for (Map.Entry<String, Integer> entry : availableResources.entrySet()) {
      this.actualCounters.put(entry.getKey(), 0);
      maximumValuesBuilder.put(entry);
    }
    this.maximumValues = maximumValuesBuilder.build();
  }

  /**
   * Returns the future which will be completed by the moment when resources will be acquired.
   * Future may be returned already completed. You should subscribe to the future and perform your
   * resource requiring job once the future will be completed and not cancelled.
   * When you finish your job, you must release acquired resources by calling release() method
   * below.
   *
   * @param resources Resource names and their values that need to be acquired.
   *
   * @return Future that will be completed once resource will be acquired.
   *
   * @throws IllegalArgumentException if resources argument contains keys that are not present in
   * the multi semaphore.
   */
  public synchronized ListenableFuture<Void> acquire(Map<String, Integer> resources) {
    if (!checkIfResourcesAvailable(resources)) {
      SettableFuture<Void> pendingFuture = SettableFuture.create();
      pending.add(ListeningSemaphoreArrayPendingItem.of(pendingFuture, resources));
      return pendingFuture;
    }

    decreaseAvailableResources(resources);
    return Futures.immediateFuture(null);
  }

  /**
   * Releases previously acquired resources.
   *
   * @param resources Resource names and their values that need to be released. This argument should
   *                  match one you used during resource acquiring.
   *
   * @throws IllegalArgumentException if resources argument contains keys that are not present in
   * the multi semaphore.
   */
  public synchronized void release(Map<String, Integer> resources) {
    increaseAvailableResources(resources);
    processPendingFutures();
  }

  public synchronized ImmutableMap<String, Integer> getAvailableResources() {
    ImmutableMap.Builder<String, Integer> builder = ImmutableMap.builder();

    for (String key : maximumValues.keySet()) {
      Integer maximumValue = maximumValues.get(key);
      Integer currentValue = actualCounters.get(key);
      Preconditions.checkNotNull(maximumValue);
      Preconditions.checkNotNull(currentValue);
      builder.put(key, maximumValue - currentValue);
    }

    return builder.build();
  }

  public synchronized ImmutableMap<String, Integer> getMaximumValues() {
    return maximumValues;
  }

  public synchronized int getQueueLength() {
    return pending.size();
  }

  private synchronized void checkResourceNames(Map<String, Integer> resources) {
    Sets.SetView<String> unknownResourceNames = Sets.difference(
        resources.keySet(),
        actualCounters.keySet());
    if (unknownResourceNames.size() != 0) {
      throw new IllegalArgumentException(
          String.format(
              "ListeningMultiSemaphore (%s) was not initialized with " +
                  "the following resource names: %s",
              actualCounters.keySet(),
              unknownResourceNames));
    }
  }

  private synchronized boolean checkIfResourcesAvailable(Map<String, Integer> resources) {
    checkResourceNames(resources);
    for (Map.Entry<String, Integer> entry : resources.entrySet()) {
      Integer currentValue = actualCounters.get(entry.getKey());
      final Integer maximumValue = maximumValues.get(entry.getKey());
      Preconditions.checkNotNull(maximumValue);
      Preconditions.checkNotNull(currentValue);
      if (currentValue + entry.getValue() > maximumValue) {
        return false;
      }
    }
    return true;
  }

  private synchronized void decreaseAvailableResources(Map<String, Integer> resources) {
    checkResourceNames(resources);
    for (Map.Entry<String, Integer> entry : resources.entrySet()) {
      Integer oldValue = actualCounters.get(entry.getKey());
      Preconditions.checkNotNull(oldValue);
      Integer newValue = oldValue + entry.getValue();
      actualCounters.put(entry.getKey(), newValue);
    }
  }

  private synchronized void increaseAvailableResources(Map<String, Integer> resources) {
    checkResourceNames(resources);
    for (Map.Entry<String, Integer> entry : resources.entrySet()) {
      Integer oldValue = actualCounters.get(entry.getKey());
      Preconditions.checkNotNull(oldValue);
      Integer newValue = oldValue - entry.getValue();
      Preconditions.checkArgument(
          newValue.intValue() >= 0,
          "Resource %s has been over-released, new actual value is %d, old value was %d",
          entry.getKey(), newValue, oldValue);
      actualCounters.put(entry.getKey(), newValue);
    }
  }

  private synchronized void processPendingFutures() {
    Iterator<ListeningSemaphoreArrayPendingItem> iterator = pending.iterator();

    while (iterator.hasNext()) {
      ListeningSemaphoreArrayPendingItem item = iterator.next();
      if (checkIfResourcesAvailable(item.getResources())) {
        iterator.remove();
        decreaseAvailableResources(item.getResources());
        if (!item.getFuture().set(null)) {
          increaseAvailableResources(item.getResources());
        }
      }
    }
  }

}
