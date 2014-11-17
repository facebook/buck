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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import java.io.IOError;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

import javax.annotation.concurrent.GuardedBy;

/**
 * Implementation of Handler which writes to the console (System.err by default).
 *
 * Unlike {@link java.util.logging.ConsoleHandler}, this
 * implementation allows registering and unregistering multiple
 * {@link OutputStream}s via {@link #registerOutputStream(String, OutputStream)} and
 * {@link #unregisterOutputStream(String)}.
 *
 * Once an OutputStream is registered, this Handler no longer writes
 * to System.err.  When the last OutputStream is unregistered, this
 * Handler writes to System.err again.
 *
 * When registering an OutputStream, the client provides a unique command ID to
 * identify the client session to which the OutputStream is attached.
 *
 * This Handler only writes to each OutputStream log messages which
 * were issued by a thread with a matching command ID.
 *
 * Also unlike {@link java.util.logging.ConsoleHandler}, this does not
 * close the registered {@link OutputStream}s.
 */
public class ConsoleHandler extends Handler {
  private static final Level DEFAULT_LEVEL = Level.SEVERE;

  private final OutputStreamWriter defaultOutputStreamWriter;
  private final ConcurrentMap<Long, String> threadIdToCommandId;
  private final ConcurrentMap<String, OutputStreamWriter> commandIdToConsoleWriter;
  private final ConcurrentMap<String, Level> commandIdToLevel;

  @GuardedBy("this")
  private boolean closed;

  public ConsoleHandler() {
    this(
        utf8OutputStreamWriter(System.err),
        new LogFormatter(),
        getLogLevelFromProperty(LogManager.getLogManager(), DEFAULT_LEVEL),
        GlobalState.THREAD_ID_TO_COMMAND_ID,
        GlobalState.COMMAND_ID_TO_CONSOLE_WRITER,
        GlobalState.COMMAND_ID_TO_LEVEL);
  }

  @VisibleForTesting
  ConsoleHandler(
      OutputStreamWriter defaultOutputStreamWriter,
      Formatter formatter,
      Level level,
      ConcurrentMap<Long, String> threadIdToCommandId,
      ConcurrentMap<String, OutputStreamWriter> commandIdToConsoleWriter,
      ConcurrentMap<String, Level> commandIdToLevel) {
    this.defaultOutputStreamWriter = defaultOutputStreamWriter;
    setFormatter(formatter);
    setLevel(level);
    this.threadIdToCommandId = threadIdToCommandId;
    this.commandIdToConsoleWriter = commandIdToConsoleWriter;
    this.commandIdToLevel = commandIdToLevel;
  }

  /**
   * Flushes pending output, then writes to {@code outputStream} log
   * messages issued by threads for {@code commandId} until
   * {@link #unregisterOutputStream(String)} is called.
   */
  public synchronized void registerOutputStream(String commandId, OutputStream outputStream) {

    flush();
    commandIdToConsoleWriter.put(commandId, utf8OutputStreamWriter(outputStream));
  }

  /**
   * Flushes pending output, then ensures further log messages are no longer
   * written to the most recent {@link OutputStream} registered for {@code commandId}.
   */
  public synchronized void unregisterOutputStream(String commandId) {

    flush();
    OutputStreamWriter oldWriter = commandIdToConsoleWriter.remove(commandId);

    // We better have removed something, or commandId was invalid.
    Preconditions.checkState(oldWriter != null);
  }

  /**
   * Flushes pending output, then ensures log messages with level greater than
   * or equal to {@code logLevel} are written to the most recent {@link OutputStream}
   * registered for {@code commandId}.
   */
  public synchronized void registerLogLevel(String commandId, Level logLevel) {

    flush();
    commandIdToLevel.put(commandId, logLevel);
  }

  /**
   * Flushes pending output, then ensures further log messages use the
   * logger's configured level when writing to the most recent {@link OutputStream}
   * registered for {@code commandId}.
   */
  public synchronized void unregisterLogLevel(String commandId) {

    flush();
    Level oldLevel = commandIdToLevel.remove(commandId);

    // We better have removed something, or commandId was invalid.
    Preconditions.checkState(oldLevel != null);
  }

  @Override
  public synchronized void publish(LogRecord record) {
    if (closed || !(isLoggable(record) || isLoggableWithRegisteredLogLevel(record))) {
      return;
    }

    Iterable<OutputStreamWriter> outputStreamWriters = getOutputStreamWritersForRecord(record);

    try {
      String formatted = getFormatter().format(record);

      for (OutputStreamWriter outputStreamWriter : outputStreamWriters) {
        outputStreamWriter.write(formatted);
        if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
          outputStreamWriter.flush();
        }
      }
    } catch (IOException e) {
      throw new IOError(e);
    }
  }

  @Override
  public synchronized void close() {
    flush();
    // We explicitly do not close any registered writers, so we don't close
    // System.err accidentally.
    closed = true;
  }

  @Override
  public synchronized void flush() {
    if (closed) {
      return;
    }
    try {
      for (OutputStreamWriter outputStreamWriter : commandIdToConsoleWriter.values()) {
        outputStreamWriter.flush();
      }
      defaultOutputStreamWriter.flush();
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

  @VisibleForTesting
  static OutputStreamWriter utf8OutputStreamWriter(OutputStream outputStream) {
    try {
      return new OutputStreamWriter(outputStream, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IOError(e);
    }
  }

  private boolean isLoggableWithRegisteredLogLevel(LogRecord record) {
    long recordThreadId = record.getThreadID();
    String logRecordCommandId = threadIdToCommandId.get(recordThreadId);
    if (logRecordCommandId == null) {
      // An unregistered thread created this LogRecord, so we don't want to force logging it.
      return false;
    }
    Level commandIdLogLevel = commandIdToLevel.get(logRecordCommandId);
    if (commandIdLogLevel == null) {
      // No log level override registered for this command ID. Don't force logging it.
      return false;
    }

    // Level.ALL.intValue() is Integer.MIN_VALUE, so have to compare it explicitly.
    return commandIdLogLevel.equals(Level.ALL) ||
      commandIdLogLevel.intValue() >= record.getLevel().intValue();
  }

  private Iterable<OutputStreamWriter> getOutputStreamWritersForRecord(LogRecord record) {
    ImmutableSet.Builder<OutputStreamWriter> builder = ImmutableSet.builder();
    long recordThreadId = record.getThreadID();
    String logRecordCommandId = threadIdToCommandId.get(recordThreadId);
    if (logRecordCommandId != null) {
      OutputStreamWriter consoleWriter = commandIdToConsoleWriter.get(logRecordCommandId);
      if (consoleWriter != null) {
        builder.add(consoleWriter);
      } else {
        builder.add(defaultOutputStreamWriter);
      }
    } else {
      Collection<OutputStreamWriter> allConsoleWriters = commandIdToConsoleWriter.values();
      if (allConsoleWriters.isEmpty()) {
        builder.add(defaultOutputStreamWriter);
      } else {
        builder.addAll(allConsoleWriters);
      }
    }

    return builder.build();
  }
}
