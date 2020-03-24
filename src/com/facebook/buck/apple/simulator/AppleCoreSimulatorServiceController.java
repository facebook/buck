/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple.simulator;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Launches, queries, and kills Apple's {@code CoreSimulator} and related {@code launchd} services.
 */
public class AppleCoreSimulatorServiceController {
  private static final Logger LOG = Logger.get(AppleCoreSimulatorServiceController.class);

  private static final Pattern LAUNCHCTL_LIST_OUTPUT_PATTERN =
      Pattern.compile("^(\\S+)\\s+(\\S+)\\s+(.*)$");

  private static final Pattern ALL_SIMULATOR_SERVICES_PATTERN =
      Pattern.compile(
          "(?i:com\\.apple\\.iphonesimulator|UIKitApplication|SimulatorBridge|iOS Simulator|"
              + "com\\.apple\\.CoreSimulator)");

  private static final int LAUNCHCTL_EXIT_SUCCESS = 0;
  private static final int LAUNCHCTL_EXIT_NO_SUCH_PROCESS = 3;

  private final ProcessExecutor processExecutor;

  public AppleCoreSimulatorServiceController(ProcessExecutor processExecutor) {
    this.processExecutor = processExecutor;
  }

  private ImmutableSet<String> getMatchingServiceNames(Pattern serviceNamePattern)
      throws IOException, InterruptedException {
    ImmutableList<String> launchctlListCommand = ImmutableList.of("launchctl", "list");
    LOG.debug("Getting list of services with %s", launchctlListCommand);
    ProcessExecutorParams launchctlListParams =
        ProcessExecutorParams.builder().setCommand(launchctlListCommand).build();
    ProcessExecutor.Result launchctlListResult =
        processExecutor.launchAndExecute(launchctlListParams);
    if (launchctlListResult.getExitCode() != LAUNCHCTL_EXIT_SUCCESS) {
      LOG.error(launchctlListResult.getMessageForUnexpectedResult(launchctlListCommand.toString()));
      return ImmutableSet.of();
    }
    String output =
        launchctlListResult
            .getStdout()
            .orElseThrow(() -> new IllegalStateException("stdout should be captured"));
    Iterable<String> lines = MoreStrings.lines(output);
    ImmutableSet.Builder<String> resultBuilder = ImmutableSet.builder();
    for (String line : lines) {
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
    return resultBuilder.build();
  }

  /** Kills any running simulator processes or services. */
  public boolean killSimulatorProcesses() throws IOException, InterruptedException {
    ImmutableSet<String> simulatorServiceNames =
        getMatchingServiceNames(ALL_SIMULATOR_SERVICES_PATTERN);
    LOG.debug("Killing simulator services: %s", simulatorServiceNames);
    boolean result = true;
    for (String simulatorServiceName : simulatorServiceNames) {
      if (!killService(simulatorServiceName)) {
        result = false;
      }
    }
    return result;
  }

  private boolean killService(String serviceName) throws IOException, InterruptedException {
    ImmutableList<String> launchctlRemoveCommand =
        ImmutableList.of("launchctl", "remove", serviceName);
    LOG.debug("Killing simulator process with with %s", launchctlRemoveCommand);
    ProcessExecutorParams launchctlRemoveParams =
        ProcessExecutorParams.builder().setCommand(launchctlRemoveCommand).build();
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
        LOG.error(result.getMessageForUnexpectedResult(launchctlRemoveCommand.toString()));
        return false;
    }
  }
}
