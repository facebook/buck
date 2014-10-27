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

import com.facebook.buck.log.ConsoleHandler;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;

import java.io.OutputStream;

/**
 * Temporarily redirects the ConsoleHandler's output to another stream.
 *
 * Restores the original output stream when closed.
 */
public class ConsoleHandlerRedirector implements AutoCloseable {
  private final String commandId;
  private final OutputStream redirectedOutputStream;
  private final Optional<OutputStream> originalOutputStream;
  private final Optional<ConsoleHandler> consoleHandler;

  public ConsoleHandlerRedirector(
      String commandId,
      OutputStream redirectedOutputStream,
      Optional<OutputStream> originalOutputStream) {
    this(
        commandId,
        redirectedOutputStream,
        originalOutputStream,
        JavaUtilLogHandlers.getConsoleHandler());
  }

  @VisibleForTesting
  ConsoleHandlerRedirector(
      String commandId,
      OutputStream redirectedOutputStream,
      Optional<OutputStream> originalOutputStream,
      Optional<ConsoleHandler> consoleHandler) {
    this.commandId = commandId;
    this.redirectedOutputStream = redirectedOutputStream;
    this.originalOutputStream = originalOutputStream;
    this.consoleHandler = consoleHandler;

    if (this.consoleHandler.isPresent()) {
      this.consoleHandler.get().registerOutputStream(this.commandId, this.redirectedOutputStream);
    }
  }

  @Override
  public void close() {
    if (consoleHandler.isPresent()) {
      if (originalOutputStream.isPresent()) {
        consoleHandler.get().registerOutputStream(commandId, originalOutputStream.get());
      } else {
        consoleHandler.get().unregisterOutputStream(commandId);
      }
    }
  }
}
