/*
 * Copyright 2016-present Facebook, Inc.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

/**
 * <code>MemoryHandler</code> maintains a circular buffer of LogRecords. The underlying circular
 * buffer implementation is implemented directly from java.util.logging.MemoryHandler, but this
 * handler extends the default JUL handler by allowing LogRecords to be handled in batch.
 * Logs are only written to file if a LogRecord at or above the push level is recorded.
 */
public class MemoryHandler extends Handler {
  private static final Logger LOG = Logger.get(MemoryHandler.class);
  private static final LogManager LOG_MANAGER = LogManager.getLogManager();

  private static final Level DEFAULT_LEVEL = Level.ALL;
  private static final Level DEFAULT_PUSH_LEVEL = Level.SEVERE;
  private static final Integer DEFAULT_BUFFER_SIZE = 100;
  private static final Formatter DEFAULT_FORMATTER = new LogFormatter();

  private final LogRecord[] buffer;
  private int start;
  private int count;
  private Level pushLevel;

  /**
   * Constructs a <code>MemoryHandler</code> specified by:.
   * <ul>
   * <li>   com.facebook.buck.cli.bootstrapper.MemoryHandler.level
   *        specifies the level for the <tt>Handler</tt>.
   * <li>   com.facebook.buck.cli.bootstrapper.MemoryHandler.size
   *        defines the buffer size.
   * <li>   com.facebook.buck.cli.bootstrapper.MemoryHandler.push
   *        defines the <tt>pushLevel</tt>.
   * <li>   com.facebook.buck.cli.bootstrapper.MemoryHandler.formatter
   *        defines the <tt>formatter</tt> for log records.
   * </ul>
   */
  public MemoryHandler() throws IOException {
    this(
        getLogLevelProperty(),
        getBufferSizeProperty(),
        getPushLevelProperty());
  }

  @VisibleForTesting
  MemoryHandler(
      Level logLevel,
      int bufferSize,
      Level pushLevel) {
    Preconditions.checkState(bufferSize >= 0);
    Preconditions.checkNotNull(pushLevel);

    buffer = new LogRecord[bufferSize];
    this.pushLevel = pushLevel;

    setLevel(logLevel);
    setFormatter(DEFAULT_FORMATTER);
  }

  private static Level getLogLevelProperty() {
    String levelStr = LOG_MANAGER.getProperty(MemoryHandler.class.getName() + ".level");
    if (levelStr != null) {
      return Level.parse(levelStr);
    } else {
      LOG.info("No log level specified so default log level %s will be used", DEFAULT_LEVEL);
      return DEFAULT_LEVEL;
    }
  }

  private static Integer getBufferSizeProperty() {
    String size = LOG_MANAGER.getProperty(MemoryHandler.class.getName() + ".size");
    if (size != null) {
      try {
        int intSize = Integer.parseInt(size);
        if (intSize > 0) {
          return intSize;
        }
      } catch (NumberFormatException e) {
          LOG.warn("Invalid buffer size specified in logging.properties (must be > 0), using " +
              "default buffer size of %s", DEFAULT_BUFFER_SIZE);
        }
    }
    LOG.info("No buffer size specified so default size %s will be used", DEFAULT_BUFFER_SIZE);
    return DEFAULT_BUFFER_SIZE;
  }

  private static Level getPushLevelProperty() {
    String levelStr = LOG_MANAGER.getProperty(MemoryHandler.class.getName() + ".push");
    if (levelStr != null) {
      return Level.parse(levelStr);
    } else {
      LOG.info("No push level specified so default push level %s will be used", DEFAULT_PUSH_LEVEL);
      return DEFAULT_PUSH_LEVEL;
    }
  }

  @Override
  public void publish(LogRecord record) {
    if (!isLoggable(record)) {
      return;
    }
    int ix = (start + count) % buffer.length;
    buffer[ix] = record;
    if (count < buffer.length) {
      count++;
    } else {
      start++;
      start %= buffer.length;
    }
    if (record.getLevel().intValue() >= pushLevel.intValue()) {
      push();
    }
  }

  public void push() {
    start = 0;
    count = 0;
  }

  @Override
  public void flush() {}

  @Override
  public void close() throws SecurityException {}
}
