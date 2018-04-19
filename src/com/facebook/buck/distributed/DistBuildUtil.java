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

import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.BuildSlaveConsoleEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventType;
import com.facebook.buck.distributed.thrift.ConsoleEventSeverity;
import com.facebook.buck.distributed.thrift.MinionRequirement;
import com.facebook.buck.distributed.thrift.MinionRequirements;
import com.facebook.buck.distributed.thrift.MinionType;
import com.facebook.buck.distributed.thrift.SchedulingEnvironmentType;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;

public class DistBuildUtil {
  private static final Logger LOG = Logger.get(ConsoleEvent.class);
  private static final DateFormat DATE_FORMAT = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS]");

  private DistBuildUtil() {}

  /** @return MinionRequirements object for the given parameters. */
  public static MinionRequirements createMinionRequirements(
      BuildMode buildMode,
      SchedulingEnvironmentType environmentType,
      int totalMinionCount,
      int lowSpecMinionCount) {
    MinionRequirements minionRequirements = new MinionRequirements();

    if (buildMode != BuildMode.DISTRIBUTED_BUILD_WITH_LOCAL_COORDINATOR
        && buildMode != BuildMode.DISTRIBUTED_BUILD_WITH_REMOTE_COORDINATOR
        && buildMode != BuildMode.LOCAL_BUILD_WITH_REMOTE_EXECUTION) {
      return minionRequirements; // BuildMode does not support minion requirements
    }

    Preconditions.checkArgument(
        totalMinionCount > 0,
        "The number of minions must be greater than zero. Value [%d] found.",
        totalMinionCount);

    List<MinionRequirement> requirements = new ArrayList<>();
    int standardSpecMinionCount = totalMinionCount;
    if (environmentType == SchedulingEnvironmentType.MIXED_HARDWARE && lowSpecMinionCount > 0) {
      Preconditions.checkArgument(
          lowSpecMinionCount < totalMinionCount,
          String.format(
              "Number of low spec minions [%d] must be less than total number of minions [%d]",
              lowSpecMinionCount, totalMinionCount));

      standardSpecMinionCount -= lowSpecMinionCount;

      MinionRequirement lowSpecRequirement = new MinionRequirement();
      lowSpecRequirement.setMinionType(MinionType.LOW_SPEC);
      lowSpecRequirement.setRequiredCount(lowSpecMinionCount);

      requirements.add(lowSpecRequirement);
    }

    MinionRequirement standardSpecRequirement = new MinionRequirement();
    standardSpecRequirement.setMinionType(MinionType.STANDARD_SPEC);
    standardSpecRequirement.setRequiredCount(standardSpecMinionCount);
    requirements.add(standardSpecRequirement);

    minionRequirements.setRequirements(requirements);
    return minionRequirements;
  }

  /** @return Total number of minions specified in requirements */
  public static int countMinions(MinionRequirements minionRequirements) {
    if (!minionRequirements.isSetRequirements()) {
      return 0;
    }

    return minionRequirements
        .getRequirements()
        .stream()
        .mapToInt(req -> req.getRequiredCount())
        .sum();
  }

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
