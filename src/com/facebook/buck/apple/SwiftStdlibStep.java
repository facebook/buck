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
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
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
  private final Iterable<String> swiftStdlibToolCommandPrefix;
  private final Iterable<String> lipoCommandPrefix;
  private final Path binaryPathToScan;
  private final Iterable<Path> additionalFoldersToScan;

  private final Optional<Supplier<CodeSignIdentity>> codeSignIdentitySupplier;

  public SwiftStdlibStep(
      Path workingDirectory,
      Path temp,
      Path sdkPath,
      Path destinationDirectory,
      Iterable<String> swiftStdlibToolCommandPrefix,
      Iterable<String> lipoCommandPrefix,
      Path binaryPathToScan,
      Iterable<Path> additionalFoldersToScan,
      Optional<Supplier<CodeSignIdentity>> codeSignIdentitySupplier) {
    this.workingDirectory = workingDirectory;
    this.sdkPath = sdkPath;
    this.destinationDirectory = workingDirectory.resolve(destinationDirectory);
    this.temp = workingDirectory.resolve(temp);
    this.swiftStdlibToolCommandPrefix = swiftStdlibToolCommandPrefix;
    this.lipoCommandPrefix = lipoCommandPrefix;
    this.binaryPathToScan = binaryPathToScan;
    this.additionalFoldersToScan = additionalFoldersToScan;
    this.codeSignIdentitySupplier = codeSignIdentitySupplier;
  }

  @Override
  public String getShortName() {
    return "copy swift standard libs";
  }

  private ImmutableList<String> getSwiftStdlibCommand() {
    ImmutableList.Builder<String> swiftStdlibCommand = ImmutableList.builder();
    swiftStdlibCommand.addAll(swiftStdlibToolCommandPrefix);
    swiftStdlibCommand.add("--scan-executable", binaryPathToScan.toString());
    for (Path p : additionalFoldersToScan) {
      swiftStdlibCommand.add("--scan-folder", p.toString());
    }

    swiftStdlibCommand.add("--destination", temp.toString());
    if (codeSignIdentitySupplier.isPresent()) {
      swiftStdlibCommand.add(
          "--sign", CodeSignStep.getIdentityArg(codeSignIdentitySupplier.get().get()));
    }
    return swiftStdlibCommand.build();
  }

  private ImmutableList<String> getArchsCommand(Path lib) {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    command.addAll(lipoCommandPrefix);
    command.add("-archs", lib.toString());
    return command.build();
  }

  private ImmutableList<String> getLipoExtractCommand(Path lib, String arch) {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    command.addAll(lipoCommandPrefix);
    command.add("-extract", arch, lib.toString());
    command.add("-output", lib.toString() + "." + arch);
    return command.build();
  }

  private ImmutableList<String> getLipoCreateCommand(Path lib, String[] archs) {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    command.addAll(lipoCommandPrefix);
    command.addAll(FluentIterable.from(archs).transform(arch -> lib.toString() + "." + arch));
    command.add("-create", "-output", destinationDirectory.resolve(lib.getFileName()).toString());
    return command.build();
  }

  private ImmutableList<String> getCopyLibCommand(Path lib) {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    command.addAll(lipoCommandPrefix);
    command.add(lib.toString());
    command.add("-create", "-output", destinationDirectory.resolve(lib.getFileName()).toString());
    return command.build();
  }

  private ProcessExecutorParams makeProcessExecutorParams(
      ExecutionContext context, ImmutableList<String> command) {
    ProcessExecutorParams.Builder builder = ProcessExecutorParams.builder();
    builder.setDirectory(workingDirectory.toAbsolutePath());
    builder.setCommand(command);

    Map<String, String> environment = new HashMap<>(context.getEnvironment());
    environment.put("SDKROOT", sdkPath.toString());
    builder.setEnvironment(ImmutableMap.copyOf(environment));
    return builder.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ProcessExecutor executor = new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutorParams params = makeProcessExecutorParams(context, getSwiftStdlibCommand());

    LOG.debug("%s", params.getCommand());
    Result result = executor.launchAndExecute(params);
    if (result.getExitCode() != 0) {
      LOG.error("Error running %s: %s", params.getCommand(), result.getStderr());
      return StepExecutionResult.of(result);
    }

    // Copy from temp to destinationDirectory if we wrote files.
    if (Files.notExists(temp)) {
      return StepExecutionResults.SUCCESS;
    }
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(temp)) {
      ImmutableList<Path> libs =
          FluentIterable.from(dirStream).filter(Files::isRegularFile).toList();

      if (libs.size() == 0) {
        return StepExecutionResults.SUCCESS;
      }

      Files.createDirectories(destinationDirectory);

      // Get needed archs from the binary.
      params = makeProcessExecutorParams(context, getArchsCommand(binaryPathToScan));
      LOG.debug("%s", params.getCommand());
      result = executor.launchAndExecute(params);
      if (result.getExitCode() != 0) {
        LOG.error("Error running %s: %s", params.getCommand(), result.getStderr());
        return StepExecutionResult.of(result);
      }

      String[] archs = result.getStdout().orElse("").trim().split(" ");
      LOG.debug("Binary archs are %s", archs.toString());

      if (archs.length < 1) {
        LOG.error("Unable to get binary archs");
        return StepExecutionResults.ERROR;
      }

      // For each library, copy only the needed arch slices to the destination.
      // This is done by:
      // 1. Extract the needed slices into the temp path
      // 2. Combine them into a new library in the destination path
      ImmutableList.Builder<ProcessExecutorParams> lipoCommands = ImmutableList.builder();
      for (Path lib : libs) {
        // For each lib, check to see if it's a universal binary and can actually extract slices.
        // Attempting to extract from a binary with only one arch will error,
        // so we just copy it instead.
        params = makeProcessExecutorParams(context, getArchsCommand(lib));
        result = executor.launchAndExecute(params);
        if (result.getExitCode() != 0) {
          LOG.error("Error running %s: %s", params.getCommand(), result.getStderr());
          return StepExecutionResult.of(result);
        }

        String[] libArchs = result.getStdout().orElse("").trim().split(" ");
        LOG.debug("Library %s archs are %s", lib, libArchs.toString());

        if (libArchs.length < 1) {
          LOG.error("Unable to get binary archs");
          return StepExecutionResults.ERROR;
        }

        boolean shouldExtractArch = (libArchs.length > 1);

        if (shouldExtractArch) {
          for (String arch : archs) {
            lipoCommands.add(makeProcessExecutorParams(context, getLipoExtractCommand(lib, arch)));
          }
          lipoCommands.add(makeProcessExecutorParams(context, getLipoCreateCommand(lib, archs)));
        } else {
          lipoCommands.add(makeProcessExecutorParams(context, getCopyLibCommand(lib)));
        }
      }

      // Actually run the lipo commands
      for (ProcessExecutorParams p : lipoCommands.build()) {
        LOG.debug("%s", p.getCommand());
        result = executor.launchAndExecute(p);
        if (result.getExitCode() != 0) {
          LOG.error("Error running %s: %s", p.getCommand(), result.getStderr());
          return StepExecutionResult.of(result);
        }
      }
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(' ').join(getSwiftStdlibCommand());
  }
}
