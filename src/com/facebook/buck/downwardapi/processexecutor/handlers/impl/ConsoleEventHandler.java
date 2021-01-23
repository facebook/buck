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

import static com.facebook.buck.event.ConsoleEvent.create;

import com.facebook.buck.downward.model.ConsoleEvent;
import com.facebook.buck.downward.model.LogLevel;
import com.facebook.buck.downwardapi.processexecutor.context.DownwardApiExecutionContext;
import com.facebook.buck.downwardapi.processexecutor.handlers.EventHandler;
import java.util.logging.Level;

/** Downward API event handler for {@code ConsoleEvent} */
enum ConsoleEventHandler implements EventHandler<ConsoleEvent> {
  INSTANCE;

  @Override
  public void handleEvent(DownwardApiExecutionContext context, ConsoleEvent event) {
    Level level = toJulLevel(event.getLogLevel());
    String message = event.getMessage();
    context.postEvent(create(level, message));
  }

  /** Transforms Downward API {@code LogLevel} to java util logger's level. */
  private Level toJulLevel(LogLevel logLevel) {
    switch (logLevel) {
      case TRACE:
        return Level.FINER;

      case DEBUG:
        return Level.FINE;

      case INFO:
        return Level.INFO;

      case WARN:
        return Level.WARNING;

      case ERROR:
      case FATAL:
        return Level.SEVERE;

      case UNKNOWN:
      case UNRECOGNIZED:
      default:
        throw new IllegalStateException("Level: " + logLevel + " is not supported!");
    }
  }
}
