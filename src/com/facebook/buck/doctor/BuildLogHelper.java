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

import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_EXIT_CODE;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_INVOCATION_INFO;

import com.facebook.buck.doctor.config.BuildLogEntry;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ObjectMappers;
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
import java.util.stream.Stream;

/** Methods for finding and inspecting buck log files. */
public class BuildLogHelper {

  private final ProjectFilesystem projectFilesystem;

  private static final String INFO_FIELD_UNEXPANDED_CMD_ARGS = "unexpandedCommandArgs";

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
          && !entry.getCommandArgs().get().matches("^(rage|doctor|server|launch)\\b.*")) {
        logEntries.add(newBuildLogEntry(logFile));
      }
    }
    return logEntries
        .build()
        .stream()
        .sorted(Comparator.comparing(BuildLogEntry::getLastModifiedTime).reversed())
        .collect(ImmutableList.toImmutableList());
  }

  @SuppressWarnings("unchecked")
  private BuildLogEntry newBuildLogEntry(Path logFile) throws IOException {
    BuildLogEntry.Builder builder = BuildLogEntry.builder();

    Path machineReadableLogFile =
        logFile.getParent().resolve(BuckConstant.BUCK_MACHINE_LOG_FILE_NAME);
    if (projectFilesystem.isFile(machineReadableLogFile)) {
      String invocationInfoLine;
      try (Stream<String> logLines =
          Files.lines(projectFilesystem.resolve(machineReadableLogFile))) {
        invocationInfoLine =
            logLines
                .filter(line -> line.startsWith(PREFIX_INVOCATION_INFO))
                .map(line -> line.substring(PREFIX_INVOCATION_INFO.length()))
                .findFirst()
                .get();
      }

      // TODO(mikath): Keep this for a while for compatibility for commandArgs, then replace with
      // a proper ObjectMapper deserialization of InvocationInfo.
      Map<String, Object> invocationInfo =
          ObjectMappers.readValue(invocationInfoLine, new TypeReference<Map<String, Object>>() {});
      Optional<String> commandArgs = Optional.empty();
      if (invocationInfo.containsKey(INFO_FIELD_UNEXPANDED_CMD_ARGS)
          && invocationInfo.get(INFO_FIELD_UNEXPANDED_CMD_ARGS) instanceof List) {
        commandArgs =
            Optional.of(
                String.join(
                    " ", (List<String>) invocationInfo.get(INFO_FIELD_UNEXPANDED_CMD_ARGS)));
      }

      String buildId = (String) invocationInfo.get("buildId");
      builder.setBuildId(Optional.of(new BuildId(buildId != null ? buildId : "unknown")));
      builder.setCommandArgs(commandArgs);
      builder.setMachineReadableLogFile(machineReadableLogFile);
      builder.setExitCode(readExitCode(machineReadableLogFile));
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

  private OptionalInt readExitCode(Path machineReadableLogFile) {
    try (BufferedReader reader =
        Files.newBufferedReader(projectFilesystem.resolve(machineReadableLogFile))) {
      Optional<String> line =
          reader
              .lines()
              .filter(s -> s.startsWith(PREFIX_EXIT_CODE))
              .map(s -> s.substring(PREFIX_EXIT_CODE.length()))
              .findFirst();
      if (line.isPresent()) {
        Map<String, Integer> exitCode =
            ObjectMappers.READER.readValue(
                ObjectMappers.createParser(line.get().getBytes(StandardCharsets.UTF_8)),
                new TypeReference<Map<String, Integer>>() {});
        if (exitCode.containsKey("exitCode")) {
          return OptionalInt.of(exitCode.get("exitCode"));
        }
      }
    } catch (IOException e) {
      return OptionalInt.empty();
    }
    return OptionalInt.empty();
  }

  private Collection<Path> getAllBuckLogFiles() throws IOException {
    final List<Path> logfiles = new ArrayList<>();
    projectFilesystem.walkRelativeFileTree(
        projectFilesystem.getBuckPaths().getLogDir(),
        new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            return Files.isSymbolicLink(dir)
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (file.getFileName().toString().equals(BuckConstant.BUCK_LOG_FILE_NAME)) {
              logfiles.add(file);
            }

            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            return FileVisitResult.CONTINUE;
          }
        });

    return logfiles;
  }
}
