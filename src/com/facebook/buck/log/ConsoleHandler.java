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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOError;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import javax.annotation.concurrent.GuardedBy;

/** Implementation of Handler which writes to the console (System.err by default). */
public class ConsoleHandler extends Handler {
  private static final Level DEFAULT_LEVEL = Level.SEVERE;

  private final ConsoleHandlerState.Writer defaultOutputStreamWriter;
  private final ConsoleHandlerState state;

  @GuardedBy("this")
  private boolean closed;

  public ConsoleHandler() {
    this(
        utf8OutputStreamWriter(System.err),
        new LogFormatter(),
        getLogLevelFromProperty(LogManager.getLogManager(), DEFAULT_LEVEL),
        GlobalStateManager.singleton().getConsoleHandlerState());
  }

  @VisibleForTesting
  ConsoleHandler(
      ConsoleHandlerState.Writer defaultOutputStreamWriter,
      Formatter formatter,
      Level level,
      ConsoleHandlerState state) {
    this.defaultOutputStreamWriter = defaultOutputStreamWriter;
    setFormatter(formatter);
    setLevel(level);
    this.state = state;
  }

  @Override
  public void publish(LogRecord record) {
    synchronized (this) {
      if (closed
          || !(isLoggable(record) || isLoggableWithRegisteredLogLevel(record))
          || isBlacklisted(record)) {
        return;
      }
    }

    Iterable<ConsoleHandlerState.Writer> outputStreamWriters =
        getOutputStreamWritersForRecord(record);

    try {
      String formatted = getFormatter().format(record);

      for (ConsoleHandlerState.Writer outputStreamWriter : outputStreamWriters) {
        synchronized (outputStreamWriter) {
          outputStreamWriter.write(formatted);
          if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
            outputStreamWriter.flush();
          }
        }
      }
    } catch (IOException e) {
      throw new IOError(e);
    }
  }

  @Override
  public void close() {
    synchronized (this) {
      if (!closed) {
        flush();
        // We explicitly do not close any registered writers, so we don't close
        // System.err accidentally.
        closed = true;
      }
    }
  }

  @Override
  public void flush() {
    synchronized (this) {
      if (closed) {
        return;
      }
    }

    try {
      for (ConsoleHandlerState.Writer outputStreamWriter : state.getAllAvailableWriters()) {
        synchronized (outputStreamWriter) {
          outputStreamWriter.flush();
        }
      }
      synchronized (defaultOutputStreamWriter) {
        defaultOutputStreamWriter.flush();
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

  public static ConsoleHandlerState.Writer utf8OutputStreamWriter(OutputStream outputStream) {
    try {
      final OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream, "UTF-8");
      return new ConsoleHandlerState.Writer() {
        @Override
        public void write(String line) throws IOException {
          outputStreamWriter.write(line);
        }

        @Override
        public void flush() throws IOException {
          outputStreamWriter.flush();
        }

        @Override
        public void close() throws IOException {
          outputStreamWriter.close();
        }
      };
    } catch (UnsupportedEncodingException e) {
      throw new IOError(e);
    }
  }

  private boolean isLoggableWithRegisteredLogLevel(LogRecord record) {
    long recordThreadId = record.getThreadID();
    String logRecordCommandId = state.threadIdToCommandId(recordThreadId);
    if (logRecordCommandId == null) {
      // An unregistered thread created this LogRecord, so we don't want to force logging it.
      return false;
    }
    Level commandIdLogLevel = state.getLogLevel(logRecordCommandId);
    if (commandIdLogLevel == null) {
      // No log level override registered for this command ID. Don't force logging it.
      return false;
    }

    // Level.ALL.intValue() is Integer.MIN_VALUE, so have to compare it explicitly.
    return commandIdLogLevel.equals(Level.ALL)
        || commandIdLogLevel.intValue() >= record.getLevel().intValue();
  }

  private boolean isBlacklisted(LogRecord record) {
    // Guava futures internals are not very actionable to the user but we still want to have
    // them in the log.
    return record.getLoggerName() != null
        && record.getLoggerName().startsWith("com.google.common.util.concurrent");
  }

  private Iterable<ConsoleHandlerState.Writer> getOutputStreamWritersForRecord(LogRecord record) {
    long recordThreadId = record.getThreadID();
    String logRecordCommandId = state.threadIdToCommandId(recordThreadId);
    if (logRecordCommandId != null) {
      ConsoleHandlerState.Writer consoleWriter = state.getWriter(logRecordCommandId);
      if (consoleWriter != null) {
        return ImmutableSet.of(consoleWriter);
      } else {
        return ImmutableSet.of(defaultOutputStreamWriter);
      }
    } else {
      Iterable<ConsoleHandlerState.Writer> allConsoleWriters = state.getAllAvailableWriters();
      if (Iterables.isEmpty(allConsoleWriters)) {
        return ImmutableSet.of(defaultOutputStreamWriter);
      } else {
        ImmutableSet.Builder<ConsoleHandlerState.Writer> builder = ImmutableSet.builder();
        builder.addAll(allConsoleWriters);
        return builder.build();
      }
    }
  }
}
