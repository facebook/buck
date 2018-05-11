/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.event.listener;

import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger; // NOPMD

/** Logs build events to java.util.logging. */
public class JavaUtilsLoggingBuildListener implements BuckEventListener {

  private static final Logger LOG = Logger.getLogger(JavaUtilsLoggingBuildListener.class.getName());
  private static final Level LEVEL = Level.INFO;

  public JavaUtilsLoggingBuildListener(ProjectFilesystem filesystem) {
    ensureLogFileIsWritten(filesystem);
  }

  private static void ensureLogFileIsWritten(ProjectFilesystem filesystem) {
    if (!filesystem.exists(filesystem.getBuckPaths().getScratchDir())) {
      try {
        filesystem.mkdirs(filesystem.getBuckPaths().getScratchDir());
      } catch (IOException e) {
        throw new HumanReadableException(
            e,
            "Unable to create output directory: %s: %s: %s",
            filesystem.getBuckPaths().getScratchDir(),
            e.getClass(),
            e.getMessage());
      }
    }

    try {
      FileHandler handler =
          new FileHandler(
              filesystem
                  .resolve(filesystem.getBuckPaths().getScratchDir())
                  .resolve("build.log")
                  .toString(),
              /* append */ false);
      Formatter formatter = new BuildEventFormatter();
      handler.setFormatter(formatter);

      LOG.setUseParentHandlers(false);
      LOG.addHandler(handler);

      LOG.setLevel(LEVEL);

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Subscribe
  public void buildStarted(BuildEvent.Started started) {
    LogRecord record = new LogRecord(LEVEL, "Build started");
    record.setMillis(started.getTimestamp());
    LOG.log(record);
  }

  @Subscribe
  public void buildFinished(BuildEvent.Finished finished) {
    LogRecord record = new LogRecord(LEVEL, "Build finished");
    record.setMillis(finished.getTimestamp());
    LOG.log(record);
  }

  @Subscribe
  public void ruleStarted(BuildRuleEvent.Started started) {
    LogRecord record = new LogRecord(LEVEL, started.toString());
    record.setMillis(started.getTimestamp());
    LOG.log(record);
  }

  @Subscribe
  public void ruleFinished(BuildRuleEvent.Finished finished) {
    LogRecord record = new LogRecord(LEVEL, finished.toLogMessage());
    record.setMillis(finished.getTimestamp());
    LOG.log(record);
  }

  @Subscribe
  public void ruleResumed(BuildRuleEvent.Resumed resumed) {
    LogRecord record = new LogRecord(LEVEL, resumed.toString());
    record.setMillis(resumed.getTimestamp());
    LOG.log(record);
  }

  @Subscribe
  public void ruleSuspended(BuildRuleEvent.Suspended suspended) {
    LogRecord record = new LogRecord(LEVEL, suspended.toString());
    record.setMillis(suspended.getTimestamp());
    LOG.log(record);
  }

  @Override
  public void outputTrace(BuildId buildId) {
    closeLogFile();
  }

  private static class BuildEventFormatter extends Formatter {

    private final ThreadLocal<SimpleDateFormat> dateFormat =
        new ThreadLocal<SimpleDateFormat>() {
          @Override
          protected SimpleDateFormat initialValue() {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
            // Normalize all the times.
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format;
          }
        };

    @Override
    public String format(LogRecord logRecord) {
      StringBuilder builder = new StringBuilder();

      SimpleDateFormat format = dateFormat.get();
      builder.append(format.format(new Date(logRecord.getMillis())));

      builder.append("\t").append(logRecord.getLevel()).append("\t");
      builder.append(formatMessage(logRecord));
      builder.append("\n");

      return builder.toString();
    }
  }

  public static void closeLogFile() {
    for (Handler handler : LOG.getHandlers()) {
      if (handler instanceof FileHandler) {
        LOG.removeHandler(handler);
        handler.close();
      }
    }
  }
}
