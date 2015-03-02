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

package com.facebook.buck.util.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MoreExecutors {

  private MoreExecutors() {
    // Utility class.
  }

  /**
   * A ThreadFactory which gives each thread a meaningful and distinct name.
   * ThreadFactoryBuilder is not used to avoid a dependency on Guava in the junit target.
   */
  public static class NamedThreadFactory implements ThreadFactory {

    private final AtomicInteger threadCount = new AtomicInteger(0);
    private final String threadName;

    public NamedThreadFactory(String threadName) {
      this.threadName = threadName;
    }

    @Override
    public Thread newThread(Runnable r) {
      Thread newThread = Executors.defaultThreadFactory().newThread(r);
      newThread.setName(String.format(threadName + "-%d", threadCount.incrementAndGet()));
      return newThread;
    }
  }

  /**
   * Creates a single threaded executor that silently discards rejected tasks. The problem with
   * {@link java.util.concurrent.Executors#newSingleThreadExecutor()} is that it does not let us
   * specify a RejectedExecutionHandler, which we need to ensure that garbage is not spewed to the
   * user's console if the build fails.
   *
   * @return A single-threaded executor that silently discards rejected tasks.
   * @param threadName a thread name prefix used to easily identify threads when debugging.
   */
  public static ExecutorService newSingleThreadExecutor(final String threadName) {
    return newSingleThreadExecutor(new NamedThreadFactory(threadName));
  }

  public static ExecutorService newSingleThreadExecutor(ThreadFactory threadFactory) {
    return new ThreadPoolExecutor(
        /* corePoolSize */ 1,
        /* maximumPoolSize */ 1,
        /* keepAliveTime */ 0L, TimeUnit.MILLISECONDS,
        /* workQueue */ new LinkedBlockingQueue<Runnable>(),
        /* threadFactory */ threadFactory,
        /* handler */ new ThreadPoolExecutor.DiscardPolicy());
  }

  /**
   * Creates a multi-threaded executor with meaningfully named threads.
   * @param threadName a thread name prefix used to easily identify threads when debugging.
   * @param count the number of threads that should be created in the pool.
   * @return A multi-threaded executor.
   */
  public static ExecutorService newMultiThreadExecutor(final String threadName, int count) {
    return newMultiThreadExecutor(new NamedThreadFactory(threadName), count);
  }

  public static ExecutorService newMultiThreadExecutor(ThreadFactory threadFactory, int count) {
    return new ThreadPoolExecutor(
        /* corePoolSize */ count,
        /* maximumPoolSize */ count,
        /* keepAliveTime */ 0L, TimeUnit.MILLISECONDS,
        /* workQueue */ new LinkedBlockingQueue<Runnable>(),
        /* threadFactory */ threadFactory,
        /* handler */ new ThreadPoolExecutor.DiscardPolicy());
  }

  /**
   * Shutdown {@code service} and wait for all it's tasks to terminate.  In the event of
   * {@link InterruptedException}, propagate the interrupt to all tasks, wait for them to
   * finish, then re-throw the exception.
   */
  public static boolean shutdown(
      ExecutorService service,
      long timeout,
      TimeUnit unit) throws InterruptedException {
    service.shutdown();
    try {
      return service.awaitTermination(timeout, unit);
    } catch (InterruptedException e) {
      service.shutdownNow();
      service.awaitTermination(timeout, unit);
      throw e;
    }
  }

  public static void shutdown(ExecutorService service) throws InterruptedException {
    shutdown(service, Long.MAX_VALUE, TimeUnit.DAYS);
  }

  /**
   * Cancel the processing being carried out by the given service and waits for the processing to
   * complete. If processing has still not terminated the method throws the given exception.
   */
  public static void shutdownOrThrow(
      ExecutorService service,
      long timeout,
      TimeUnit unit,
      RuntimeException exception) {
    boolean terminated = false;
    service.shutdown();
    try {
      terminated = service.awaitTermination(timeout, unit);
    } catch (InterruptedException e) {
      terminated = false;
    } finally {
      if (!terminated) {
        service.shutdownNow();
        try {
          terminated = service.awaitTermination(timeout, unit);
        } catch (InterruptedException e) {
          terminated = false;
        }
      }
    }
    if (!terminated) {
      throw exception;
    }
  }
}
