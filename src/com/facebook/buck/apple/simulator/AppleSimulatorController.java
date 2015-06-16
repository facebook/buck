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

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.IOException;

import java.nio.file.Path;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to manage starting the iOS simulator as well as installing and
 * running applications inside it.
 */
public class AppleSimulatorController {
  private static final Logger LOG = Logger.get(AppleSimulatorController.class);

  private static final long SIMULATOR_POLL_TIMEOUT_MILLIS = 100;

  private static final Pattern SIMCTL_LAUNCH_OUTPUT_PATTERN = Pattern.compile("^.*: ([0-9]+)$");

  private final ProcessExecutor processExecutor;
  private final Path simctlPath;
  private final Path iosSimulatorPath;

  public enum LaunchBehavior {
      DO_NOT_WAIT_FOR_DEBUGGER,
      WAIT_FOR_DEBUGGER;
  }

  public AppleSimulatorController(
      ProcessExecutor processExecutor,
      Path simctlPath,
      Path iosSimulatorPath) {
    this.processExecutor = processExecutor;
    this.simctlPath = simctlPath;
    this.iosSimulatorPath = iosSimulatorPath;
  }

  /**
   * Starts up the iOS simulator, blocking the calling thread until the simulator boots
   * or {@code timeoutMillis} passes, whichever happens first.
   *
   * Call {@link #canStartSimulator(String)} before invoking this method to ensure
   * the simulator can be started.
   *
   * @return The number of milliseconds waited if the simulator booted successfully,
   * {@code Optional.absent()} otherwise.
   */
  public Optional<Long> startSimulator(
      String simulatorUdid,
      long timeoutMillis) throws IOException, InterruptedException {
    if (!canStartSimulator(simulatorUdid)) {
      LOG.warn("Cannot start simulator with UDID %s", simulatorUdid);
      return Optional.absent();
    }

    // Even if the simulator is already running, we'll run this to bring it to the front.
    if (!launchSimulatorWithUdid(iosSimulatorPath, simulatorUdid)) {
      return Optional.absent();
    }

    Optional<Long> bootMillisWaited = waitForSimulatorToBoot(timeoutMillis, simulatorUdid);

    if (!bootMillisWaited.isPresent()) {
      LOG.warn("Simulator %s did not boot up within %d millis", simulatorUdid, timeoutMillis);
      return Optional.absent();
    }

    return bootMillisWaited;
  }

  public boolean canStartSimulator(String simulatorUdid) throws IOException, InterruptedException {
    ImmutableSet<String> bootedSimulatorDeviceUdids = getBootedSimulatorDeviceUdids(
        processExecutor);
    if (bootedSimulatorDeviceUdids.size() == 0) {
      return true;
    } else if (bootedSimulatorDeviceUdids.size() > 1) {
      LOG.debug(
          "Multiple simulators booted (%s), cannot start simulator.",
          bootedSimulatorDeviceUdids);
      return false;
    } else if (!bootedSimulatorDeviceUdids.contains(simulatorUdid)) {
      LOG.debug(
          "Booted simulator (%s) does not match desired (%s), cannot start simulator.",
          Iterables.getOnlyElement(bootedSimulatorDeviceUdids),
          simulatorUdid);
      return false;
    } else {
      return true;
    }
  }

  private boolean launchSimulatorWithUdid(
      Path iosSimulatorPath,
      String simulatorUdid) throws IOException, InterruptedException {
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
    return true;
  }

  private ImmutableSet<String> getBootedSimulatorDeviceUdids(
      ProcessExecutor processExecutor) throws IOException, InterruptedException {
    ImmutableSet.Builder<String> bootedSimulatorUdids = ImmutableSet.builder();
    for (AppleSimulator sim : AppleSimulatorDiscovery.discoverAppleSimulators(
             processExecutor,
             simctlPath)) {
      if (sim.getSimulatorState() == AppleSimulatorState.BOOTED) {
        bootedSimulatorUdids.add(sim.getUdid());
      }
    }
    return bootedSimulatorUdids.build();
  }

  /**
   * Waits up to {@code timeoutMillis} for all simulators to shut down.
   *
   * @return The number of milliseconds waited if all simulators have shut down,
   * {@code Optional.absent()} otherwise.
   */
  public Optional<Long> waitForSimulatorsToShutdown(long timeoutMillis)
      throws IOException, InterruptedException {
    return waitForSimulatorState(
        timeoutMillis,
        "all simulators shutdown",
        new Predicate<ImmutableSet<AppleSimulator>>() {
          @Override
          public boolean apply(ImmutableSet<AppleSimulator> simulators) {
            for (AppleSimulator simulator : simulators) {
              if (simulator.getSimulatorState() != AppleSimulatorState.SHUTDOWN) {
                return false;
              }
            }
            return true;
          }
        });
  }

  /**
   * Waits up to {@code timeoutMillis} for the specified simulator to boot.
   *
   * @return The number of milliseconds waited if the specified simulator booted,
   * {@code Optional.absent()} otherwise.
   */
  public Optional<Long> waitForSimulatorToBoot(
      long timeoutMillis,
      final String simulatorUdid) throws IOException, InterruptedException {
    return waitForSimulatorState(
        timeoutMillis,
        String.format("simulator %s booted", simulatorUdid),
        new Predicate<ImmutableSet<AppleSimulator>>() {
          @Override
          public boolean apply(ImmutableSet<AppleSimulator> simulators) {
            for (AppleSimulator simulator : simulators) {
              if (simulator.getUdid().equals(simulatorUdid) &&
                  simulator.getSimulatorState().equals(AppleSimulatorState.BOOTED)) {
                return true;
              }
            }
            return false;
          }
        });
  }

  private Optional<Long> waitForSimulatorState(
      long timeoutMillis,
      String description,
      Predicate<ImmutableSet<AppleSimulator>> predicate) throws IOException, InterruptedException {
    boolean stateReached = false;
    long millisWaited = 0;
    while (!stateReached && millisWaited < timeoutMillis) {
      LOG.debug("Checking if simulator state %s reached..", description);
      if (predicate.apply(AppleSimulatorDiscovery.discoverAppleSimulators(
                              processExecutor,
                              simctlPath))) {
        LOG.debug("Simulator state %s reached.", description);
        stateReached = true;
      } else {
        LOG.debug(
             "Sleeping for %d ms waiting for simulator to reach state %s...",
             SIMULATOR_POLL_TIMEOUT_MILLIS,
             description);
        Thread.sleep(SIMULATOR_POLL_TIMEOUT_MILLIS);
        millisWaited += SIMULATOR_POLL_TIMEOUT_MILLIS;
      }
    }

    if (stateReached) {
      return Optional.of(millisWaited);
    } else {
      LOG.debug("Simulator did not reach state %s within %d ms.", description, timeoutMillis);
      return Optional.absent();
    }
  }

  /**
   * Installs a bundle in a previously-started simulator.
   *
   * @return true if the bundle was installed, false otherwise.
   */
  public boolean installBundleInSimulator(
      String simulatorUdid,
      Path bundlePath) throws IOException, InterruptedException {
    ImmutableList<String> command = ImmutableList.of(
        simctlPath.toString(), "install", simulatorUdid, bundlePath.toString());
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
  public Optional<Long> launchInstalledBundleInSimulator(
      String simulatorUdid,
      String bundleID,
      LaunchBehavior launchBehavior,
      List<String> args) throws IOException, InterruptedException {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder.add(simctlPath.toString(), "launch");
    if (launchBehavior == LaunchBehavior.WAIT_FOR_DEBUGGER) {
      commandBuilder.add("-w");
    }
    commandBuilder.add(simulatorUdid, bundleID);
    commandBuilder.addAll(args);
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
        /* timeOutMs */ Optional.<Long>absent(),
        /* timeOutHandler */ Optional.<Function<Process, Void>>absent());
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
