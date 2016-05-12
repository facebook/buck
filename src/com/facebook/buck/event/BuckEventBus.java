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

import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.Pair;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around guava event bus.
 */
public class BuckEventBus implements Closeable {

  private static final Logger LOG = Logger.get(BuckEventBus.class);

  public static final int DEFAULT_SHUTDOWN_TIMEOUT_MS = 15000;

  private static final Supplier<Long> DEFAULT_THREAD_ID_SUPPLIER = new Supplier<Long>() {
    @Override
    public Long get() {
      return Thread.currentThread().getId();
    }
  };

  private final Clock clock;
  private final boolean async;
  private final ConcurrentLinkedDeque<Pair<ExecutorService, EventBus>> executorsAndBuses;
  private final Supplier<Long> threadIdSupplier;
  private final BuildId buildId;
  private final int shutdownTimeoutMillis;

  public BuckEventBus(Clock clock, BuildId buildId) {
    this(clock, true, buildId, DEFAULT_SHUTDOWN_TIMEOUT_MS);
  }

  @VisibleForTesting
  public BuckEventBus(
      Clock clock,
      final boolean async,
      BuildId buildId,
      int shutdownTimeoutMillis) {
    this.clock = clock;
    this.async = async;
    this.executorsAndBuses = new ConcurrentLinkedDeque<>();
    this.threadIdSupplier = DEFAULT_THREAD_ID_SUPPLIER;
    this.buildId = buildId;
    this.shutdownTimeoutMillis = shutdownTimeoutMillis;
  }

  private void dispatch(final BuckEvent event) {
    for (final Pair<ExecutorService, EventBus> executorAndBus : executorsAndBuses) {
      executorAndBus.getFirst().submit(
          new Runnable() {
            @Override
            public void run() {
              executorAndBus.getSecond().post(event);
            }
          }
      );
    }
  }

  public void post(BuckEvent event) {
    timestamp(event);
    dispatch(event);
  }

  public void logVerboseAndPost(Logger logger, BuckEvent event) {
    logger.verbose("%s", event);
    post(event);
  }

  public void logDebugAndPost(Logger logger, BuckEvent event) {
    logger.debug("%s", event);
    post(event);
  }

  /**
   * Post event to the EventBus using the timestamp given by atTime.
   */
  public void post(BuckEvent event, BuckEvent atTime) {
    event.configure(atTime.getTimestamp(), atTime.getNanoTime(), threadIdSupplier.get(), buildId);
    dispatch(event);
  }

  public void register(Object object) {
    EventBus eventBus = new EventBus("buck-build-events");
    eventBus.register(object);
    executorsAndBuses.add(new Pair<ExecutorService, EventBus>(
        async ?
            MostExecutors.newSingleThreadExecutor(
                new CommandThreadFactory(BuckEventBus.class.getSimpleName())) :
            MoreExecutors.newDirectExecutorService(),
        eventBus));
  }

  @VisibleForTesting
  public void postWithoutConfiguring(BuckEvent event) {
    Preconditions.checkState(event.isConfigured());
    for (final Pair<ExecutorService, EventBus> executorAndBus : executorsAndBuses) {
      executorAndBus.getSecond().post(event);
    }
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
  public BuildId getBuildId() {
    return buildId;
  }

  /**
   * {@link ExecutorService#awaitTermination(long, java.util.concurrent.TimeUnit)} is called
   * to wait for events which have been posted, but which have been queued by the
   * {@link EventBus}, to be delivered. This allows listeners to record or report as much
   * information as possible. This aids debugging when close is called during exception processing.
   */
  @Override
  public void close() throws IOException {
    try {
      ImmutableSet.Builder<ExecutorService> leftoverExecutorsBuilder = ImmutableSet.builder();
      for (Pair<ExecutorService, EventBus> executorAndBus : executorsAndBuses) {
        ExecutorService executorService = executorAndBus.getFirst();
        executorService.shutdown();
        if (!executorService.awaitTermination(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
          leftoverExecutorsBuilder.add(executorService);
        }
      }
      ImmutableSet<ExecutorService> leftoverExecutors = leftoverExecutorsBuilder.build();
      if (!leftoverExecutors.isEmpty()) {
        ImmutableSet<String> message = ImmutableSet.of(
            "The BuckEventBus failed to shut down within the standard timeout.",
            "Your build might have succeeded, but some messages were probably lost.",
            "Here's some debugging information:");
        LOG.warn(
            Joiner.on(System.lineSeparator())
                .join(
                    Iterables.concat(
                        message,
                        Iterables.transform(leftoverExecutors, Functions.toStringFunction()))));
        for (ExecutorService leftoverExecutor : leftoverExecutors) {
          leftoverExecutor.shutdownNow();
        }
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
