/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import static com.facebook.buck.util.BuckConstant.DIST_BUILD_SLAVE_BUCK_OUT_LOG_DIR_NAME;

import com.facebook.buck.distributed.thrift.BuildSlaveConsoleEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventType;
import com.facebook.buck.distributed.thrift.ConsoleEventSeverity;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;

public class DistBuildUtil {
  private static final Logger LOG = Logger.get(ConsoleEvent.class);
  private static final DateFormat DATE_FORMAT = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS]");

  private DistBuildUtil() {}

  public static BuildSlaveEvent createBuildSlaveEvent(
      BuildSlaveEventType eventType, long timeMillis) {
    return new BuildSlaveEvent().setEventType(eventType).setTimestampMillis(timeMillis);
  }

  /** Create an empty BuildSlaveConsoleEvent. */
  public static BuildSlaveEvent createBuildSlaveConsoleEvent(long timeMillis) {
    return createBuildSlaveEvent(BuildSlaveEventType.CONSOLE_EVENT, timeMillis)
        .setConsoleEvent(new BuildSlaveConsoleEvent());
  }

  /** Create a BuildSlaveEvent with an existing ConsoleEvent. */
  public static BuildSlaveEvent createBuildSlaveConsoleEvent(ConsoleEvent event, long timeMillis) {
    BuildSlaveConsoleEvent buildSlaveConsoleEvent = new BuildSlaveConsoleEvent();
    buildSlaveConsoleEvent.setMessage(event.getMessage());

    if (event.getLevel().equals(Level.WARNING)) {
      buildSlaveConsoleEvent.setSeverity(ConsoleEventSeverity.WARNING);
    } else if (event.getLevel().equals(Level.SEVERE)) {
      buildSlaveConsoleEvent.setSeverity(ConsoleEventSeverity.SEVERE);
    } else {
      buildSlaveConsoleEvent.setSeverity(ConsoleEventSeverity.INFO);
    }

    return createBuildSlaveConsoleEvent(timeMillis).setConsoleEvent(buildSlaveConsoleEvent);
  }

  /** Convert a BuildConsoleSlaveEvent into a ConsoleEvent. */
  public static ConsoleEvent createConsoleEvent(BuildSlaveEvent event) {
    Preconditions.checkArgument(event.getEventType() == BuildSlaveEventType.CONSOLE_EVENT);
    Preconditions.checkArgument(event.isSetConsoleEvent());

    BuildSlaveConsoleEvent consoleEvent = event.getConsoleEvent();
    Preconditions.checkState(consoleEvent.isSetMessage());
    Preconditions.checkState(consoleEvent.isSetSeverity());

    String timestampPrefix = DATE_FORMAT.format(new Date(event.getTimestampMillis())) + " ";
    switch (consoleEvent.getSeverity()) {
      case INFO:
        return ConsoleEvent.create(Level.INFO, timestampPrefix + consoleEvent.getMessage());
      case WARNING:
        return ConsoleEvent.create(Level.WARNING, timestampPrefix + consoleEvent.getMessage());
      case SEVERE:
        return ConsoleEvent.create(Level.SEVERE, timestampPrefix + consoleEvent.getMessage());
      default:
        LOG.error(
            String.format(
                "Unsupported type of ConsoleEventSeverity received in BuildSlaveConsoleEvent: [%d]"
                    + "Defaulting to SEVERE.",
                consoleEvent.getSeverity().getValue()));
        return ConsoleEvent.create(Level.SEVERE, timestampPrefix + consoleEvent.getMessage());
    }
  }

  private static Path getLogDirForRunId(String runId, Path logDirectoryPath) {
    return logDirectoryPath.resolve(
        String.format(BuckConstant.DIST_BUILD_SLAVE_TOPLEVEL_LOG_DIR_NAME_TEMPLATE, runId));
  }

  public static Path getStreamLogFilePath(String runId, String streamType, Path logDirectoryPath) {
    return getLogDirForRunId(runId, logDirectoryPath).resolve(String.format("%s.log", streamType));
  }

  public static Path getRemoteBuckLogPath(String runId, Path logDirectoryPath) {
    return getLogDirForRunId(runId, logDirectoryPath)
        .resolve(DIST_BUILD_SLAVE_BUCK_OUT_LOG_DIR_NAME);
  }
}
