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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MostExecutors {

  private MostExecutors() {
    // Utility class.
  }

  /**
   * A ThreadFactory which gives each thread a meaningful and distinct name. ThreadFactoryBuilder is
   * not used to avoid a dependency on Guava in the junit target.
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
  public static ExecutorService newSingleThreadExecutor(String threadName) {
    return newSingleThreadExecutor(new NamedThreadFactory(threadName));
  }

  public static ExecutorService newSingleThreadExecutor(ThreadFactory threadFactory) {
    return new ThreadPoolExecutor(
        /* corePoolSize */ 1,
        /* maximumPoolSize */ 1,
        /* keepAliveTime */ 0L,
        TimeUnit.MILLISECONDS,
        /* workQueue */ new LinkedBlockingQueue<Runnable>(),
        /* threadFactory */ threadFactory,
        /* handler */ new ThreadPoolExecutor.DiscardPolicy());
  }

  /**
   * Creates a multi-threaded executor with meaningfully named threads.
   *
   * @param threadName a thread name prefix used to easily identify threads when debugging.
   * @param count the number of threads that should be created in the pool.
   * @return A multi-threaded executor.
   */
  public static ExecutorService newMultiThreadExecutor(String threadName, int count) {
    return newMultiThreadExecutor(new NamedThreadFactory(threadName), count);
  }

  public static ExecutorService newMultiThreadExecutor(ThreadFactory threadFactory, int count) {
    return new ThreadPoolExecutor(
        /* corePoolSize */ count,
        /* maximumPoolSize */ count,
        /* keepAliveTime */ 0L,
        TimeUnit.MILLISECONDS,
        /* workQueue */ new LinkedBlockingQueue<Runnable>(),
        /* threadFactory */ threadFactory,
        /* handler */ new ThreadPoolExecutor.DiscardPolicy());
  }

  /**
   * Shutdown {@code service} and wait for all it's tasks to terminate. In the event of {@link
   * InterruptedException}, propagate the interrupt to all tasks, wait for them to finish, then
   * re-throw the exception.
   */
  public static boolean shutdown(ExecutorService service, long timeout, TimeUnit unit)
      throws InterruptedException {
    service.shutdown();
    try {
      return service.awaitTermination(timeout, unit);
    } catch (InterruptedException e) {
      service.shutdownNow();
      service.awaitTermination(timeout, unit);
      throw e;
    }
  }

  /**
   * Cancel the processing being carried out by the given service and waits for the processing to
   * complete. If processing has still not terminated the method throws the given exception.
   */
  public static void shutdownOrThrow(
      ExecutorService service, long timeout, TimeUnit unit, RuntimeException exception) {
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

  /**
   * Construct a ForkJoinPool with a stricter thread limit.
   *
   * <p>ForkJoinPool by default will create a new thread to handle pending work whenever an existing
   * thread becomes blocked on a task and cannot work steal. In cases when many tasks would block on
   * a slow running dependency, it can trigger thread creation for all those tasks.
   *
   * <p>Note that limiting the maximum threads will impact the ability for ManagedBlockers to cause
   * the pool to create new worker threads, leading to potential deadlock if many ManagedBlockers
   * are used.
   */
  public static ForkJoinPool forkJoinPoolWithThreadLimit(int parallelism, int spares) {
    AtomicInteger activeThreads = new AtomicInteger(0);
    return new ForkJoinPool(
        parallelism,
        pool -> {
          if (activeThreads.get() > parallelism + spares) {
            return null;
          }
          return new ForkJoinWorkerThread(pool) {
            @Override
            protected void onStart() {
              super.onStart();
              activeThreads.incrementAndGet();
            }

            @Override
            protected void onTermination(Throwable exception) {
              activeThreads.decrementAndGet();
              super.onTermination(exception);
            }
          };
        },
        /* handler */ null,
        /* asyncMode */ false);
  }
}
