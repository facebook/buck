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

import java.util.AbstractMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * A semaphore using {@link ListenableFuture}s for acquisition rather than blocking.
 */
public class ListeningSemaphore {

  private final List<Map.Entry<Integer, SettableFuture<Void>>> pending = new LinkedList<>();
  private int size = 0;

  private final int maxSize;
  private final Cap cap;
  private final Fairness fairness;

  public ListeningSemaphore(
      int maxSize,
      Cap cap,
      Fairness fairness) {
    this.maxSize = maxSize;
    this.cap = cap;
    this.fairness = fairness;
  }

  public ListeningSemaphore(int maxSize) {
    this(maxSize, Cap.HARD, Fairness.FAIR);
  }

  private synchronized boolean canFit(int permits) {

    // If we have enough space, then always accept.
    if (size + permits <= maxSize) {
      return true;
    }

    // If soft cap is set, and size is 0, then accept even though this pushes us over the max size.
    if (cap == Cap.SOFT && size == 0) {
      return true;
    }

    return false;
  }

  public synchronized ListenableFuture<Void> acquire(int permits) {

    // If the semaphore isn't full, acquire it now.  Since an immediate future cannot be canceled,
    // there's no extra handling we have to do here.
    if (canFit(permits)) {
      size += permits;
      return Futures.immediateFuture(null);
    }

    // Otherwise, queue it up for later.
    SettableFuture<Void> future = SettableFuture.create();
    pending.add(new AbstractMap.SimpleEntry<>(permits, future));
    return future;
  }

  public synchronized ImmutableList<Map.Entry<Integer, SettableFuture<Void>>> releaseInternal(
      int permits) {
    ImmutableList.Builder<Map.Entry<Integer, SettableFuture<Void>>> scheduled =
        ImmutableList.builder();

    // Re-add the permits to the size.
    size -= permits;
    Preconditions.checkState(size >= 0);

    // Accept any pending requests that can fit.
    Iterator<Map.Entry<Integer, SettableFuture<Void>>> itr = pending.iterator();
    while (size < maxSize && itr.hasNext()) {
      Map.Entry<Integer, SettableFuture<Void>> entry = itr.next();
      if (canFit(entry.getKey())) {
        itr.remove();
        size += entry.getKey();
        scheduled.add(entry);
      } else if (fairness == Fairness.FAIR) {
        break;
      }
    }

    return scheduled.build();
  }

  public void release(int permits) {

    // Release the given permits and get back the list of new jobs that can be scheduled.
    ImmutableList<Map.Entry<Integer, SettableFuture<Void>>> ready = releaseInternal(permits);

    // Walk through the list of jobs ready to schedule and trigger them, keeping track of any ones
    // that were cancelled.
    int failed = 0;
    for (Map.Entry<Integer, SettableFuture<Void>> entry : ready) {
      // The future may be have been canceled, so keep track of how many permits correspond to the
      // failed jobs, as we'll need to re-release them at the end.
      if (!entry.getValue().set(null)) {
        failed += entry.getKey();
      }
    }

    // If we had any failed permits kicking off other jobs, then re-release them.
    if (failed > 0) {
      release(failed);
    }
  }

  public synchronized int availablePermits() {
    return maxSize - size;
  }

  public synchronized int getQueueLength() {
    return pending.size();
  }

  /**
   * How to handle permit counting passing the max permit limit.
   */
  public enum Cap {

    /**
     * Allow crossing the max permit level only if the current size of 0.  This allows progress
     * even when acquisitions use a permit size greater than the max.
     */
    SOFT,

    /**
     * Never allow crossing the max permit level.
     */
    HARD,

  }

  /**
   * How to handle fairness when acquiring the sempahore.
   */
  public enum Fairness {

    /**
     * Make acquisitions happen in order.  This may mean a high-permit count acquisition can block
     * smaller ones queued behind it that could otherwise grab the semaphore.
     */
    FAIR,

    /**
     * Move lower-permit count acquisitions that can acquire the lock ahead of high-count ones that
     * are blocked due to size.
     */
    FAST,

  }

}
