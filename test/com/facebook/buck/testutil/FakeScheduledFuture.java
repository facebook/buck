/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.testutil;

import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class FakeScheduledFuture<V> implements ScheduledFuture<V>, Runnable {
  private final Callable<V> callable;
  private final Object lock = new Object();

  private volatile boolean hasRun = false;
  private volatile boolean isCancelled = false;
  private Exception callableException = null;
  private V result = null;

  public FakeScheduledFuture(Callable<V> callable) {
    this.callable = callable;
  }

  @Override
  public void run() {
    try {
      result = callable.call();
    } catch (Exception e) {
      callableException = e;
    } finally {
      synchronized (lock) {
        hasRun = true;
        lock.notifyAll();
      }
    }
  }

  @Override
  public long getDelay(TimeUnit unit) {
    return 0;
  }

  @Override
  public int compareTo(Delayed o) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    return isCancelled = true;
  }

  @Override
  public boolean isCancelled() {
    return isCancelled;
  }

  @Override
  public boolean isDone() {
    return hasRun;
  }

  @Override
  public V get() throws InterruptedException, ExecutionException {
    try {
      return get(0, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public V get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    long timeoutMillis = unit.toMillis(timeout);
    long start = System.currentTimeMillis();

    synchronized (lock) {
      while (!hasRun) {
        lock.wait(timeoutMillis);

        if (timeoutMillis > 0 && System.currentTimeMillis() - start >= timeoutMillis) {
          throw new TimeoutException();
        }
      }
    }

    if (callableException != null) {
      throw new ExecutionException(callableException);
    }

    return result;
  }
}
