/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.ListeningMultiSemaphore;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.google.common.base.Joiner;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Encapsulates a group of threads which operate a {@link ListeningExecutorService}, providing an
 * {@link AutoCloseable} interface which waits for and kills the threads on close.
 */
@SuppressWarnings("PMD.AvoidThreadGroup")
public class CommandThreadManager implements AutoCloseable {

  // Shutdown timeout should be longer than the maximum runtime of a single step as some
  // steps ignore interruption. The longest ever recorded step execution time as of
  // 2014-07-08 was ~6 minutes, so a timeout of 10 minutes should be sufficient.
  private static final long DEFAULT_SHUTDOWN_TIMEOUT = 30;
  private static final TimeUnit DEFAULT_SHUTDOWN_TIMEOUT_UNIT = TimeUnit.MINUTES;

  // Use a thread group purely as a debugging aid to help enumerate the threads we should
  // print an error message for.
  private final ThreadGroup threadGroup;

  private final WeightedListeningExecutorService executor;

  // How long to wait for the executor service to shutdown.
  private final long shutdownTimeout;
  private final TimeUnit shutdownTimeoutUnit;

  public CommandThreadManager(
      String name,
      ListeningMultiSemaphore semaphore,
      ResourceAmounts defaultAmounts,
      int managedThreadCount,
      long shutdownTimeout,
      TimeUnit shutdownTimeoutUnit) {
    this.threadGroup = new ThreadGroup(name);

    // TODO(cjhopman): This should probably take a Function<ThreadGroup, ListeningExecutorService>
    // so that all that this class is really in charge of is properly shutting it down and providing
    // useful information when that fails.
    this.executor =
        new WeightedListeningExecutorService(
            semaphore,
            defaultAmounts,
            listeningDecorator(
                MostExecutors.newMultiThreadExecutor(
                    new ThreadFactoryBuilder()
                        .setNameFormat(name + "-%d")
                        .setThreadFactory(new CommandThreadFactory(r -> new Thread(threadGroup, r)))
                        .build(),
                    managedThreadCount)));
    this.shutdownTimeout = shutdownTimeout;
    this.shutdownTimeoutUnit = shutdownTimeoutUnit;
  }

  public CommandThreadManager(
      String name,
      ListeningMultiSemaphore semaphore,
      ResourceAmounts defaultAmounts,
      int managedThreadCount) {
    this(
        name,
        semaphore,
        defaultAmounts,
        managedThreadCount,
        DEFAULT_SHUTDOWN_TIMEOUT,
        DEFAULT_SHUTDOWN_TIMEOUT_UNIT);
  }

  public CommandThreadManager(
      String name,
      ConcurrencyLimit concurrencyLimit,
      long shutdownTimeout,
      TimeUnit shutdownTimeoutUnit) {
    this(
        name,
        new ListeningMultiSemaphore(
            concurrencyLimit.maximumAmounts, concurrencyLimit.resourceAllocationFairness),
        concurrencyLimit.defaultAmounts,
        concurrencyLimit.managedThreadCount,
        shutdownTimeout,
        shutdownTimeoutUnit);
  }

  public CommandThreadManager(String name, ConcurrencyLimit concurrencyLimit) {
    this(name, concurrencyLimit, DEFAULT_SHUTDOWN_TIMEOUT, DEFAULT_SHUTDOWN_TIMEOUT_UNIT);
  }

  public WeightedListeningExecutorService getExecutor() {
    return executor;
  }

  @Override
  public void close() throws InterruptedException {
    boolean shutdown = MostExecutors.shutdown(executor, shutdownTimeout, shutdownTimeoutUnit);

    // If the shutdown failed, print the stacks for all the blocked threads.
    if (!shutdown) {
      List<String> parts = new ArrayList<>();

      parts.add(String.format("Shutdown timed out for thread pool %s", threadGroup.getName()));

      Thread[] threads = new Thread[threadGroup.activeCount()];
      threadGroup.enumerate(threads);
      for (Thread thread : threads) {
        if (thread.getState() != Thread.State.TERMINATED) {
          parts.add("  Thread " + thread.getName() + ":");
          for (StackTraceElement element : thread.getStackTrace()) {
            parts.add(String.format("    %s", element.toString()));
          }
        }
      }

      throw new RuntimeException(Joiner.on("\n").join(parts));
    }
  }
}
