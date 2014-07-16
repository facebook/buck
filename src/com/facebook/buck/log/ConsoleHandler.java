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

package com.facebook.buck.log;

import java.io.IOException;
import java.io.IOError;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.util.logging.Handler;

import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

/**
 * Implementation of Handler which writes to System.err.
 *
 * Unlike {@link java.util.logging.ConsoleHandler}, this
 * implementation allows changing its OutputStream via
 * {@link setOutputStream()} without closing the old one first.
 *
 * (Closing System.err is generally not a side effect we want our logging
 * infrastructure to have.)
 */
public class ConsoleHandler extends Handler {
  private static final Level DEFAULT_LEVEL = Level.SEVERE;

  private OutputStreamWriter writer;

  public ConsoleHandler() {
    this(
        System.err,
        new LogFormatter(),
        getLogLevelFromProperty(LogManager.getLogManager(), DEFAULT_LEVEL));
  }

  @VisibleForTesting
  ConsoleHandler(
      OutputStream outputStream,
      Formatter formatter,
      Level level) {
    setOutputStream(Preconditions.checkNotNull(outputStream));
    setFormatter(Preconditions.checkNotNull(formatter));
    setLevel(Preconditions.checkNotNull(level));
  }

  /**
   * Flushes pending output, then redirects any further log messages to the new outputStream.
   */
  public synchronized void setOutputStream(OutputStream outputStream) {
    Preconditions.checkNotNull(outputStream);

    flush();
    // Note that unlike java.util.ConsoleHandler's superclass
    // StreamHandler, we explicitly do not close the writer.
    // Doing so would close its OutputStream (System.err by default),
    // which is not something this class will do.
    try {
      this.writer = new OutputStreamWriter(outputStream, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IOError(e);
    }
  }

  @Override
  public synchronized void publish(LogRecord record) {
    if (writer != null && isLoggable(record)) {
      try {
        writer.write(getFormatter().format(record));
      } catch (IOException e) {
        throw new IOError(e);
      }
    }
  }

  @Override
  public synchronized void close() {
    flush();
    // We explicitly do not close the writer, so we don't close
    // System.err accidentally.
    writer = null;
  }

  @Override
  public synchronized void flush() {
    try {
      if (writer != null) {
        writer.flush();
      }
    } catch (IOException e) {
      throw new IOError(e);
    }
  }

  private static Level getLogLevelFromProperty(LogManager logManager, Level defaultLevel) {
    String levelStr = logManager.getProperty(ConsoleHandler.class.getName() + ".level");
    if (levelStr != null) {
      return Level.parse(levelStr);
    } else {
      return defaultLevel;
    }
  }
}
