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
import com.facebook.buck.util.concurrent.MoreExecutors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.eventbus.AsyncEventBus;

import java.io.Closeable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around guava event bus.
 */
public class BuckEventBus implements Closeable {

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
  private final String buildId;


  public BuckEventBus(Clock clock, String buildId) {
    this(clock, MoreExecutors.newSingleThreadExecutor(BuckEventBus.class.getSimpleName()), buildId);
  }

  @VisibleForTesting
  BuckEventBus(Clock clock, ExecutorService executorService, String buildId) {
    this.clock = Preconditions.checkNotNull(clock);
    this.executorService = Preconditions.checkNotNull(executorService);
    this.eventBus = new AsyncEventBus("buck-build-events", executorService);
    this.threadIdSupplier = DEFAULT_THREAD_ID_SUPPLIER;
    this.buildId = Preconditions.checkNotNull(buildId);
  }

  public void post(BuckEvent event) {
    event.configure(clock.currentTimeMillis(), clock.nanoTime(), threadIdSupplier.get(), buildId);
    eventBus.post(event);
  }

  public void register(Object object) {
    eventBus.register(object);
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

  /**
   * An id that every event posted to this event bus will share. For long-running processes, like
   * the daemon, the build id makes it possible to distinguish when events come from different
   * invocations of Buck.
   * <p>
   * In practice, this should be a short string, because it may be sent over the wire frequently.
   */
  public String getBuildId() {
    return buildId;
  }

  /**
   * Attempt to release resources (specifically, threads) held by this bus.
   * This is meant for cleaning up after an exception.  {@link #shutdown()} should be used under
   * normal circumstances to wait for all jobs to complete.
   */
  @Override
  public void close() {
    executorService.shutdown();
  }

  /**
   * Shutdown the bus and wait a reasonable interval for it to terminate.
   *
   * @return {@code true} iff it successfully terminated.
   */
  public boolean shutdown(long timeout, TimeUnit unit) {
    executorService.shutdown();
    try {
      // Give the eventBus some time to finish dispatching all events and inform the caller
      // if they don't finish during that time.
      return executorService.awaitTermination(timeout, unit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  public String executorSummary() {
    return executorService.toString();
  }
}
