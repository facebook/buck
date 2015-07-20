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

/**
 * A wrapper around a {@link Thread} which implements the {@link AutoCloseable} interface to be
 * used in try-resource blocks and which also supports forwarding exceptions to the managing
 * thread.
 */
public abstract class ManagedThread implements AutoCloseable {

  private final Thread thread;

  @Nullable
  private Exception exception;

  public ManagedThread() {
    this.thread = new Thread(
        new Runnable() {
          @Override
          @SuppressWarnings("PMD.EmptyCatchBlock")
          public void run() {
            try {
              ManagedThread.this.run();
            } catch (InterruptedIOException | InterruptedException e) {
            } catch (Exception e) {
              exception = e;
            }
          }
        });
  }

  public void start() {
    this.thread.start();
  }

  protected abstract void run() throws Exception;

  /**
   * Wait for the backing thread to terminate.
   */
  public void waitFor() throws InterruptedException {

    // Wait for the thread to finish.
    this.thread.join();
  }

  @Override
  public void close() throws Exception {

    // Stop the thread and wait for it to finish.
    this.thread.interrupt();
    this.thread.join();

    // If the thread died with an exception, forward it to the creating thread.
    if (exception != null) {
      throw exception;
    }
  }

}
