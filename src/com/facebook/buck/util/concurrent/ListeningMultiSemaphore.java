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
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Wrapper around several ListeningSemaphore to manage the acquisition and release
 * of different resource types.
 */
public class ListeningMultiSemaphore {

  private ResourceAmounts usedValues;
  private final ResourceAmounts maximumValues;
  private final List<ListeningSemaphoreArrayPendingItem> pending = new LinkedList<>();

  public ListeningMultiSemaphore(ResourceAmounts availableResources) {
    this.usedValues = ResourceAmounts.ZERO;
    this.maximumValues = availableResources;
  }

  /**
   * Returns the future which will be completed by the moment when resources will be acquired.
   * Future may be returned already completed. You should subscribe to the future and perform your
   * resource requiring job once the future will be completed and not cancelled.
   * When you finish your job, you must release acquired resources by calling release() method
   * below.
   *
   * @param resources Resource amounts that need to be acquired.
   *
   * @return Future that will be completed once resource will be acquired.
   */
  public synchronized ListenableFuture<Void> acquire(ResourceAmounts resources) {
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
   * @param resources Resource amounts that need to be released. This argument should
   *                  match one you used during resource acquiring.
   */
  public synchronized void release(ResourceAmounts resources) {
    decreaseUsedResources(resources);
    processPendingFutures();
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

  private synchronized boolean checkIfResourcesAvailable(ResourceAmounts resources) {
    return !usedValues.append(resources).containsValuesGreaterThan(maximumValues);
  }

  private synchronized void increaseUsedResources(ResourceAmounts resources) {
    ResourceAmounts updatedAmounts = usedValues.append(resources);
    Preconditions.checkArgument(
        !updatedAmounts.containsValuesGreaterThan(maximumValues),
        "Cannot decrease available resources by %s. Current: %s, Maximum: %s",
        resources, usedValues, maximumValues);
    usedValues = updatedAmounts;
  }

  private synchronized void decreaseUsedResources(ResourceAmounts resources) {
    ResourceAmounts updatedAmounts = usedValues.subtract(resources);
    Preconditions.checkArgument(
        !updatedAmounts.containsValuesLessThan(ResourceAmounts.ZERO),
        "Cannot increase available resources by %s. Current: %s, Maximum: %s",
        resources, usedValues, maximumValues);
    usedValues = updatedAmounts;
  }

  private synchronized void processPendingFutures() {
    Iterator<ListeningSemaphoreArrayPendingItem> iterator = pending.iterator();

    while (iterator.hasNext()) {
      ListeningSemaphoreArrayPendingItem item = iterator.next();
      if (checkIfResourcesAvailable(item.getResources())) {
        iterator.remove();
        increaseUsedResources(item.getResources());
        if (!item.getFuture().set(null)) {
          decreaseUsedResources(item.getResources());
        }
      }
    }
  }

}
