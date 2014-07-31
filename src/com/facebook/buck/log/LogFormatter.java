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

import com.facebook.buck.util.concurrent.MoreExecutors;
import com.google.common.base.Preconditions;

import java.io.Closeable;
import java.io.IOException;
import java.text.SimpleDateFormat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import javax.annotation.Nullable;

public class LogFormatter extends java.util.logging.Formatter {
  private static final int ERROR_LEVEL = Level.SEVERE.intValue();
  private static final int WARN_LEVEL = Level.WARNING.intValue();
  private static final int INFO_LEVEL = Level.INFO.intValue();
  private static final int DEBUG_LEVEL = Level.FINE.intValue();
  private static final int VERBOSE_LEVEL = Level.FINER.intValue();
  private final ThreadLocal<SimpleDateFormat> simpleDateFormat;
  private static final Map<Long, String> threadCommandMap =
      Collections.synchronizedMap(new HashMap<Long, String>());

  public LogFormatter() {
    simpleDateFormat = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            SimpleDateFormat format = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS]");
            return format;
        }
      };
  }

  @Override
  public String format(LogRecord record) {
    String timestamp = simpleDateFormat.get().format(new Date(record.getMillis()));

    // We explicitly don't use String.format here because this code is very
    // performance-critical: http://stackoverflow.com/a/1281651
    long tid = record.getThreadID();
    @Nullable String command = threadCommandMap.get(tid);
    StringBuilder sb = new StringBuilder(timestamp)
      .append(formatRecordLevel(record.getLevel()))
      .append("[command:")
      .append(command)
      .append("][tid:");
    // Zero-pad on the left. We're currently assuming we have less than 100 threads.
    if (tid < 10) {
      sb.append("0").append(tid);
    } else {
      sb.append(tid);
    }
    sb.append("][")
      .append(record.getLoggerName())
      .append("] ")
      .append(formatMessage(record))
      .append("\n");
    Throwable t = record.getThrown();
    if (t != null) {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      t.printStackTrace(pw);
      sb.append(sw)
        .append("\n");
    }
    return sb.toString();
  }

  private static String formatRecordLevel(Level level) {
    int l = level.intValue();
    if (l == ERROR_LEVEL) {
      return "[error]";
    } else if (l == WARN_LEVEL) {
      return "[warn ]";
    } else if (l == INFO_LEVEL) {
      return "[info ]";
    } else if (l == DEBUG_LEVEL) {
      return "[debug]";
    } else if (l == VERBOSE_LEVEL) {
      return "[vrbos]";
    } else {
      // We don't expect this to happen, so meh, let's use String.format for simplicity.
      return String.format("[%-5d]", l);
    }
  }

  /**
   * An association between the current thread and a given command.
   * Closeable so that its lifecycle can by safely managed with a try-with-resources block.
   */
  public static class CommandThreadAssociation implements Closeable {

    private String commandId;

    public CommandThreadAssociation(String commandId) {
      this.commandId = Preconditions.checkNotNull(commandId);
      threadCommandMap.put(Thread.currentThread().getId(), commandId);
    }

    @Override
    public void close() throws IOException {
      // remove(commandId) would only remove the first association.
      threadCommandMap.values().removeAll(Collections.singleton(commandId));
    }
  }

  /**
   * A ThreadFactory which associates created threads with the same command associated with
   * the thread which creates the CommandThreadFactory.
   */
  public static class CommandThreadFactory implements ThreadFactory {

    private MoreExecutors.NamedThreadFactory threadFactory;
    @Nullable private String commandId;

    public CommandThreadFactory(String threadName) {
      threadFactory = new MoreExecutors.NamedThreadFactory(threadName);
      commandId = threadCommandMap.get(Thread.currentThread().getId());
    }

    @Override
    public Thread newThread(Runnable r) {
      Thread newThread = threadFactory.newThread(r);
      threadCommandMap.put(newThread.getId(), commandId);
      return newThread;
    }
  }
}
