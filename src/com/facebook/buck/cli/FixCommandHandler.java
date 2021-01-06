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

package com.facebook.buck.cli;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.doctor.BuildLogHelper;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.support.fix.BuckFixSpec;
import com.facebook.buck.support.fix.BuckFixSpecParser;
import com.facebook.buck.support.fix.BuckFixSpecWriter;
import com.facebook.buck.support.fix.BuckRunSpec;
import com.facebook.buck.support.fix.FixBuckConfig;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
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
  private static final Logger LOG = Logger.get(FixCommandHandler.class);

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
  private final InvocationInfo invocationInfo;

  FixCommandHandler(
      ProjectFilesystem filesystem,
      Console console,
      ImmutableMap<String, String> environment,
      FixBuckConfig fixConfig,
      @Nullable String commandArgsFile,
      InvocationInfo invocationInfo) {
    this.filesystem = filesystem;
    this.console = console;
    this.environment = environment;
    this.fixConfig = fixConfig;
    this.commandArgsPath = Optional.ofNullable(commandArgsFile).map(Paths::get);
    this.invocationInfo = invocationInfo;
  }

  private void validateCanRunFixScript() throws FixCommandHandlerException {
    if (!commandArgsPath.isPresent()) {
      throw new IllegalStateException(
          "--command-args-file was not provided by the buck wrapper script");
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

  void runWithBuildIdWithExitCode(
      BuildId buildId, ExitCode exitCode, boolean manuallyInvoked, Optional<Exception> runException)
      throws FixCommandHandlerException, IOException {
    BuildLogHelper buildLogHelper = new BuildLogHelper(filesystem);
    Either<BuckFixSpec, BuckFixSpecParser.FixSpecFailure> fixSpec =
        BuckFixSpecParser.parseFromBuildIdWithExitCode(
            buildLogHelper, fixConfig, buildId, exitCode, manuallyInvoked, runException);
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

    Path fixPath =
        filesystem
            .resolve(invocationInfo.getLogDirectoryPath())
            .resolve(BuckConstant.BUCK_FIX_SPEC_FILE_NAME);
    if (!Files.exists(fixPath) || Files.size(fixPath) == 0) {
      BuckFixSpecWriter.writeSpec(fixPath, fixSpec);
    }

    Path runPath = commandArgsPath.get();
    AbsPath repositoryRoot = filesystem.getRootPath();

    ImmutableList<String> fixScript =
        fixConfig.getInterpolatedFixScript(
            fixConfig.getFixScript().get(), repositoryRoot.getPath(), fixPath);

    BuckRunSpec runSpec = BuckRunSpec.of(fixScript, environment, repositoryRoot.getPath(), true);

    // If the fix command was invoked automatically, make sure to tell users how they can
    // run buck fix on this specific build id manually with the `buck fix` command
    if (!fixSpec.getManuallyInvoked()) {
      printManualInvocationInstructions(console, fixSpec.getBuildId());
    }

    if (fixConfig.shouldPrintFixScriptMessage()) {
      printFixScriptMessage(console, fixScript);
    }

    filesystem.mkdirs(runPath.getParent());

    try (OutputStream specOutput = filesystem.newFileOutputStream(runPath)) {
      ObjectMappers.WRITER.writeValue(specOutput, runSpec);
    }
  }

  /**
   * @param buildId current BuildId
   * @param exitCode current ExitCode
   * @param exceptionForFix an Optional Exception to add to the buck fix spec
   * @return an optional BuckFixSpec, if empty means an error happened, but we don't want to throw
   *     exceptions to avoid altering the error code of code invocation.
   */
  Optional<BuckFixSpec> writeFixSpec(
      BuildId buildId, ExitCode exitCode, Optional<Exception> exceptionForFix) {

    try {
      // Because initially there's no fix spec file in the log dir, build a Buck Fix Spec from logs
      Either<BuckFixSpec, BuckFixSpecParser.FixSpecFailure> fixSpec =
          BuckFixSpecParser.parseFromBuildIdWithExitCode(
              new BuildLogHelper(filesystem), fixConfig, buildId, exitCode, false, exceptionForFix);

      if (fixSpec.isRight()) {
        LOG.warn(
            "Error fetching logs for build %s: %s",
            buildId, fixSpec.getRight().humanReadableError());
        return Optional.empty();
      }

      Path specPath =
          BuckFixSpecWriter.writeSpecToLogDir(
              filesystem.getRootPath().getPath(), invocationInfo, fixSpec.getLeft());
      LOG.info("wrote fix spec file to: %s", specPath);

      return Optional.of(fixSpec.getLeft());
    } catch (IOException e) {
      LOG.warn(e, "Error while writing fix spec file to log directory");
    }
    return Optional.empty();
  }
}
