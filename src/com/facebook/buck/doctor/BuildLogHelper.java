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

package com.facebook.buck.doctor;

import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_BUILD_FINISHED;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_EXIT_CODE;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_INVOCATION_INFO;

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
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

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
          && !entry.getCommandArgs().get().get(0).matches("^(rage|doctor|server|launch)$")) {
        logEntries.add(newBuildLogEntry(logFile));
      }
    }
    return logEntries
        .build()
        .stream()
        .sorted(Comparator.comparing(BuildLogEntry::getLastModifiedTime).reversed())
        .collect(ImmutableList.toImmutableList());
  }

  private BuildLogEntry newBuildLogEntry(Path logFile) throws IOException {
    BuildLogEntry.Builder builder = BuildLogEntry.builder();

    Path machineReadableLogFile =
        logFile.getParent().resolve(BuckConstant.BUCK_MACHINE_LOG_FILE_NAME);

    if (projectFilesystem.isFile(machineReadableLogFile)) {

      Optional<InvocationInfo> invocationInfo =
          readObjectFromLog(
              machineReadableLogFile,
              PREFIX_INVOCATION_INFO,
              new TypeReference<InvocationInfo>() {});
      Optional<Long> startTimestampMs = Optional.empty();

      if (invocationInfo.isPresent()) {
        builder.setCommandArgs(
            Optional.ofNullable(invocationInfo.get().getUnexpandedCommandArgs()));
        startTimestampMs = Optional.of(invocationInfo.get().getTimestampMillis());
      }

      builder.setBuildId(
          Optional.of(
              invocationInfo.isPresent()
                  ? invocationInfo.get().getBuildId()
                  : new BuildId("unknown")));
      builder.setMachineReadableLogFile(machineReadableLogFile);
      Optional<Integer> exitCode =
          readObjectFieldFromLog(machineReadableLogFile, PREFIX_EXIT_CODE, "exitCode");
      Optional<Long> finishTimestampMs =
          readObjectFieldFromLog(machineReadableLogFile, PREFIX_BUILD_FINISHED, "timestamp");

      builder.setExitCode(
          exitCode.isPresent() ? OptionalInt.of(exitCode.get()) : OptionalInt.empty());
      if (finishTimestampMs.isPresent() && startTimestampMs.isPresent()) {
        builder.setBuildTimeMs(
            OptionalInt.of((int) (finishTimestampMs.get() - startTimestampMs.get())));
      }
    }

    Path ruleKeyLoggerFile = logFile.getParent().resolve(BuckConstant.RULE_KEY_LOGGER_FILE_NAME);
    if (projectFilesystem.isFile(ruleKeyLoggerFile)) {
      builder.setRuleKeyLoggerLogFile(ruleKeyLoggerFile);
    }

    Path ruleKeyDiagKeysFile =
        logFile.getParent().resolve(BuckConstant.RULE_KEY_DIAG_KEYS_FILE_NAME);
    if (projectFilesystem.isFile(ruleKeyDiagKeysFile)) {
      builder.setRuleKeyDiagKeysFile(ruleKeyDiagKeysFile);
    }

    Path ruleKeyDiagGraphFile =
        logFile.getParent().resolve(BuckConstant.RULE_KEY_DIAG_GRAPH_FILE_NAME);
    if (projectFilesystem.isFile(ruleKeyDiagGraphFile)) {
      builder.setRuleKeyDiagGraphFile(ruleKeyDiagGraphFile);
    }

    Optional<Path> traceFile =
        projectFilesystem
            .getFilesUnderPath(logFile.getParent())
            .stream()
            .filter(input -> input.toString().endsWith(".trace"))
            .findFirst();

    return builder
        .setRelativePath(logFile)
        .setSize(projectFilesystem.getFileSize(logFile))
        .setLastModifiedTime(Date.from(projectFilesystem.getLastModifiedTime(logFile).toInstant()))
        .setTraceFile(traceFile)
        .build();
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
        new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return Files.isSymbolicLink(dir)
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
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
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            return FileVisitResult.CONTINUE;
          }
        });

    return logfiles;
  }
}
