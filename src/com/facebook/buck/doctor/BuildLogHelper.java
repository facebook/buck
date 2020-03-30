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

package com.facebook.buck.doctor;

import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_BUILD_FINISHED;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_EXIT_CODE;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_INVOCATION_INFO;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.doctor.config.BuildLogEntry;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import javax.annotation.Nullable;

/** Methods for finding and inspecting buck log files. */
public class BuildLogHelper {

  private final ProjectFilesystem projectFilesystem;

  public BuildLogHelper(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
  }

  public ImmutableList<BuildLogEntry> getBuildLogs() throws IOException {
    // Remove commands with unknown args or invocations of buck rage.
    // Sort the remaining logs based on time, reverse order.
    ImmutableList.Builder<BuildLogEntry> logEntries = ImmutableList.builder();
    for (Path logFile : getAllBuckLogFiles()) {
      BuildLogEntry entry = newBuildLogEntry(logFile);
      if (entry.getCommandArgs().isPresent()
          && entry.getCommandArgs().get().size() > 0
          && !entry.getCommandArgs().get().get(0).matches("^(rage|doctor|server|launch|fix)$")) {
        logEntries.add(newBuildLogEntry(logFile));
      }
    }
    return logEntries.build().stream()
        .sorted(Comparator.comparing(BuildLogEntry::getLastModifiedTime).reversed())
        .collect(ImmutableList.toImmutableList());
  }

  private BuildLogEntry newBuildLogEntry(Path logFile) throws IOException {

    Optional<Path> machineReadableLogFile =
        Optional.of(logFile.resolveSibling(BuckConstant.BUCK_MACHINE_LOG_FILE_NAME))
            .filter(path -> projectFilesystem.isFile(path));

    Optional<Integer> exitCode =
        machineReadableLogFile.flatMap(
            machineFile -> readObjectFieldFromLog(machineFile, PREFIX_EXIT_CODE, "exitCode"));

    Optional<InvocationInfo> invocationInfo =
        machineReadableLogFile.flatMap(
            machineLogFile ->
                readObjectFromLog(
                    machineLogFile,
                    PREFIX_INVOCATION_INFO,
                    new TypeReference<InvocationInfo>() {}));

    Optional<List<String>> commandArgs =
        invocationInfo.flatMap(iInfo -> Optional.ofNullable(iInfo.getUnexpandedCommandArgs()));
    Optional<List<String>> expandedCommandArgs =
        invocationInfo.flatMap(iInfo -> Optional.ofNullable(iInfo.getCommandArgs()));

    Optional<BuildId> buildId =
        machineReadableLogFile.map(
            machineLogFile ->
                invocationInfo.map(InvocationInfo::getBuildId).orElse(new BuildId("unknown")));

    Optional<Long> startTimestampMs = invocationInfo.map(InvocationInfo::getTimestampMillis);

    Optional<Long> finishTimestampMs =
        machineReadableLogFile.flatMap(
            machineFile -> readObjectFieldFromLog(machineFile, PREFIX_BUILD_FINISHED, "timestamp"));

    OptionalInt buildTimeMs =
        finishTimestampMs.isPresent() && startTimestampMs.isPresent()
            ? OptionalInt.of((int) (finishTimestampMs.get() - startTimestampMs.get()))
            : OptionalInt.empty();

    Optional<Path> ruleKeyLoggerFile =
        Optional.of(logFile.getParent().resolve(BuckConstant.RULE_KEY_LOGGER_FILE_NAME))
            .filter(path -> projectFilesystem.isFile(path));

    Optional<Path> ruleKeyDiagKeysFile =
        Optional.of(logFile.getParent().resolve(BuckConstant.RULE_KEY_DIAG_KEYS_FILE_NAME))
            .filter(path -> projectFilesystem.isFile(path));

    Optional<Path> ruleKeyDiagGraphFile =
        Optional.of(logFile.getParent().resolve(BuckConstant.RULE_KEY_DIAG_GRAPH_FILE_NAME))
            .filter(path -> projectFilesystem.isFile(path));

    Optional<Path> traceFile =
        projectFilesystem.asView()
            .getFilesUnderPath(logFile.getParent(), EnumSet.of(FileVisitOption.FOLLOW_LINKS))
            .stream()
            .filter(input -> input.toString().endsWith(".trace"))
            .findFirst();

    Optional<Path> configJsonFile =
        Optional.of(logFile.resolveSibling(BuckConstant.CONFIG_JSON_FILE_NAME))
            .filter(projectFilesystem::isFile);

    Optional<Path> fixSpecFile =
        Optional.of(logFile.resolveSibling(BuckConstant.BUCK_FIX_SPEC_FILE_NAME))
            .filter(projectFilesystem::isFile);

    return BuildLogEntry.of(
        logFile,
        buildId,
        commandArgs,
        expandedCommandArgs,
        exitCode.map(OptionalInt::of).orElseGet(OptionalInt::empty),
        buildTimeMs,
        ruleKeyLoggerFile,
        machineReadableLogFile,
        ruleKeyDiagKeysFile,
        ruleKeyDiagGraphFile,
        traceFile,
        configJsonFile,
        fixSpecFile,
        projectFilesystem.getFileSize(logFile),
        Date.from(projectFilesystem.getLastModifiedTime(logFile).toInstant()));
  }

  private <T> Optional<T> readObjectFromLog(
      Path machineReadableLogFile, String linePrefix, TypeReference<T> typeReference) {
    try (BufferedReader reader =
        Files.newBufferedReader(projectFilesystem.resolve(machineReadableLogFile))) {
      Optional<String> line =
          reader
              .lines()
              .filter(s -> s.startsWith(linePrefix))
              .map(s -> s.substring(linePrefix.length()))
              .findFirst();
      if (line.isPresent()) {
        return Optional.of(
            ObjectMappers.READER.readValue(
                ObjectMappers.createParser(line.get().getBytes(StandardCharsets.UTF_8)),
                typeReference));
      }
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private <T> Optional<T> readObjectFieldFromLog(
      Path machineReadableLogFile, String linePrefix, String fieldName) {
    Optional<Map<String, T>> logObject =
        readObjectFromLog(
            machineReadableLogFile, linePrefix, new TypeReference<Map<String, T>>() {});
    if (logObject.isPresent() && logObject.get().containsKey(fieldName)) {
      return Optional.of(logObject.get().get(fieldName));
    }
    return Optional.empty();
  }

  private Collection<Path> getAllBuckLogFiles() throws IOException {
    List<Path> logfiles = new ArrayList<>();
    projectFilesystem.walkRelativeFileTree(
        projectFilesystem.getBuckPaths().getLogDir(),
        EnumSet.noneOf(FileVisitOption.class),
        new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return attrs.isSymbolicLink() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (file.getFileName().toString().equals(BuckConstant.BUCK_LOG_FILE_NAME)) {
              logfiles.add(file);
            }

            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            if (exc != null) {
              throw new HumanReadableException(exc, "Cannot visit %s", file);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) {
            if (exc != null) {
              throw new HumanReadableException(exc, "Cannot get a list of files in %s", dir);
            }
            return FileVisitResult.CONTINUE;
          }
        },
        false);

    return logfiles;
  }

  /**
   * Given a build id, return the corresponding {@link BuildLogEntry} of that build
   *
   * @param buildId the buildId corresponding to the {@link BuildLogEntry} to retrieve
   * @return An optional BuildLogEntry
   * @throws IOException If interacting with the filesystem fails
   */
  public Optional<BuildLogEntry> getBuildLogEntryFromId(BuildId buildId) throws IOException {
    return getBuildLogs().stream()
        .filter(e -> e.getBuildId().map(id -> id.equals(buildId)).orElse(false))
        .findFirst();
  }
}
