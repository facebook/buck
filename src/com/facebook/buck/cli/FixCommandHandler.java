/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.cli;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.doctor.BuildLogHelper;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.support.fix.BuckFixSpec;
import com.facebook.buck.support.fix.BuckFixSpecParser;
import com.facebook.buck.support.fix.BuckRunSpec;
import com.facebook.buck.support.fix.FixBuckConfig;
import com.facebook.buck.support.fix.ImmutableBuckRunSpec;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Encapsulates the logic around running `buck fix`.
 *
 * <p>This allows us to run it both from {@link FixCommand}, and automatically from things like
 * {@link BuildCommand}
 */
public class FixCommandHandler {

  /** Simple wrapper class signalling that something failed while trying to run BuckFix */
  class FixCommandHandlerException extends HumanReadableException {

    public FixCommandHandlerException(String humanReadableFormatString, Object... args) {
      super(humanReadableFormatString, args);
    }

    public FixCommandHandlerException(String humanReadableErrorMessage) {
      super(humanReadableErrorMessage);
    }
  }

  private final ProjectFilesystem filesystem;
  private final Console console;
  private final ImmutableMap<String, String> environment;
  private final FixBuckConfig fixConfig;
  private final Optional<Path> commandArgsPath;
  private final Optional<Path> fixSpecPath;

  FixCommandHandler(
      ProjectFilesystem filesystem,
      Console console,
      ImmutableMap<String, String> environment,
      FixBuckConfig fixConfig,
      @Nullable String commandArgsFile,
      @Nullable String fixSpecFile) {
    this.filesystem = filesystem;
    this.console = console;
    this.environment = environment;
    this.fixConfig = fixConfig;
    this.commandArgsPath = Optional.ofNullable(commandArgsFile).map(Paths::get);
    this.fixSpecPath = Optional.ofNullable(fixSpecFile).map(Paths::get);
  }

  private void validateCanRunFixScript() throws FixCommandHandlerException {
    if (!commandArgsPath.isPresent()) {
      throw new IllegalStateException(
          "--command-args-file was not provided by the buck wrapper script");
    }

    if (!fixSpecPath.isPresent()) {
      throw new IllegalStateException(
          "--fix-spec-file was not provided by the buck wrapper script");
    }

    if (!fixConfig.getFixScript().isPresent()) {
      throw new FixCommandHandlerException("`buck fix` requires buck.fix_script to be set");
    }

    if (!fixConfig.getFixScriptContact().isPresent()) {
      throw new FixCommandHandlerException(
          "`buck fix` requires a contact for any configured scripts");
    }
  }

  /**
   * Print the user-configured message that should be printed whenever `buck fix` is run.
   *
   * <p>This is often something like "Now running {command}. Contact {contact} with any problems"
   */
  private void printFixScriptMessage(Console console, ImmutableList<String> fixScript) {
    String message =
        fixConfig.getInterpolatedFixScriptMessage(fixScript, fixConfig.getFixScriptContact().get());
    console.getStdErr().getRawStream().println(console.getAnsi().asWarningText(message));
  }

  /**
   * Print instructions telling users how to manually invoke buck fix with the correct build id
   *
   * <p>This is only printed when buck fix is run automatically. Otherwise users are already running
   * `buck fix` and should know how to run `buck fix`.
   */
  private void printManualInvocationInstructions(Console console, BuildId buildId) {
    String message =
        String.format(
            "Running `buck fix`. Invoke this manually with `buck fix --build-id %s`", buildId);
    console.getStdErr().getRawStream().println(console.getAnsi().asWarningText(message));
  }

  void runWithBuildId(BuildId buildId, boolean manuallyInvoked)
      throws FixCommandHandlerException, IOException {
    BuildLogHelper buildLogHelper = new BuildLogHelper(filesystem);
    Either<BuckFixSpec, BuckFixSpecParser.FixSpecFailure> fixSpec =
        BuckFixSpecParser.parseFromBuildId(buildLogHelper, fixConfig, buildId, manuallyInvoked);
    if (fixSpec.isRight()) {
      throw new FixCommandHandlerException(
          "Error fetching logs for build %s: %s", buildId, fixSpec.getRight().humanReadableError());
    }

    run(fixSpec.getLeft());
  }

  void runWithBuildIdWithExitCode(BuildId buildId, ExitCode exitCode, boolean manuallyInvoked)
      throws FixCommandHandlerException, IOException {
    BuildLogHelper buildLogHelper = new BuildLogHelper(filesystem);
    Either<BuckFixSpec, BuckFixSpecParser.FixSpecFailure> fixSpec =
        BuckFixSpecParser.parseFromBuildIdWithExitCode(
            buildLogHelper, fixConfig, buildId, exitCode, manuallyInvoked);
    if (fixSpec.isRight()) {
      throw new FixCommandHandlerException(
          "Error fetching logs for build %s: %s", buildId, fixSpec.getRight().humanReadableError());
    }

    run(fixSpec.getLeft());
  }

  void runWithLatestCommand(boolean manuallyInvoked)
      throws FixCommandHandlerException, IOException {
    BuildLogHelper buildLogHelper = new BuildLogHelper(filesystem);
    Either<BuckFixSpec, BuckFixSpecParser.FixSpecFailure> fixSpec =
        BuckFixSpecParser.parseLastCommand(buildLogHelper, fixConfig, manuallyInvoked);
    if (fixSpec.isRight()) {
      throw new FixCommandHandlerException(
          "Error finding a valid previous run to get 'fix' information from: %s",
          fixSpec.getRight().humanReadableError());
    }

    run(fixSpec.getLeft());
  }

  void run(BuckFixSpec fixSpec) throws FixCommandHandlerException, IOException {
    validateCanRunFixScript();

    Path runPath = commandArgsPath.get();
    Path fixPath = fixSpecPath.get();
    Path repositoryRoot = filesystem.getRootPath();

    ImmutableList<String> fixScript =
        fixConfig.getInterpolatedFixScript(fixConfig.getFixScript().get(), repositoryRoot, fixPath);

    BuckRunSpec runSpec = new ImmutableBuckRunSpec(fixScript, environment, repositoryRoot, true);

    // If the fix command was invoked automatically, make sure to tell users how they can
    // run buck fix on this specific build id manually with the `buck fix` command
    if (!fixSpec.getManuallyInvoked()) {
      printManualInvocationInstructions(console, fixSpec.getBuildId());
    }

    if (fixConfig.shouldPrintFixScriptMessage()) {
      printFixScriptMessage(console, fixScript);
    }

    filesystem.mkdirs(runPath.getParent());
    filesystem.mkdirs(fixPath.getParent());

    try (OutputStream specOutput = filesystem.newFileOutputStream(runPath)) {
      ObjectMappers.WRITER.writeValue(specOutput, runSpec);
    }

    try (OutputStream specOutput = filesystem.newFileOutputStream(fixPath)) {
      ObjectMappers.WRITER.writeValue(specOutput, fixSpec);
    }
  }
}
