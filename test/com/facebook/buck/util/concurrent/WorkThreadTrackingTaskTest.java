/*
 * Copyright 2019-present Facebook, Inc.
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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import org.junit.Test;

public class WorkThreadTrackingTaskTest {
  @Test
  public void threadIsTrackedWhenComputing() throws InterruptedException {
    Semaphore assertBlocker = new Semaphore(0);
    Semaphore blocker = new Semaphore(0);
    WorkThreadTrackingTask<Object> task =
        new WorkThreadTrackingTask<>(
            () -> {
              try {
                assertBlocker.release();
                blocker.acquire();
              } catch (InterruptedException e) {
              }
              return null;
            });

    Thread thread =
        new Thread(
            () -> {
              task.compute();
            });
    thread.start();

    assertBlocker.acquire();
    assertEquals(thread, task.workThread);
    thread.interrupt();
  }

  @Test
  public void completeCompletesTask() throws ExecutionException, InterruptedException {
    WorkThreadTrackingTask<Object> workThreadTrackingTask =
        new WorkThreadTrackingTask<>(() -> null);
    assertFalse(workThreadTrackingTask.isDone());
    Object completed = new Object();
    workThreadTrackingTask.complete(completed);
    assertTrue(workThreadTrackingTask.isDone());
    assertSame(completed, workThreadTrackingTask.get());
  }

  @Test
  public void completeOnlyOnceWithMultipleThread() throws InterruptedException {
    LongAdder invocationCount = new LongAdder();
    WorkThreadTrackingTask<Object> workThreadTrackingTask =
        new WorkThreadTrackingTask<>(
            () -> {
              invocationCount.increment();
              return null;
            });

    Thread t = new Thread(() -> workThreadTrackingTask.externalCompute());
    workThreadTrackingTask.externalCompute();
    t.join();

    assertEquals(1, invocationCount.intValue());
  }
}
