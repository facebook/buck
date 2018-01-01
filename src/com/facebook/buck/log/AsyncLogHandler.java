/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.log;

import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;

/**
 * Wraps a log Handler, e.g. {@link com.facebook.buck.log.LogFileHandler}, but ensures that all
 * requests are dispatched on a separate thread and do not block the caller.
 */
public class AsyncLogHandler extends Handler {
  private enum LogRequestType {
    PUBLISH_LOG_RECORD,
    FLUSH_LOGS,
    CLOSE_HANDLER
  }

  private static class LogRequest {
    private final LogRequestType logRequestType;
    private final Optional<LogRecord> logRecord;

    public LogRequest(LogRequestType logRequestType, Optional<LogRecord> logRecord) {
      this.logRequestType = logRequestType;
      this.logRecord = logRecord;
    }

    public static LogRequest newPublishRequest(LogRecord logRecord) {
      return new LogRequest(LogRequestType.PUBLISH_LOG_RECORD, Optional.of(logRecord));
    }

    public static LogRequest newFLushLogsRequest() {
      return new LogRequest(LogRequestType.FLUSH_LOGS, Optional.empty());
    }

    public static LogRequest newCloseHandlerRequest() {
      return new LogRequest(LogRequestType.CLOSE_HANDLER, Optional.empty());
    }
  }

  private final BlockingQueue<LogRequest> asyncLogRequestsQueue = new LinkedBlockingDeque<>();
  private final Supplier<Executor> performLoggingExecutor; // Only instantiate if started
  private final Handler delegate;
  private AtomicBoolean asyncHandlerHasShutdown = new AtomicBoolean(false);
  private AtomicBoolean asyncHandlerHasStarted = new AtomicBoolean(false);

  public AsyncLogHandler(LogFileHandler delegate) {
    this(() -> MostExecutors.newSingleThreadExecutor("AsyncLogHandler"), delegate);
  }

  @VisibleForTesting
  protected AsyncLogHandler(Supplier<Executor> performLoggingExecutor, Handler delegate) {
    this.performLoggingExecutor = performLoggingExecutor;
    this.delegate = delegate;
  }

  @Override
  public void publish(LogRecord record) {
    if (asyncHandlerHasShutdown.get()) {
      performSynchronousPublish(record);
    } else {
      queueAyncRequest(LogRequest.newPublishRequest(record));
    }
  }

  @Override
  public void flush() {
    if (asyncHandlerHasShutdown.get()) {
      performSynchronousFlush();
    } else {
      queueAyncRequest(LogRequest.newFLushLogsRequest());
    }
  }

  @Override
  public void close() throws SecurityException {
    if (asyncHandlerHasShutdown.get()) {
      performSynchronousClose();
    } else {
      queueAyncRequest(LogRequest.newCloseHandlerRequest());
    }
  }

  private void performSynchronousPublish(LogRecord record) {
    delegate.publish(record);
  }

  private void performSynchronousFlush() {
    delegate.flush();
  }

  private void performSynchronousClose() {
    delegate.close();
  }

  private void shutdownAsyncHandler() {
    // Signal that executor should now shut down, and no more request should be accepted.
    // Note: we might lose a single request of each type if they already received
    // asyncHandlerHasShutdown == true, but haven't yet submitted items.
    asyncHandlerHasShutdown.set(true);

    List<LogRequest> remainingElements =
        asyncLogRequestsQueue.stream().collect(Collectors.toList());
    asyncLogRequestsQueue.removeAll(remainingElements);

    if (remainingElements.size() > 0) {
      logViaDelegate(
          Level.WARNING,
          String.format(
              "AsyncLogHandler received close() request, even though it still has [%d] pending requests to handle.",
              remainingElements.size()));
    }

    performSynchronousClose();

    logViaDelegate(
        Level.INFO,
        String.format(
            "Finishing shutting down AsyncLogHandler. "
                + "Reverting to synchronous log Handler for all future requests. "
                + "Remaining elements: %d.",
            remainingElements.size()));
  }

  private void logViaDelegate(Level level, String msg) {
    LogRecord logRecord = new LogRecord(level, msg);
    logRecord.setMillis(System.currentTimeMillis());
    delegate.publish(logRecord);
  }

  private void asyncRequestProcessingLoop() {
    while (!asyncHandlerHasShutdown.get()) {
      LogRequest logRequest;
      try {
        logRequest = asyncLogRequestsQueue.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // reset interrupt flag
        shutdownAsyncHandler();
        logViaDelegate(Level.SEVERE, String.format("AsyncLogHandler was interrupted. Exiting."));
        return;
      }

      switch (logRequest.logRequestType) {
        case PUBLISH_LOG_RECORD:
          performSynchronousPublish(Preconditions.checkNotNull(logRequest.logRecord.get()));
          break;
        case FLUSH_LOGS:
          performSynchronousFlush();
          break;
        case CLOSE_HANDLER:
          shutdownAsyncHandler();
          break;
        default:
          logViaDelegate(
              Level.WARNING,
              String.format(
                  "AsyncLogHandler received unknown request type: %s", logRequest.logRequestType));
          break;
      }
    }
  }

  private void ensureStarted() {
    if (!asyncHandlerHasStarted.compareAndSet(false, true)) {
      return; // already started
    }

    this.performLoggingExecutor.get().execute(() -> asyncRequestProcessingLoop());
  }

  private void queueAyncRequest(LogRequest request) {
    ensureStarted();
    asyncLogRequestsQueue.add(request);
  }
}
