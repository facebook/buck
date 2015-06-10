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
import com.facebook.buck.util.UserIdFetcher;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.nio.charset.StandardCharsets;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Launches, queries, and kills Apple's {@code CoreSimulator} and related {@code launchd} services.
 */
public class AppleCoreSimulatorServiceController {
  private static final Logger LOG = Logger.get(AppleCoreSimulatorServiceController.class);

  private static final Pattern LAUNCHCTL_LIST_OUTPUT_PATTERN = Pattern.compile(
      "^(\\S+)\\s+(\\S+)\\s+(.*)$");
  private static final Pattern LAUNCHCTL_PRINT_PATH_PATTERN = Pattern.compile(
      "^\\s*path\\s*=\\s*(.*)$");
  private static final Pattern CORE_SIMULATOR_SERVICE_PATTERN = Pattern.compile(
      "(?i:com\\.apple\\.CoreSimulator\\.CoreSimulatorService)");

  private static final Pattern ALL_SIMULATOR_SERVICES_PATTERN = Pattern.compile(
      "(?i:com\\.apple\\.iphonesimulator|UIKitApplication|SimulatorBridge|iOS Simulator|" +
      "com\\.apple\\.CoreSimulator)");

  private static final int LAUNCHCTL_EXIT_SUCCESS = 0;
  private static final int LAUNCHCTL_EXIT_NO_SUCH_PROCESS = 3;

  private final ProcessExecutor processExecutor;

  public AppleCoreSimulatorServiceController(ProcessExecutor processExecutor) {
    this.processExecutor = processExecutor;
  }

  /**
   * Returns the path on disk to the running Core Simulator service, if any.
   * Returns {@code Optional.absent()} unless exactly one Core Simulator service is running.
   */
  public Optional<Path> getCoreSimulatorServicePath(UserIdFetcher userIdFetcher)
      throws IOException, InterruptedException {
    ImmutableSet<String> coreSimulatorServiceNames = getMatchingServiceNames(
        CORE_SIMULATOR_SERVICE_PATTERN);

    if (coreSimulatorServiceNames.size() != 1) {
      LOG.debug("Could not get core simulator service name (got %s)", coreSimulatorServiceNames);
      return Optional.absent();
    }

    String coreSimulatorServiceName = Iterables.getOnlyElement(coreSimulatorServiceNames);

    ImmutableList<String> launchctlPrintCommand = ImmutableList.of(
        "launchctl",
        "print",
        String.format("user/%d/%s", userIdFetcher.getUserId(), coreSimulatorServiceName));

    LOG.debug(
        "Getting status of service %s with %s",
        coreSimulatorServiceName,
        launchctlPrintCommand);
    ProcessExecutorParams launchctlPrintParams =
        ProcessExecutorParams.builder()
            .setCommand(launchctlPrintCommand)
            .build();
    ProcessExecutor.LaunchedProcess launchctlPrintProcess = processExecutor.launchProcess(
        launchctlPrintParams);
    Optional<Path> result = Optional.absent();
    try (InputStreamReader stdoutReader = new InputStreamReader(
             launchctlPrintProcess.getInputStream(),
             StandardCharsets.UTF_8);
         BufferedReader bufferedStdoutReader = new BufferedReader(stdoutReader)) {
      String line;
      while ((line = bufferedStdoutReader.readLine()) != null) {
        Matcher matcher = LAUNCHCTL_PRINT_PATH_PATTERN.matcher(line);
        if (matcher.matches()) {
          String path = matcher.group(1);
          LOG.debug("Found path of service %s: %s", coreSimulatorServiceName, path);
          result = Optional.of(Paths.get(path));
          break;
        }
      }
    } finally {
      processExecutor.destroyLaunchedProcess(launchctlPrintProcess);
      processExecutor.waitForLaunchedProcess(launchctlPrintProcess);
    }

    return result;
  }

  private ImmutableSet<String> getMatchingServiceNames(Pattern serviceNamePattern)
      throws IOException, InterruptedException {
    ImmutableList<String> launchctlListCommand = ImmutableList.of(
        "launchctl",
        "list");
    LOG.debug(
        "Getting list of services with %s",
        launchctlListCommand);
    ProcessExecutorParams launchctlListParams =
        ProcessExecutorParams.builder()
            .setCommand(launchctlListCommand)
            .build();
    ProcessExecutor.LaunchedProcess launchctlListProcess = processExecutor.launchProcess(
        launchctlListParams);
    ImmutableSet.Builder<String> resultBuilder = ImmutableSet.builder();
    try (InputStreamReader stdoutReader = new InputStreamReader(
             launchctlListProcess.getInputStream(),
             StandardCharsets.UTF_8);
         BufferedReader bufferedStdoutReader = new BufferedReader(stdoutReader)) {
      String line;
      while ((line = bufferedStdoutReader.readLine()) != null) {
        Matcher launchctlListOutputMatcher = LAUNCHCTL_LIST_OUTPUT_PATTERN.matcher(line);
        if (launchctlListOutputMatcher.matches()) {
          String serviceName = launchctlListOutputMatcher.group(3);
          Matcher serviceNameMatcher = serviceNamePattern.matcher(serviceName);
          if (serviceNameMatcher.find()) {
            LOG.debug("Found matching service name: %s", serviceName);
            resultBuilder.add(serviceName);
          }
        }
      }
    } finally {
      processExecutor.destroyLaunchedProcess(launchctlListProcess);
      processExecutor.waitForLaunchedProcess(launchctlListProcess);
    }

    return resultBuilder.build();
  }

  /**
   * Kills any running simulator processes or services.
   */
  public boolean killSimulatorProcesses() throws IOException, InterruptedException {
    ImmutableSet<String> simulatorServiceNames = getMatchingServiceNames(
        ALL_SIMULATOR_SERVICES_PATTERN);
    LOG.debug("Killing simulator services: %s", simulatorServiceNames);
    boolean result = true;
    for (String simulatorServiceName : simulatorServiceNames) {
      if (!killService(simulatorServiceName)) {
        result = false;
      }
    }
    return result;
  }

  private boolean killService(String serviceName)
      throws IOException, InterruptedException {
    ImmutableList<String> launchctlRemoveCommand = ImmutableList.of(
        "launchctl",
        "remove",
        serviceName);
    LOG.debug("Killing simulator process with with %s", launchctlRemoveCommand);
    ProcessExecutorParams launchctlRemoveParams =
        ProcessExecutorParams.builder()
            .setCommand(launchctlRemoveCommand)
            .build();
    ProcessExecutor.Result result = processExecutor.launchAndExecute(launchctlRemoveParams);
    int launchctlExitCode = result.getExitCode();
    LOG.debug("Command %s exited with code %d", launchctlRemoveParams, launchctlExitCode);
    switch (launchctlExitCode) {
      case LAUNCHCTL_EXIT_SUCCESS:
      case LAUNCHCTL_EXIT_NO_SUCH_PROCESS:
        // The process could have exited by itself or already been terminated by the time
        // we told it to die, so we have to treat "no such process" as success.
        return true;
      default:
        LOG.error("Error %d running %s", result.getExitCode(), launchctlRemoveCommand);
        return false;
    }
  }
}
