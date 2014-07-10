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

package com.facebook.buck.cli;

import com.facebook.buck.util.Verbosity;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Temporarily overrides the console log level to match a verbosity
 * flag passed from the command-line.
 */
public class ConsoleLogLevelOverrider implements AutoCloseable {

  private final Optional<Handler> consoleHandler;
  private final Optional<Level> originalConsoleLevel;

  public ConsoleLogLevelOverrider(Verbosity verbosity) {

    consoleHandler = getConsoleHandler();
    if (consoleHandler.isPresent() &&
        verbosity == Verbosity.ALL &&
        !consoleHandler.get().getLevel().equals(Level.ALL)) {
      this.originalConsoleLevel = Optional.of(consoleHandler.get().getLevel());
      consoleHandler.get().setLevel(Level.ALL);
    } else {
      this.originalConsoleLevel = Optional.absent();
    }
  }

  @Override
  public void close() {
    if (consoleHandler.isPresent() && originalConsoleLevel.isPresent()) {
      consoleHandler.get().setLevel(originalConsoleLevel.get());
    }
  }

  private static Optional<Handler> getConsoleHandler() {
    // We can't stop someone from mutating this array, but we can minimize the chance.
    ImmutableList<Handler> handlers = ImmutableList.copyOf(Logger.getLogger("").getHandlers());
    for (Handler handler : handlers) {
      if (handler instanceof ConsoleHandler) {
        return Optional.of(handler);
      }
    }
    return Optional.absent();
  }
}
