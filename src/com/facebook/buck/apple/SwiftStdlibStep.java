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

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.io.file.MoreFiles;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.SimpleProcessListener;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A step that invokes Apple's tool to scan the binary and copy any needed Swift standard libraries.
 */
class SwiftStdlibStep implements Step {

  private static final Logger LOG = Logger.get(SwiftStdlibStep.class);

  private final Path workingDirectory;
  private final Path temp;
  private final Path sdkPath;
  private final Path destinationDirectory;
  private final Iterable<String> command;
  private final Optional<Supplier<CodeSignIdentity>> codeSignIdentitySupplier;

  public SwiftStdlibStep(
      Path workingDirectory,
      Path temp,
      Path sdkPath,
      Path destinationDirectory,
      Iterable<String> command,
      Optional<Supplier<CodeSignIdentity>> codeSignIdentitySupplier) {
    this.workingDirectory = workingDirectory;
    this.sdkPath = sdkPath;
    this.destinationDirectory = workingDirectory.resolve(destinationDirectory);
    this.temp = workingDirectory.resolve(temp);
    this.command = command;
    this.codeSignIdentitySupplier = codeSignIdentitySupplier;
  }

  @Override
  public String getShortName() {
    return "copy swift standard libs";
  }

  private ProcessExecutorParams makeProcessExecutorParams(ExecutionContext context) {
    ProcessExecutorParams.Builder builder = ProcessExecutorParams.builder();
    builder.setDirectory(workingDirectory.toAbsolutePath());
    builder.setCommand(command);
    builder.addCommand("--destination", temp.toString());
    if (codeSignIdentitySupplier.isPresent()) {
      builder.addCommand(
          "--sign", CodeSignStep.getIdentityArg(codeSignIdentitySupplier.get().get()));
    }

    Map<String, String> environment = new HashMap<>();
    environment.putAll(context.getEnvironment());
    environment.put("SDKROOT", sdkPath.toString());
    builder.setEnvironment(ImmutableMap.copyOf(environment));
    return builder.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    ProcessExecutorParams params = makeProcessExecutorParams(context);
    SimpleProcessListener listener = new SimpleProcessListener();

    // TODO(markwang): parse output as needed
    LOG.debug("%s", command);
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(params, listener);
    int result = executor.waitForProcess(process);
    if (result != 0) {
      LOG.error("Error running %s: %s", getDescription(context), listener.getStderr());
      return StepExecutionResult.of(result);
    }

    // Copy from temp to destinationDirectory if we wrote files.
    if (Files.notExists(temp)) {
      return StepExecutionResults.SUCCESS;
    }
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(temp)) {
      if (dirStream.iterator().hasNext()) {
        Files.createDirectories(destinationDirectory);
        MoreFiles.copyRecursively(temp, destinationDirectory);
      }
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(" ").join(command);
  }
}
