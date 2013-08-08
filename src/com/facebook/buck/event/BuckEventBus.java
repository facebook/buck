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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.eventbus.AsyncEventBus;

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

  private final Clock clock;
  private final ExecutorService executorService;
  private final AsyncEventBus eventBus;
  private final Supplier<Long> threadIdSupplier;

  public BuckEventBus(Clock clock) {
    // We create an ExecutorService based on the implementation of
    // Executors.newSingleThreadExecutor(). The problem with Executors.newSingleThreadExecutor() is
    // that it does not let us specify a RejectedExecutionHandler, which we need to ensure that
    // garbage is not spewed to the user's console if the build fails.
    this(clock, new ThreadPoolExecutor(
        /* corePoolSize */ 1,
        /* maximumPoolSize */ 1,
        /* keepAliveTime */ 0L, TimeUnit.MILLISECONDS,
        /* workQueue */ new LinkedBlockingQueue<Runnable>(),
        /* handler */ new ThreadPoolExecutor.DiscardPolicy()));
  }

  @VisibleForTesting
  BuckEventBus(Clock clock, ExecutorService executorService) {
    this.clock = Preconditions.checkNotNull(clock);
    this.executorService = Preconditions.checkNotNull(executorService);
    this.eventBus = new AsyncEventBus("buck-build-events", executorService);
    this.threadIdSupplier = DEFAULT_THREAD_ID_SUPPLIER;
  }

  public void post(BuckEvent event) {
    event.configure(clock.currentTimeMillis(), clock.nanoTime(), threadIdSupplier.get());
    eventBus.post(event);
  }

  public void register(Object object) {
    eventBus.register(object);
  }

  public ExecutorService getExecutorService() {
    Preconditions.checkNotNull(executorService,
        "executorService must have been specified to the constructor");
    return executorService;
  }

  @VisibleForTesting
  AsyncEventBus getEventBus() {
    return eventBus;
  }

  @VisibleForTesting
  Clock getClock() {
    return clock;
  }

  @VisibleForTesting
  Supplier<Long> getThreadIdSupplier() {
    return threadIdSupplier;
  }
}
