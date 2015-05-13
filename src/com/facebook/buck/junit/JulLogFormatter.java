/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.junit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public class JulLogFormatter extends Formatter {
  private static final int ERROR_LEVEL = Level.SEVERE.intValue();
  private static final int WARN_LEVEL = Level.WARNING.intValue();
  private static final int INFO_LEVEL = Level.INFO.intValue();
  private static final int DEBUG_LEVEL = Level.FINE.intValue();
  private static final int VERBOSE_LEVEL = Level.FINER.intValue();
  private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
    new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            SimpleDateFormat format = new SimpleDateFormat(
                "[yyyy-MM-dd HH:mm:ss.SSS]",
                Locale.US);
            format.setTimeZone(TimeZone.getDefault());
            return format;
        }
    };

  @Override
  public String format(LogRecord record) {
    String timestamp = DATE_FORMAT.get().format(new Date(record.getMillis()));

    // We explicitly don't use String.format here because this code is very
    // performance-critical: http://stackoverflow.com/a/1281651
    long tid = record.getThreadID();
    StringBuilder sb = new StringBuilder(timestamp)
      .append(formatRecordLevel(record.getLevel()))
      .append("[tid:");
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
      // Closing a StringWriter has no effect, so we don't need to do it in a
      // try-with-resources (but oddly, it throws IOException, so we couldn't
      // use it in a try-with-resources if we wanted to).
      StringWriter sw = new StringWriter();
      try (PrintWriter pw = new PrintWriter(sw)) {
        t.printStackTrace(pw);
        sb.append(sw)
            .append("\n");
      }
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
}
