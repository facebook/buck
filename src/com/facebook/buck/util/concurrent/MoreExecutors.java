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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MoreExecutors {

  private MoreExecutors() {
    // Utility class.
  }

  /**
   * Creates a single threaded executor that silently discards rejected tasks. The problem with
   * {@link java.util.concurrent.Executors#newSingleThreadExecutor()) is that it does not let us
   * specify a RejectedExecutionHandler, which we need to ensure that garbage is not spewed to the
   * user's console if the build fails.
   *
   * @return A single-threaded executor that silently discards rejected tasks.
   */
  public static ExecutorService newSingleThreadExecutor() {
    return new ThreadPoolExecutor(
        /* corePoolSize */ 1,
        /* maximumPoolSize */ 1,
        /* keepAliveTime */ 0L, TimeUnit.MILLISECONDS,
        /* workQueue */ new LinkedBlockingQueue<Runnable>(),
        /* handler */ new ThreadPoolExecutor.DiscardPolicy());
  }

}
