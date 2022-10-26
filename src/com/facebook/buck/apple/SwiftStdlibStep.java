/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
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
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A step that invokes Apple's tool to scan the binary and copy any needed Swift standard libraries.
 */
class SwiftStdlibStep implements Step {

  private static final Logger LOG = Logger.get(SwiftStdlibStep.class);

  private final AbsPath workingDirectory;
  private final AbsPath temp;
  private final Path sdkPath;
  private final AbsPath destinationDirectory;
  private final Iterable<String> swiftStdlibToolCommandPrefix;
  private final Path binaryPathToScan;
  private final Iterable<Path> additionalFoldersToScan;
  private final boolean withDownwardApi;

  private final Optional<Supplier<CodeSignIdentity>> codeSignIdentitySupplier;

  public SwiftStdlibStep(
      AbsPath workingDirectory,
      Path temp,
      Path sdkPath,
      Path destinationDirectory,
      Iterable<String> swiftStdlibToolCommandPrefix,
      Path binaryPathToScan,
      Iterable<Path> additionalFoldersToScan,
      Optional<Supplier<CodeSignIdentity>> codeSignIdentitySupplier,
      boolean withDownwardApi) {
    this.workingDirectory = workingDirectory;
    this.sdkPath = sdkPath;
    this.withDownwardApi = withDownwardApi;
    this.destinationDirectory = workingDirectory.resolve(destinationDirectory);
    this.temp = workingDirectory.resolve(temp);
    this.swiftStdlibToolCommandPrefix = swiftStdlibToolCommandPrefix;
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

  private ProcessExecutorParams makeProcessExecutorParams(
      StepExecutionContext context, ImmutableList<String> command) {
    ProcessExecutorParams.Builder builder = ProcessExecutorParams.builder();
    builder.setDirectory(workingDirectory.getPath());
    builder.setCommand(command);

    Map<String, String> environment = new HashMap<>(context.getEnvironment());
    environment.put("SDKROOT", sdkPath.toString());
    builder.setEnvironment(ImmutableMap.copyOf(environment));
    return builder.build();
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    ProcessExecutor executor = new DefaultProcessExecutor(Console.createNullConsole());
    if (withDownwardApi) {
      executor = context.getDownwardApiProcessExecutor(executor);
    }

    ProcessExecutorParams params = makeProcessExecutorParams(context, getSwiftStdlibCommand());

    LOG.debug("%s", params.getCommand());
    Result result = executor.launchAndExecute(params);
    if (result.getExitCode() != 0) {
      LOG.error("Error running %s: %s", params.getCommand(), result.getStderr());
      return StepExecutionResult.of(result);
    }

    // Copy from temp to destinationDirectory if we wrote files.
    if (Files.notExists(temp.getPath())) {
      return StepExecutionResults.SUCCESS;
    }
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(temp.getPath())) {
      ImmutableList<Path> libs =
          FluentIterable.from(dirStream).filter(Files::isRegularFile).toList();

      if (libs.size() == 0) {
        return StepExecutionResults.SUCCESS;
      }

      Files.createDirectories(destinationDirectory.getPath());
      return copyUnmodifiedRuntimeDylibs(libs);
    }
  }

  private StepExecutionResult copyUnmodifiedRuntimeDylibs(ImmutableList<Path> libs)
      throws IOException {
    Path destDir = destinationDirectory.getPath();
    for (Path libPath : libs) {
      Path destLibpath = destDir.resolve(libPath.getFileName());
      Files.move(libPath, destLibpath, StandardCopyOption.REPLACE_EXISTING);
    }

    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return Joiner.on(' ').join(getSwiftStdlibCommand());
  }
}
