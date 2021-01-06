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

package com.facebook.buck.support.fix;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.doctor.BuildLogHelper;
import com.facebook.buck.doctor.config.BuildLogEntry;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.types.Either;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Creates a BuckFixSpec to pass to helper scripts from matching {@link
 * com.facebook.buck.doctor.config.BuildLogEntry}
 */
public class BuckFixSpecParser {
  private static Logger LOG = Logger.get(BuckFixSpecParser.class);

  /** The various ways that trying to parse a FixSpec can fail */
  public enum FixSpecFailure {
    MISSING("No log for could be found to use for `fix`"),
    MISSING_BUILD_ID("Fix spec was missing a build id"),
    MISSING_EXIT_CODE("Fix spec was missing an exit code"),
    MISSING_COMMAND_ARGS("Fix spec was missing command args"),
    MISSING_EXPANDED_COMMAND_ARGS("Fix spec was missing expanded command args"),
    MISSING_FIX_SPEC_FILE_IN_LOGS("Fix spec file in logs was missing");

    private final String message;

    FixSpecFailure(String message) {
      this.message = message;
    }

    /** Get a human readable error message for this failure */
    public String humanReadableError() {
      return message;
    }
  }

  /**
   * Tries to construct a {@link BuckFixSpec} from the newest invocation of buck
   *
   * <p>This excludes commands like fix, server, doctor, etc (see {@link BuildLogHelper}), and will
   * not return a spec if required fields were not able to be found in the logs (e.g. if exitCode is
   * missing, a useful spec will not be able to be constructed)
   *
   * @param helper The helper used to find all build logs
   * @param fixConfig The configuration for this invocation of fix
   * @param manuallyInvoked Whether or not this spec will be used in a command that was manually
   *     invoked
   * @return A {@link BuckFixSpec} constructed from the build logs or an error if one could not be
   *     found or constructed
   * @throws IOException There was a problem reading the logs on disk
   */
  public static Either<BuckFixSpec, FixSpecFailure> parseLastCommand(
      BuildLogHelper helper, FixBuckConfig fixConfig, boolean manuallyInvoked) throws IOException {
    Optional<BuildLogEntry> entry = helper.getBuildLogs().stream().findFirst();
    return entry
        .map(
            e ->
                specFromBuildLogEntry(
                    fixConfig, e, OptionalInt.empty(), manuallyInvoked, Optional.empty()))
        .orElse(Either.ofRight(FixSpecFailure.MISSING));
  }

  /**
   * Tries to construct a {@link BuckFixSpec} from a specific invocation of buck
   *
   * <p>A spec will not be returned if the log could not be found, or if required fields were not
   * able to be found in the logs (e.g. if exitCode is missing, a useful spec will not be able to be
   * constructed)
   *
   * @param helper The helper used to find all build logs
   * @param fixConfig The configuration for this invocation of fix
   * @param buildId The build id to look for
   * @param manuallyInvoked Whether or not this spec will be used in a command that was manually
   *     invoked
   * @return A {@link BuckFixSpec} constructed from the build logs or an error if one could not be
   *     found or constructed
   * @throws IOException There was a problem reading the logs on disk
   */
  public static Either<BuckFixSpec, FixSpecFailure> parseFromBuildId(
      BuildLogHelper helper, FixBuckConfig fixConfig, BuildId buildId, boolean manuallyInvoked)
      throws IOException {

    Optional<BuildLogEntry> entry = helper.getBuildLogEntryFromId(buildId);
    return entry
        .map(
            e ->
                specFromBuildLogEntry(
                    fixConfig, e, OptionalInt.empty(), manuallyInvoked, Optional.empty()))
        .orElse(Either.ofRight(FixSpecFailure.MISSING));
  }

  /**
   * Tries to construct a {@link com.facebook.buck.support.fix.BuckFixSpec} from a specific
   * invocation of buck
   *
   * <p>A spec will not be returned if the log could not be found, or if required fields were not
   * able to be found in the logs (e.g. if command args are missing, a useful spec will not be able
   * to be constructed)
   *
   * <p>Exit code is passed in, as there may not be enough information in the log yet (if this is
   * called before the command has terminated) to otherwise construct a valid spec
   *
   * @param helper The helper used to find all build logs
   * @param fixConfig The configuration for this invocation of fix
   * @param buildId The build id to look for
   * @param exitCode The exit code for the command
   * @param manuallyInvoked Whether or not this spec will be used in a command that was manually
   *     invoked
   * @return A {@link com.facebook.buck.support.fix.BuckFixSpec} constructed from the build logs
   * @throws IOException There was a problem reading the logs on disk
   */
  public static Either<BuckFixSpec, FixSpecFailure> parseFromBuildIdWithExitCode(
      BuildLogHelper helper,
      FixBuckConfig fixConfig,
      BuildId buildId,
      ExitCode exitCode,
      boolean manuallyInvoked,
      Optional<Exception> runException)
      throws IOException {

    Optional<BuildLogEntry> entry = helper.getBuildLogEntryFromId(buildId);
    return entry
        .map(
            e ->
                specFromBuildLogEntry(
                    fixConfig,
                    e,
                    OptionalInt.of(exitCode.getCode()),
                    manuallyInvoked,
                    runException))
        .orElse(Either.ofRight(FixSpecFailure.MISSING));
  }

  /**
   * Tries to parse a {@link BuckFixSpec} from a file already with the information of a fix spec.
   * This differs from other parse... methods in that it doesn't try to construct a fix spec from
   * various different log files, only from a single spec file.
   *
   * @param fixSpecPath the absolute path to the fix spec file
   * @return Either a {@link BuckFixSpec} or a Failure if the spec file is missing in the given path
   * @throws IOException for any other error in the process of reading the file
   */
  public static Either<BuckFixSpec, FixSpecFailure> parseFromFixSpecFile(Path fixSpecPath)
      throws IOException {
    try {
      BuckFixSpec buckFixSpec =
          ObjectMappers.READER.readValue(
              ObjectMappers.createParser(fixSpecPath), BuckFixSpec.class);

      return Either.ofLeft(buckFixSpec);
    } catch (FileNotFoundException | NoSuchFileException e) {
      return Either.ofRight(FixSpecFailure.MISSING_FIX_SPEC_FILE_IN_LOGS);
    }
  }

  private static Either<BuckFixSpec, FixSpecFailure> specFromBuildLogEntry(
      FixBuckConfig fixConfig,
      BuildLogEntry buildLogEntry,
      OptionalInt exitCodeOverride,
      boolean manuallyInvoked,
      Optional<Exception> runException) {
    if (!buildLogEntry.getBuildId().isPresent()) {
      return Either.ofRight(FixSpecFailure.MISSING_BUILD_ID);
    }

    if (buildLogEntry.getBuckFixSpecFile().isPresent()) {
      try {
        return parseFromFixSpecFile(buildLogEntry.getBuckFixSpecFile().get());
      } catch (IOException e) {
        LOG.warn(
            e,
            "Tried to parse from fix spec file: %s because file is present, but failed.",
            buildLogEntry.getBuckFixSpecFile().get());
      }
    }

    OptionalInt maybeExitCode =
        exitCodeOverride.isPresent() ? exitCodeOverride : buildLogEntry.getExitCode();
    if (!maybeExitCode.isPresent()) {
      return Either.ofRight(FixSpecFailure.MISSING_EXIT_CODE);
    }

    if (!buildLogEntry.getCommandArgs().isPresent()
        || buildLogEntry.getCommandArgs().get().isEmpty()) {
      return Either.ofRight(FixSpecFailure.MISSING_COMMAND_ARGS);
    }

    if (!buildLogEntry.getExpandedCommandArgs().isPresent()
        || buildLogEntry.getExpandedCommandArgs().get().isEmpty()) {
      return Either.ofRight(FixSpecFailure.MISSING_EXPANDED_COMMAND_ARGS);
    }

    BuildId buildId = buildLogEntry.getBuildId().get();
    int exitCode = maybeExitCode.getAsInt();
    List<String> commandArgs = buildLogEntry.getCommandArgs().get();
    List<String> expandedCommandArgs = buildLogEntry.getExpandedCommandArgs().get();

    return Either.ofLeft(
        ImmutableBuckFixSpec.of(
            buildId,
            commandArgs.get(0),
            exitCode,
            commandArgs.subList(1, commandArgs.size()),
            expandedCommandArgs.subList(1, expandedCommandArgs.size()),
            manuallyInvoked,
            commandDataObject(runException),
            fixConfig.getBuckProvidedScripts(),
            BuckFixSpec.getLogsMapping(
                Optional.of(buildLogEntry.getRelativePath()),
                buildLogEntry.getMachineReadableLogFile(),
                buildLogEntry.getTraceFile(),
                buildLogEntry.getConfigJsonFile())));
  }

  @VisibleForTesting
  static Optional<Object> commandDataObject(Optional<Exception> runException) {
    return runException.map(e -> ImmutableMap.of("exception", runException));
  }
}
