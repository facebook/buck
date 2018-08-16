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

package com.facebook.buck.apple.simulator;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/** Utilty class to discover state about Apple simulators installed on the host system. */
public class AppleSimulatorDiscovery {
  private static final Logger LOG = Logger.get(AppleSimulatorDiscovery.class);

  private static final int SIMCTL_LIST_MAX_RETRY_COUNT = 3;

  // Utility class, do not instantiate.
  private AppleSimulatorDiscovery() {}

  public static ImmutableSet<AppleSimulator> discoverAppleSimulators(
      ProcessExecutor processExecutor, Path simctlPath) throws InterruptedException, IOException {
    int count = 0;
    while (true) {
      try {
        return tryDiscoverAppleSimulators(processExecutor, simctlPath);
      } catch (IOException e) {
        count++;
        if (count < SIMCTL_LIST_MAX_RETRY_COUNT) {
          LOG.debug(e, "Failed to run tryDiscoverAppleSimulators()");
        } else {
          LOG.debug(e, "Failed all attempts to run tryDiscoverAppleSimulators()");
          throw e;
        }
      }
    }
  }

  /** Discovers information about Apple simulators installed on the system. */
  private static ImmutableSet<AppleSimulator> tryDiscoverAppleSimulators(
      ProcessExecutor processExecutor, Path simctlPath) throws InterruptedException, IOException {
    LOG.debug("Running xcrun simctl list to get list of simulators");
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of(simctlPath.toString(), "list"))
            .build();
    ProcessExecutor.Result simctlListResult =
        processExecutor.launchAndExecute(processExecutorParams);
    if (simctlListResult.getExitCode() != 0) {
      throw new IOException(simctlListResult.getMessageForUnexpectedResult("simctl list"));
    }
    String output =
        simctlListResult
            .getStdout()
            .orElseThrow(() -> new IllegalStateException("stdout should be captured"));
    ImmutableSet.Builder<AppleSimulator> simulatorsBuilder = ImmutableSet.builder();
    SimctlListOutputParsing.parseOutput(output, simulatorsBuilder);
    ImmutableSet<AppleSimulator> simulators = simulatorsBuilder.build();
    LOG.debug("Discovered simulators: %s", simulators);
    return simulators;
  }

  /**
   * Given a simulators, looks up metadata on the supported architectures and product families for
   * that simulator (if present).
   */
  public static Optional<AppleSimulatorProfile> discoverAppleSimulatorProfile(
      AppleSimulator appleSimulator, Path iphonesimulatorPlatformRoot) throws IOException {
    Path simulatorProfilePlistPath =
        iphonesimulatorPlatformRoot.resolve(
            String.format(
                "Developer/Library/CoreSimulator/Profiles/DeviceTypes/%s.simdevicetype/"
                    + "Contents/Resources/profile.plist",
                appleSimulator.getName()));
    LOG.debug("Parsing simulator profile plist %s", simulatorProfilePlistPath);
    try (InputStream inputStream = Files.newInputStream(simulatorProfilePlistPath)) {
      // This might return Optional.empty() if the input could not be parsed.
      return AppleSimulatorProfileParsing.parseProfilePlistStream(inputStream);
    } catch (FileNotFoundException | NoSuchFileException e) {
      LOG.warn(e, "Could not open simulator profile %s, ignoring", simulatorProfilePlistPath);
      return Optional.empty();
    }
  }
}
