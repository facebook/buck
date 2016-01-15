/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.android.common.annotations.Nullable;

import java.io.InterruptedIOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * A wrapper around a {@link Thread} which implements the {@link AutoCloseable} interface to be
 * used in try-resource blocks and which also supports forwarding exceptions to the managing
 * thread.
 */
public abstract class ManagedRunnable implements AutoCloseable {

  private final Runnable runnable;
  private final ExecutorService threadPool;
  private Future<?> runnableFuture;

  @Nullable
  private Exception exception;

  public ManagedRunnable(ExecutorService threadPool) {
    this.threadPool = threadPool;
    this.runnable =
        new Runnable() {
          @Override
          @SuppressWarnings("PMD.EmptyCatchBlock")
          public void run() {
            try {
              ManagedRunnable.this.run();
            } catch (InterruptedIOException | InterruptedException e) {
            } catch (Exception e) {
              exception = e;
            }
          }
        };
  }

  public void start() {
    this.runnableFuture = this.threadPool.submit(this.runnable);
  }

  protected abstract void run() throws Exception;

  /**
   * Wait for the backing thread to terminate.
   */
  public void waitFor() throws InterruptedException, ExecutionException {

    // Wait for the thread to finish.
    this.runnableFuture.get();
  }

  @Override
  public void close() throws Exception {

    // Stop the thread and wait for it to finish.
    this.runnableFuture.cancel(true);
    this.runnableFuture.get();

    // If the thread died with an exception, forward it to the creating thread.
    if (exception != null) {
      throw exception;
    }
  }

}
