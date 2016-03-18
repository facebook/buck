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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.Pair;
import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class ListeningSemaphoreTest {

  @Test
  public void acquireRelease() {
    ListeningSemaphore semaphore = new ListeningSemaphore(1);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(1));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));

    // Verify that a simple acquire works.
    AtomicBoolean first = acquire(semaphore, 1);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(0));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
    assertTrue(first.get());

    // Verify a subsequent release works.
    semaphore.release(1);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(1));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
  }

  @Test
  public void weightedAcquire() {
    ListeningSemaphore semaphore = new ListeningSemaphore(2);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(2));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));

    // Verify that a simple acquire works.
    AtomicBoolean first = acquire(semaphore, 2);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(0));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
    assertTrue(first.get());

    // Verify a subsequent release works.
    semaphore.release(2);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(2));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
  }

  @Test
  public void doubleAcquireWithEnoughPermits() {
    ListeningSemaphore semaphore = new ListeningSemaphore(2);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(2));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));

    // Acquire a single permit and verify it goes through.
    AtomicBoolean first = acquire(semaphore, 1);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(1));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
    assertTrue(first.get());

    // Acquire a single permit and verify it goes through.
    AtomicBoolean second = acquire(semaphore, 1);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(0));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
    assertTrue(second.get());
  }

  @Test
  public void blockedAcquire() {
    ListeningSemaphore semaphore = new ListeningSemaphore(1);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(1));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));

    // Verify that a simple acquire works.
    AtomicBoolean first = acquire(semaphore, 1);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(0));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
    assertTrue(first.get());

    // Verify that a subsequent acquire blocks.
    AtomicBoolean second = acquire(semaphore, 1);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(0));
    assertFalse(second.get());
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(1));

    // Release the semaphore from the first acquisition and verify that the second
    // acquire was unblocked.
    semaphore.release(1);
    assertTrue(second.get());
    assertThat(semaphore.availablePermits(), Matchers.equalTo(0));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
  }

  @Test
  public void weightedBlockedAcquire() {
    ListeningSemaphore semaphore = new ListeningSemaphore(2);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(2));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));

    // Verify that a simple acquire works.
    AtomicBoolean first = acquire(semaphore, 1);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(1));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
    assertTrue(first.get());

    // Verify that a subsequent acquire blocks.
    AtomicBoolean second = acquire(semaphore, 2);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(1));
    assertFalse(second.get());
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(1));

    // Release the semaphore from the first acquisition and verify that the second
    // acquire was unblocked.
    semaphore.release(1);
    assertTrue(second.get());
    assertThat(semaphore.availablePermits(), Matchers.equalTo(0));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
  }

  @Test
  public void softCap() {
    ListeningSemaphore semaphore =
        new ListeningSemaphore(1, ListeningSemaphore.Cap.SOFT, ListeningSemaphore.Fairness.FAIR);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(1));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));

    // Verify that a simple acquire works.
    AtomicBoolean first = acquire(semaphore, 2);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(-1));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
    assertTrue(first.get());

    // Verify a subsequent release works.
    semaphore.release(2);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(1));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
  }

  @Test
  public void fastFairness() {
    ListeningSemaphore semaphore =
        new ListeningSemaphore(1, ListeningSemaphore.Cap.HARD, ListeningSemaphore.Fairness.FAST);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(1));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));

    // Try to acquire more permits than we have, which should block.
    AtomicBoolean first = acquire(semaphore, 2);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(1));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(1));
    assertFalse(first.get());

    // Acquire a single permit and verify it goes through.
    AtomicBoolean second = acquire(semaphore, 1);
    assertThat(semaphore.availablePermits(), Matchers.equalTo(0));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(1));
    assertTrue(second.get());
  }

  @Test
  public void cancelledWhileBlocked() {
    ListeningSemaphore semaphore = new ListeningSemaphore(1);

    // Do an initial acquire to grab the only permit.
    semaphore.acquire(1);

    // Initiate a second acquisition that'll block on the first one and then immediately cancel it.
    ListenableFuture<Void> future = semaphore.acquire(1);
    final AtomicBoolean flag = new AtomicBoolean(false);
    Futures.transform(
        future,
        new Function<Void, Object>() {
          @Override
          public Object apply(Void input) {
            flag.set(true);
            return null;
          }
        });
    assertFalse(future.isDone());
    assertFalse(flag.get());
    future.cancel(/* mayInterruptWhileRunning */ false);

    // Now release the first acquisition and verify that the second cancelled acquire didn't get
    // processed.
    semaphore.release(1);
    assertFalse(flag.get());
    assertThat(semaphore.availablePermits(), Matchers.equalTo(1));
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
  }

  @Test
  public void fuzz() {
    Random random = new Random();
    ListeningSemaphore semaphore =
        new ListeningSemaphore(10, ListeningSemaphore.Cap.HARD, ListeningSemaphore.Fairness.FAST);

    // Dispatch 1000 acquisitions of random size.
    List<Pair<Integer, AtomicBoolean>> tasks = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      int permits = 1 + random.nextInt(10);
      AtomicBoolean flag = acquire(semaphore, permits);
      tasks.add(new Pair<>(permits, flag));
    }

    // At this point we should have some queued acquisitions.
    assertThat(semaphore.getQueueLength(), Matchers.greaterThan(0));

    // Now iterate through the acquisitions, releasing their permits as they complete.  We'll
    // stop once we're no longer making progress.
    while (true) {
      List<Pair<Integer, AtomicBoolean>> remaining = new ArrayList<>();
      for (Pair<Integer, AtomicBoolean> task : tasks) {
        if (task.getSecond().get()) {
          semaphore.release(task.getFirst());
        } else {
          remaining.add(task);
        }
      }
      if (remaining.size() == tasks.size()) {
        assertThat(remaining.size(), Matchers.equalTo(0));
        break;
      }
      tasks = remaining;
    }

    // Now verify nothing is left in the queue, and all the permits are available again.
    assertThat(semaphore.getQueueLength(), Matchers.equalTo(0));
    assertThat(semaphore.availablePermits(), Matchers.equalTo(10));
  }

  private AtomicBoolean acquire(ListeningSemaphore semaphore, int permits) {
    final AtomicBoolean bool = new AtomicBoolean(false);
    Futures.transform(
        semaphore.acquire(permits),
        new Function<Void, Object>() {
          @Override
          public Object apply(Void input) {
            bool.set(true);
            return null;
          }
        });
    return bool;
  }

}
