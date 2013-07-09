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
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MoreBuckExecutors {

  /** Utility class, should not be instantiated. */
  private MoreBuckExecutors() {}


  /**
   * Equivalent to {@link java.util.concurrent.Executors#newCachedThreadPool()}, but it allows us to
   * specify the {@link RejectedExecutionHandler}.
   */
  public static ExecutorService newCachedThreadPool(RejectedExecutionHandler handler) {
    return new ThreadPoolExecutor(/* corePoolSize */ 0,
        /* maximumPoolSize */ Integer.MAX_VALUE,
        /* keepAliveTime */ 60L,
        /* unit */ TimeUnit.SECONDS,
        /* workQueue */ new SynchronousQueue<Runnable>(),
        /* handler */ handler);
  }
}
