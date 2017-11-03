// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import java.util.Iterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ThreadUtils {

  public static void awaitFutures(Iterable<? extends Future<?>> futures)
      throws ExecutionException {
    Iterator<? extends Future<?>> it = futures.iterator();
    try {
      while (it.hasNext()) {
        it.next().get();
      }
    } catch (InterruptedException e) {
      throw new RuntimeException("Interrupted while waiting for future.", e);
    } finally {
      // In case we get interrupted or one of the threads throws an exception, still wait for all
      // further work to make sure synchronization guarantees are met. Calling cancel unfortunately
      // does not guarantee that the task at hand actually terminates before cancel returns.
      while (it.hasNext()) {
        try {
          it.next().get();
        } catch (Throwable t) {
          // Ignore any new Exception.
        }
      }
    }
  }

  public static ExecutorService getExecutorService(int threads) {
    // Don't use Executors.newSingleThreadExecutor() when threads == 1, see b/67338394.
    return Executors.newWorkStealingPool(threads);
  }

  public static ExecutorService getExecutorService(InternalOptions options) {
    int threads = options.numberOfThreads;
    if (threads == options.NOT_SPECIFIED) {
      // This heuristic is based on measurements on a 32 core (hyper-threaded) machine.
      threads = Integer.min(Runtime.getRuntime().availableProcessors(), 16) / 2;
    }
    return getExecutorService(threads);
  }
}
