/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.event;

import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.eventbus.AsyncEventBus;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around guava event bus.
 */
public class BuckEventBus {
  private static Supplier<Long> DEFAULT_THREAD_ID_SUPPLIER = new Supplier<Long>() {
    @Override
    public Long get() {
      return Thread.currentThread().getId();
    }
  };

  /** This is the {@link Executor} that powers the {@link AsyncEventBus}. */
  private final ThreadPoolExecutor executor;

  private final AsyncEventBus eventBus;
  private final Clock clock;
  private final Supplier<Long> threadIdSupplier;

  public BuckEventBus() {
    this(new DefaultClock(), getDefaultThreadIdSupplier());
  }

  public BuckEventBus(Clock clock, Supplier<Long> threadIdSupplier) {
    // We instantiate this.executor using the implementation of Executors.newSingleThreadExecutor().
    // The problem with newSingleThreadExecutor is that it returns an ExecutorService; however, we
    // need to use the getQueue() method that is available in ThreadPoolExecutor, but is not
    // exposed via the ThreadPoolExecutor interface.
    this.executor = new ThreadPoolExecutor(/* corePoolSize */ 1,
        /* maximumPoolSize */ 1,
        /* keepAliveTime */ 0L,
        TimeUnit.MILLISECONDS,
        /* workQueue */ new LinkedBlockingQueue<Runnable>(),
        new ThreadPoolExecutor.DiscardPolicy());
    this.eventBus = new AsyncEventBus("buck-build-events", executor);
    this.clock = Preconditions.checkNotNull(clock);
    this.threadIdSupplier = Preconditions.checkNotNull(threadIdSupplier);
  }

  public static Supplier<Long> getDefaultThreadIdSupplier() {
    return DEFAULT_THREAD_ID_SUPPLIER;
  }

  public void post(BuckEvent event) {
    event.configure(clock.currentTimeMillis(), clock.nanoTime(), threadIdSupplier.get());
    eventBus.post(event);
  }

  public void register(Object object) {
    eventBus.register(object);
  }

  /**
   * Execute {@link Runnable}s in the underlying {@link ExecutorService} until there aren't any
   * left to execute. This differs from {@link ExecutorService#shutdown()} because this method
   * still allows new {@link Runnable}s to be submitted to the {@link ExecutorService} while this
   * method is running.
   */
  @VisibleForTesting
  public void flushForTesting() {
    while (!executor.getQueue().isEmpty()) {
      try {
        Thread.sleep(50L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Posts the specified event directly to the underlying {@link AsyncEventBus}.
   * <p>
   * TODO(royw): Try to eliminate this method.
   */
  @VisibleForTesting
  public void postDirectlyToAsyncEventBusForTesting(BuckEvent event) {
    eventBus.post(event);
    flushForTesting();
  }

  /**
   * @return the underlying {@link ExecutorService} that is being used to process the events posted
   *     to this event bus.
   */
  public ExecutorService getExecutorService() {
    return executor;
  }

  public Clock getClock() {
    return clock;
  }

  public Supplier<Long> getThreadIdSupplier() {
    return threadIdSupplier;
  }
}
