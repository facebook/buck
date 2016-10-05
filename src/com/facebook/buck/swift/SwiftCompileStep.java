/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.swift;

import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A step that compiles Swift sources to a single module.
 */
class SwiftCompileStep implements Step {

  private static final Logger LOG = Logger.get(SwiftCompileStep.class);

  private final Path compilerCwd;
  private final ImmutableMap<String, String> compilerEnvironment;
  private final ImmutableList<String> compilerCommand;

  SwiftCompileStep(
      Path compilerCwd,
      Map<String, String> compilerEnvironment,
      Iterable<String> compilerCommand) {
    this.compilerCwd = compilerCwd;
    this.compilerEnvironment = ImmutableMap.copyOf(compilerEnvironment);
    this.compilerCommand = ImmutableList.copyOf(compilerCommand);
  }

  @Override
  public String getShortName() {
    return "swift compile";
  }

  private ProcessExecutorParams makeProcessExecutorParams() {
    return ProcessExecutorParams.builder()
        .setDirectory(compilerCwd.toAbsolutePath())
        .setEnvironment(compilerEnvironment)
        .setCommand(compilerCommand)
        .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
        .setRedirectError(ProcessBuilder.Redirect.PIPE)
        .build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws InterruptedException {
    ProcessExecutor executor = context.getProcessExecutor();
    ProcessExecutorParams params = makeProcessExecutorParams();

    try {
      LOG.debug("%s", compilerCommand);
      ProcessExecutor.LaunchedProcess launchedProcess = executor.launchProcess(params);
      SwiftCompileOutputHandler swiftCompileHandler = new SwiftCompileOutputHandler();
      int result;
      String stdout;
      try (InputStreamReader isr =
               new InputStreamReader(
                   launchedProcess.getInputStream(),
                   StandardCharsets.UTF_8);
           BufferedReader br = new BufferedReader(isr);
           InputStreamReader esr =
               new InputStreamReader(
                   launchedProcess.getErrorStream(),
                   StandardCharsets.UTF_8);
           BufferedReader ebr = new BufferedReader(esr)) {
        SwiftCompileOutputParsing.streamOutputFromReader(ebr, swiftCompileHandler);
        stdout = CharStreams.toString(br).trim();
        result = executor.waitForLaunchedProcess(launchedProcess).getExitCode();
      }

      String accumulatedError = swiftCompileHandler.getAllErrors();
      if (!accumulatedError.isEmpty()) {
        context.getConsole().printErrorText(
            String.format(
                Locale.US,
                "Swift failed compiling with following error %d: %s.\nOutput: %s",
                result,
                accumulatedError,
                stdout));
      }
      return StepExecutionResult.of(result);
    } catch (IOException e) {
      LOG.error(e, "Could not execute command %s", compilerCommand);
      return StepExecutionResult.ERROR;
    }
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(" ").join(compilerCommand);
  }

  private class SwiftCompileOutputHandler
      implements SwiftCompileOutputParsing.SwiftOutputHandler {
    Set<String> errors = new HashSet<>();

    @Override
    public void recordError(String error) {
      errors.add(error);
    }

    private String getAllErrors() {
      return Joiner.on("\n").join(errors);
    }
  }
}
