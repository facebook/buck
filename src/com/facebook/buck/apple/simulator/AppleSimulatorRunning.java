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

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.IOException;

import java.nio.file.Path;

import java.util.EnumSet;
import java.util.Set;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to manage starting the iOS simulator as well as installing and
 * running applications inside it.
 */
public class AppleSimulatorRunning {
  private static final Logger LOG = Logger.get(AppleSimulatorRunning.class);

  private static final long SIMULATOR_POLL_TIMEOUT_MILLIS = 100;

  private static final Pattern SIMCTL_LAUNCH_OUTPUT_PATTERN = Pattern.compile("^.*: ([0-9]+)$");

  // Utility class, do not instantiate.
  private AppleSimulatorRunning() { }

  /**
   * Starts up the iOS simulator, blocking the calling thread until the simulator boots
   * or {@code simulatorBootTimeoutMillis} passes, whichever happens first.
   *
   * @return true if the simulator was started, false otherwise.
   */
  public static boolean startSimulator(
      ProcessExecutor processExecutor,
      Path appleDeveloperDirectoryPath,
      final String simulatorUdid,
      long simulatorBootTimeoutMillis) throws IOException, InterruptedException {
    Path iosSimulatorPath = appleDeveloperDirectoryPath.resolve("Applications/iOS Simulator.app");
    ImmutableList<String> command = ImmutableList.of(
        "open",
        "-a",
        iosSimulatorPath.toString(),
        "--args",
        "-CurrentDeviceUDID",
        simulatorUdid);
    LOG.debug("Launching iOS simulator %s: %s", simulatorUdid, command);
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(command)
            .build();
    ProcessExecutor.Result result = processExecutor.launchAndExecute(processExecutorParams);
    if (result.getExitCode() != 0) {
      LOG.error("Error %d running %s", result.getExitCode(), command);
      return false;
    }

    Predicate<AppleSimulator> simulatorBootedPredicate = new Predicate<AppleSimulator>() {
      @Override
      public boolean apply(AppleSimulator simulator) {
        return simulator.getUdid().equals(simulatorUdid) &&
          simulator.getSimulatorState() == AppleSimulatorState.BOOTED;
      }
    };

    long millisWaited = 0;
    boolean simulatorBooted = false;

    while (!simulatorBooted && millisWaited < simulatorBootTimeoutMillis) {
      LOG.debug("Checking if iOS simulator %s has finished booting...", simulatorUdid);
      ImmutableSet<AppleSimulator> runningSimulators =
          AppleSimulatorDiscovery.discoverAppleSimulators(processExecutor);
      if (Iterables.any(runningSimulators, simulatorBootedPredicate)) {
        LOG.debug("iOS simulator %s has finished booting.", simulatorUdid);
        simulatorBooted = true;
      } else {
        LOG.debug(
            "Sleeping for %s ms waiting for simulator to finish booting...",
            SIMULATOR_POLL_TIMEOUT_MILLIS);
        Thread.sleep(SIMULATOR_POLL_TIMEOUT_MILLIS);
        millisWaited += SIMULATOR_POLL_TIMEOUT_MILLIS;
      }
    }

    return simulatorBooted;
  }

  /**
   * Installs a bundle in a previously-started simulator.
   *
   * @return true if the bundle was installed, false otherwise.
   */
  public static boolean installBundleInSimulator(
      ProcessExecutor processExecutor,
      String simulatorUdid,
      Path bundlePath) throws IOException, InterruptedException {
    ImmutableList<String> command = ImmutableList.of(
        "xcrun", "simctl", "install", simulatorUdid, bundlePath.toString());
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(command)
            .build();
    ProcessExecutor.Result result = processExecutor.launchAndExecute(processExecutorParams);
    if (result.getExitCode() != 0) {
      LOG.error("Error %d running %s", result.getExitCode(), command);
      return false;
    }
    return true;
  }

  /**
   * Launches a previously-installed bundle in a started simulator.
   *
   * @return the process ID of the newly-launched process if successful,
   * an absent value otherwise.
   */
  public static Optional<Long> launchInstalledBundleInSimulator(
      ProcessExecutor processExecutor,
      String simulatorUdid,
      String bundleID,
      boolean waitForDebugger) throws IOException, InterruptedException {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder.add("xcrun", "simctl", "launch");
    if (waitForDebugger) {
      commandBuilder.add("-w");
    }
    commandBuilder.add(simulatorUdid, bundleID);
    ImmutableList<String> command = commandBuilder.build();
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(command)
            .build();
    Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
    LOG.debug(
        "Launching bundle ID %s in simulator %s via command %s", bundleID, simulatorUdid, command);
    ProcessExecutor.Result result = processExecutor.launchAndExecute(
        processExecutorParams,
        options,
        /* stdin */ Optional.<String>absent(),
        /* timeOutMs */ Optional.<Long>absent());
    if (result.getExitCode() != 0) {
      LOG.error(
          "Error %d launching bundle %s in simulator %s (command %s)",
          result.getExitCode(),
          bundleID,
          simulatorUdid,
          command);
      return Optional.<Long>absent();
    }
    Preconditions.checkState(result.getStdout().isPresent());
    String trimmedStdout = result.getStdout().get().trim();
    Matcher stdoutMatcher = SIMCTL_LAUNCH_OUTPUT_PATTERN.matcher(trimmedStdout);
    if (!stdoutMatcher.find()) {
      LOG.error("Could not parse output from %s: %s", command, trimmedStdout);
      return Optional.<Long>absent();
    }

    return Optional.of(Long.parseLong(stdoutMatcher.group(1), 10));
  }

}
