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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.BuildSlaveConsoleEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventType;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.ConsoleEventSeverity;
import com.facebook.buck.distributed.thrift.MinionRequirement;
import com.facebook.buck.distributed.thrift.MinionRequirements;
import com.facebook.buck.distributed.thrift.MinionType;
import com.facebook.buck.distributed.thrift.SchedulingEnvironmentType;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.support.cli.config.AliasConfig;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DistBuildUtil {

  private static final Logger LOG = Logger.get(ConsoleEvent.class);
  private static final ThreadLocal<DateFormat> DATE_FORMAT =
      ThreadLocal.withInitial(() -> new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS]"));

  // Converts '//project/subdir:target' or '//project:target' into '//project'
  private static final String TARGET_TO_PROJECT_REGREX = "(//.*?)[/|:].*";

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
      return minionRequirements; // BuckBuildType does not support minion requirements
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

    return minionRequirements.getRequirements().stream()
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

    String timestampPrefix = DATE_FORMAT.get().format(new Date(event.getTimestampMillis())) + " ";
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

  /** Checks whether the given target command line arguments match the Stampede project whitelist */
  public static boolean doTargetsMatchProjectWhitelist(
      List<String> commandArgs, ImmutableSet<String> projectWhitelist, BuckConfig buckConfig) {
    ImmutableSet.Builder<String> buildTargets = new ImmutableSet.Builder<>();
    for (String commandArg : commandArgs) {
      ImmutableSet<String> buildTargetForAliasAsString =
          AliasConfig.from(buckConfig).getBuildTargetForAliasAsString(commandArg);
      if (buildTargetForAliasAsString.size() > 0) {
        buildTargets.addAll(buildTargetForAliasAsString);
      } else {
        // Target was not an alias
        if (!commandArg.startsWith("//")) {
          commandArg = "//" + commandArg;
        }

        buildTargets.add(commandArg);
      }
    }

    return doTargetsMatchProjectWhitelist(buildTargets.build(), projectWhitelist);
  }

  /** Checks whether the given targets match the Stampede project whitelist */
  protected static boolean doTargetsMatchProjectWhitelist(
      ImmutableSet<String> buildTargets, ImmutableSet<String> projectWhitelist) {
    if (buildTargets.isEmpty()) {
      return false;
    }
    boolean mismatchFound = false;
    for (String buildTarget : buildTargets) {
      Pattern pattern = Pattern.compile(TARGET_TO_PROJECT_REGREX);
      Matcher matcher = pattern.matcher(buildTarget);

      if (matcher.find()) {
        // Check the project for the given target is whitelisted
        String projectForTarget = matcher.group(1);
        mismatchFound = !projectWhitelist.contains(projectForTarget);
      } else {
        mismatchFound = true;
      }

      if (mismatchFound) {
        break;
      }
    }

    return !mismatchFound;
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

  /** From buildslave runId generates id for a minion running on this buildslave host. */
  public static String generateMinionId(BuildSlaveRunId buildSlaveRunId) {
    Preconditions.checkState(!buildSlaveRunId.getId().isEmpty());

    String hostname = "Unknown";
    try {
      InetAddress addr;
      addr = InetAddress.getLocalHost();
      hostname = addr.getHostName();
    } catch (UnknownHostException ex) {
      LOG.warn("Hostname can not be resolved - it will not be included in Minion ID.");
    }

    return String.format("minion:%s:%s", hostname, buildSlaveRunId);
  }
}
