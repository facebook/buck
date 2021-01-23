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

package com.facebook.buck.downwardapi.processexecutor.handlers.impl;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.LogEvent;
import com.facebook.buck.downward.model.LogLevel;
import com.facebook.buck.downwardapi.processexecutor.context.DownwardApiExecutionContext;
import com.facebook.buck.downwardapi.processexecutor.handlers.EventHandler;

/** Downward API event handler for {@code LogEvent} */
enum LogEventHandler implements EventHandler<LogEvent> {
  INSTANCE;

  @Override
  public void handleEvent(DownwardApiExecutionContext context, LogEvent event) {
    Logger logger = Logger.get(event.getLoggerName());
    LogLevel logLevel = event.getLogLevel();
    String message = event.getMessage();

    logMessage(logger, logLevel, message);
  }

  private void logMessage(Logger logger, LogLevel logLevel, String message) {
    switch (logLevel) {
      case TRACE:
        logger.verbose(message);
        return;

      case DEBUG:
        logger.debug(message);
        return;

      case INFO:
        logger.info(message);
        return;

      case WARN:
        logger.warn(message);
        return;

      case ERROR:
      case FATAL:
        logger.error(message);
        return;

      case UNKNOWN:
      case UNRECOGNIZED:
      default:
        throw new IllegalStateException("Level: " + logLevel + " is not supported!");
    }
  }
}
