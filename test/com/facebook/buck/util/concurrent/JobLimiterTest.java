/*
 * Copyright 2018-present Facebook, Inc.
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

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class JobLimiterTest {

  @Test
  public void testLimitsJobs() throws Exception {
    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(10));

    JobLimiter limiter = new JobLimiter(2);

    Semaphore jobStarted = new Semaphore(0);
    Semaphore jobFinished = new Semaphore(0);
    Semaphore jobWaiting = new Semaphore(0);

    AtomicInteger jobsRunning = new AtomicInteger();

    List<ListenableFuture<?>> futures = new ArrayList<>();

    for (int i = 0; i < 10; i++) {
      futures.add(
          limiter.schedule(
              service,
              () ->
                  service.submit(
                      () -> {
                        try {
                          assertTrue(jobsRunning.incrementAndGet() <= 2);
                          jobStarted.release();
                          assertTrue(jobWaiting.tryAcquire(2, TimeUnit.SECONDS));
                          jobFinished.release();
                          jobsRunning.decrementAndGet();
                        } catch (InterruptedException e) {
                          throw new RuntimeException();
                        }
                      })));
    }

    // Two jobs should start.
    assertTrue(jobStarted.tryAcquire(50, TimeUnit.MILLISECONDS));
    assertTrue(jobStarted.tryAcquire(50, TimeUnit.MILLISECONDS));

    assertEquals(2, jobsRunning.get());

    for (int i = 0; i < 10; i++) {
      service.submit(() -> {}).get();
    }

    // Third job shouldn't start.
    assertFalse(jobStarted.tryAcquire(50, TimeUnit.MILLISECONDS));

    // Release a waiting job and a new one should start.
    jobWaiting.release();
    assertTrue(jobFinished.tryAcquire(2, TimeUnit.SECONDS));
    assertTrue(jobStarted.tryAcquire(2, TimeUnit.SECONDS));

    // But not a fourth.
    assertFalse(jobStarted.tryAcquire(50, TimeUnit.MILLISECONDS));

    // Let all the jobs but one finish.
    jobWaiting.release(8);
    assertTrue(jobFinished.tryAcquire(8, 2, TimeUnit.SECONDS));

    jobStarted.tryAcquire(7, 2, TimeUnit.SECONDS);
    assertEquals(1, jobsRunning.get());
    // Let the final job finish.
    jobWaiting.release();
    assertTrue(jobFinished.tryAcquire(2, TimeUnit.SECONDS));

    // Ensure all the futures are fulfilled.
    for (ListenableFuture<?> future : futures) {
      future.get(1, TimeUnit.SECONDS);
    }
  }

  @Test(expected = Exception.class)
  public void supplierExceptionIsPropagated() throws Exception {
    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1));

    JobLimiter limiter = new JobLimiter(2);

    limiter
        .schedule(
            service,
            () -> {
              throw new Exception("got ya!");
            })
        .get(2, TimeUnit.SECONDS);
  }
}
