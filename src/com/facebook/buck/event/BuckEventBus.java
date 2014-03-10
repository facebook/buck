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
import com.facebook.buck.util.ShutdownException;
import com.facebook.buck.util.concurrent.MoreExecutors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.eventbus.AsyncEventBus;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around guava event bus.
 */
public class BuckEventBus implements Closeable {

  public static final int DEFAULT_SHUTDOWN_TIMEOUT_MS = 15000;

  private static final Supplier<Long> DEFAULT_THREAD_ID_SUPPLIER = new Supplier<Long>() {
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
  private final int shutdownTimeoutMillis;

  public BuckEventBus(Clock clock, String buildId) {
    this(clock,
        MoreExecutors.newSingleThreadExecutor(BuckEventBus.class.getSimpleName()),
        buildId,
        DEFAULT_SHUTDOWN_TIMEOUT_MS);
  }

  @VisibleForTesting
  BuckEventBus(Clock clock,
               ExecutorService executorService,
               String buildId,
               int shutdownTimeoutMillis) {
    this.clock = Preconditions.checkNotNull(clock);
    this.executorService = Preconditions.checkNotNull(executorService);
    this.eventBus = new AsyncEventBus("buck-build-events", executorService);
    this.threadIdSupplier = DEFAULT_THREAD_ID_SUPPLIER;
    this.buildId = Preconditions.checkNotNull(buildId);
    this.shutdownTimeoutMillis = shutdownTimeoutMillis;
  }

  public void post(BuckEvent event) {
    timestamp(event);
    eventBus.post(event);
  }

  /**
   * Post event to the EventBus using the timestamp given by atTime.
   */
  public void post(BuckEvent event, BuckEvent atTime) {
    event.configure(atTime.getTimestamp(), atTime.getNanoTime(), threadIdSupplier.get(), buildId);
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
   * {@link ExecutorService#awaitTermination(long, java.util.concurrent.TimeUnit)} is called
   * to wait for events which have been posted, but which have been queued by the
   * {@link AsyncEventBus}, to be delivered. This allows listeners to record or report as much
   * information as possible. This aids debugging when close is called during exception processing.
   */
  @Override
  public void close() throws IOException {
    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
        throw new ShutdownException(
            Joiner.on(System.lineSeparator()).join(
                "The BuckEventBus failed to shut down within the standard timeout.",
                "Your build might have succeeded, but some messages were probably lost.",
                "Here's some debugging information:",
                executorService.toString()));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Timestamp event. A timestamped event cannot subsequently being posted and is useful only to
   * pass its timestamp on to another posted event.
   */
  public void timestamp(BuckEvent event) {
    event.configure(clock.currentTimeMillis(), clock.nanoTime(), threadIdSupplier.get(), buildId);
  }
}
