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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.SimpleProcessListener;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** A step that compiles Swift sources to a single module. */
class SwiftCompileStep implements Step {

  private static final Logger LOG = Logger.get(SwiftCompileStep.class);

  private final Path compilerCwd;
  private final ImmutableMap<String, String> compilerEnvironment;
  private final ImmutableList<String> compilerCommand;

  SwiftCompileStep(
      Path compilerCwd, Map<String, String> compilerEnvironment, Iterable<String> compilerCommand) {
    this.compilerCwd = compilerCwd;
    this.compilerEnvironment = ImmutableMap.copyOf(compilerEnvironment);
    this.compilerCommand = ImmutableList.copyOf(compilerCommand);
  }

  @Override
  public String getShortName() {
    return "swift compile";
  }

  private ProcessExecutorParams makeProcessExecutorParams() {
    ProcessExecutorParams.Builder builder = ProcessExecutorParams.builder();
    builder.setDirectory(compilerCwd.toAbsolutePath());
    builder.setEnvironment(compilerEnvironment);
    builder.setCommand(compilerCommand);
    return builder.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    ProcessExecutorParams params = makeProcessExecutorParams();
    SimpleProcessListener listener = new SimpleProcessListener();

    // TODO(markwang): parse the output, print build failure errors, etc.
    LOG.debug("%s", compilerCommand);
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(params, listener);
    int result = executor.waitForProcess(process);
    if (result != 0) {
      LOG.error("Error running %s: %s", getDescription(context), listener.getStderr());
    }
    return StepExecutionResult.of(result, Optional.of(listener.getStderr()));
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(" ").join(compilerCommand);
  }
}
