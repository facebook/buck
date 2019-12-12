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

package com.facebook.buck.log;

import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Switches between synchronous and asynchronous LogFileHandler, depending on value returned from
 * LogConfig.shouldUseAsyncFileLogging() Note: this could potentially change after
 * LogFileHandlerDispatcher is created
 */
public class LogFileHandlerDispatcher extends Handler {
  private final LogFileHandler logFileHandler;
  private final AsyncLogHandler asyncLogFileHandler;

  public LogFileHandlerDispatcher() {
    logFileHandler = new LogFileHandler();
    asyncLogFileHandler = new AsyncLogHandler(logFileHandler);
  }

  @Override
  public void publish(LogRecord record) {
    if (LogConfig.shouldUseAsyncFileLogging()) {
      asyncLogFileHandler.publish(record);
    } else {
      logFileHandler.publish(record);
    }
  }

  @Override
  public void flush() {
    if (LogConfig.shouldUseAsyncFileLogging()) {
      asyncLogFileHandler.flush();
    } else {
      logFileHandler.flush();
    }
  }

  @Override
  public void close() throws SecurityException {
    if (LogConfig.shouldUseAsyncFileLogging()) {
      asyncLogFileHandler.flush();
    } else {
      logFileHandler.flush();
    }
  }
}
