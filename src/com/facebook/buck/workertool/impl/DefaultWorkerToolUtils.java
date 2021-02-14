/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.workertool.impl;

import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Utils methods for {@link DefaultWorkerToolExecutor} */
class DefaultWorkerToolUtils {

  private static final boolean IS_WINDOWS = Platform.detect() == Platform.WINDOWS;

  private static final long WAIT_FOR_EXECUTOR_SHUTDOWN_TIMEOUT = 100;
  private static final TimeUnit WAIT_FOR_EXECUTOR_SHUTDOWN_TIMEOUT_UNIT = TimeUnit.MILLISECONDS;

  private static final long OPEN_OUTPUT_STREAM_TIMEOUT = 10;
  private static final TimeUnit OPEN_OUTPUT_STREAM_TIMEOUT_UNIT = TimeUnit.SECONDS;

  private DefaultWorkerToolUtils() {}

  static OutputStream openStreamFromNamedPipe(NamedPipeWriter namedPipeWriter, int workerId)
      throws IOException {
    // if the code is running on windows, then it is need to wrap `getOutputStream()` with timeout
    if (IS_WINDOWS) {
      try {
        return runWithTimeout(
            namedPipeWriter::getOutputStream,
            "OpenOutputStreamFromPipe_" + workerId,
            OPEN_OUTPUT_STREAM_TIMEOUT,
            OPEN_OUTPUT_STREAM_TIMEOUT_UNIT);
      } catch (ExecutionException e) {
        throw new IOException(
            String.format(
                "Cannot open an output stream for named pipe: %s. Worker id: %s",
                namedPipeWriter.getName(), workerId),
            e);
      }
    }
    return namedPipeWriter.getOutputStream();
  }

  @VisibleForTesting
  static <T> T runWithTimeout(
      Callable<T> callable, String executorName, long timeout, TimeUnit timeoutUnit)
      throws ExecutionException {
    ExecutorService executor = MostExecutors.newSingleThreadExecutor(executorName);
    Future<T> future = executor.submit(callable);
    return runWithTimeout(future, executor, timeout, timeoutUnit);
  }

  @VisibleForTesting
  static <T> T runWithTimeout(
      Future<T> future, ExecutorService executor, long timeout, TimeUnit timeoutUnit)
      throws ExecutionException {
    try {
      return future.get(timeout, timeoutUnit);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new ExecutionException(
          "Timeout of " + timeoutUnit.toMillis(timeout) + "ms has been exceeded", e);
    } catch (InterruptedException e) {
      Threads.interruptCurrentThread();
      throw new ExecutionException("Thread has been interrupted", e);
    } finally {
      shutdownExecutor(executor);
    }
  }

  static void shutdownExecutor(ExecutorService executor) {
    try {
      MostExecutors.shutdown(
          executor, WAIT_FOR_EXECUTOR_SHUTDOWN_TIMEOUT, WAIT_FOR_EXECUTOR_SHUTDOWN_TIMEOUT_UNIT);
    } catch (InterruptedException e) {
      Threads.interruptCurrentThread();
    }
  }
}
