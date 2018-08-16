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

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.concurrent.CommandThreadFactory;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.timing.Clock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Thin wrapper around guava event bus. */
public class DefaultBuckEventBus implements com.facebook.buck.event.BuckEventBus {

  private static final Logger LOG = Logger.get(BuckEventBus.class);

  public static final int DEFAULT_SHUTDOWN_TIMEOUT_MS = 15000;

  private static final Supplier<Long> DEFAULT_THREAD_ID_SUPPLIER =
      () -> Thread.currentThread().getId();

  private final Clock clock;
  private final ExecutorService executorService;
  private final EventBus eventBus;
  private final Supplier<Long> threadIdSupplier;
  private final BuildId buildId;
  private final int shutdownTimeoutMillis;

  // synchronization variables to ensure proper shutdown
  private volatile int activeTasks = 0;
  private final Object lock = new Object();

  public DefaultBuckEventBus(Clock clock, BuildId buildId) {
    this(clock, true, buildId, DEFAULT_SHUTDOWN_TIMEOUT_MS);
  }

  @VisibleForTesting
  public DefaultBuckEventBus(
      Clock clock, boolean async, BuildId buildId, int shutdownTimeoutMillis) {
    this.clock = clock;
    this.executorService =
        async
            ? MostExecutors.newSingleThreadExecutor(
                new CommandThreadFactory(
                    BuckEventBus.class.getSimpleName(),
                    GlobalStateManager.singleton().getThreadToCommandRegister()))
            : MoreExecutors.newDirectExecutorService();
    this.eventBus = new EventBus("buck-build-events");
    this.threadIdSupplier = DEFAULT_THREAD_ID_SUPPLIER;
    this.buildId = buildId;
    this.shutdownTimeoutMillis = shutdownTimeoutMillis;
  }

  private void dispatch(BuckEvent event) {
    // keep track the number of active tasks so we can do proper shutdown
    synchronized (lock) {
      activeTasks++;
    }

    executorService.submit(
        () -> {
          try {
            eventBus.post(event);
          } finally {
            // event bus should not throw but just in case wrap with try-finally
            synchronized (lock) {
              activeTasks--;
              // notify about task completion; shutdown may wait for it
              lock.notifyAll();
            }
          }
        });
  }

  @Override
  public void post(BuckEvent event) {
    timestamp(event);
    dispatch(event);
  }

  /** Post event to the EventBus using the timestamp given by atTime. */
  @Override
  public void post(BuckEvent event, BuckEvent atTime) {
    event.configure(
        atTime.getTimestamp(),
        atTime.getNanoTime(),
        atTime.getThreadUserNanoTime(),
        threadIdSupplier.get(),
        buildId);
    dispatch(event);
  }

  @Override
  public void register(Object object) {
    eventBus.register(object);
  }

  @Override
  public void unregister(Object object) {
    eventBus.unregister(object);
  }

  @Override
  public void postWithoutConfiguring(BuckEvent event) {
    Preconditions.checkState(event.isConfigured());
    dispatch(event);
  }

  @VisibleForTesting
  Clock getClock() {
    return clock;
  }

  /**
   * An id that every event posted to this event bus will share. For long-running processes, like
   * the daemon, the build id makes it possible to distinguish when events come from different
   * invocations of Buck.
   *
   * <p>In practice, this should be a short string, because it may be sent over the wire frequently.
   */
  @Override
  public BuildId getBuildId() {
    return buildId;
  }

  /**
   * {@link ExecutorService#awaitTermination(long, java.util.concurrent.TimeUnit)} is called to wait
   * for events which have been posted, but which have been queued by the {@link EventBus}, to be
   * delivered. This allows listeners to record or report as much information as possible. This aids
   * debugging when close is called during exception processing.
   */
  @Override
  public void close() throws IOException {
    long timeoutTime = System.currentTimeMillis() + shutdownTimeoutMillis;

    // it might have happened that executor service is still processing a task which in turn may
    // post new tasks to the executor, in this case if we shutdown executor they won't be processed
    // so first wait for all currently running tasks and their descendants to finish
    // ideally it should be done inside executorService but it only provides shutdown() method
    // which immediately stops accepting new tasks, that's why we have some wrapper on top of it
    waitEvents(shutdownTimeoutMillis);

    executorService.shutdown();
    try {
      long waitTime = timeoutTime - System.currentTimeMillis();
      if (waitTime <= 0 || !executorService.awaitTermination(waitTime, TimeUnit.MILLISECONDS)) {
        LOG.warn(
            Joiner.on(System.lineSeparator())
                .join(
                    "The BuckEventBus failed to shut down within the standard timeout.",
                    "Your build might have succeeded, but some messages were probably lost.",
                    "Here's some debugging information:",
                    executorService.toString()));
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      Threads.interruptCurrentThread();
    }
  }

  @Override
  public boolean waitEvents(long timeout) {
    long startWaitTime = System.nanoTime();
    synchronized (lock) {
      while (activeTasks > 0) {

        long waitTime = 0;
        if (timeout > 0) {
          waitTime = timeout - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startWaitTime);
          if (waitTime <= 0) {
            return false;
          }
        }

        try {
          lock.wait(waitTime);
        } catch (InterruptedException e) {
          Threads.interruptCurrentThread();
          return activeTasks == 0;
        }
      }
    }
    return true;
  }

  /**
   * Timestamp event. A timestamped event cannot subsequently being posted and is useful only to
   * pass its timestamp on to another posted event.
   */
  @Override
  public void timestamp(BuckEvent event) {
    Long threadId = threadIdSupplier.get();
    event.configure(
        clock.currentTimeMillis(),
        clock.nanoTime(),
        clock.threadUserNanoTime(threadId),
        threadId,
        buildId);
  }
}
