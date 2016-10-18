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

package com.facebook.buck.rage;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.immutables.value.Value;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * Methods for finding and inspecting buck log files.
 *
 */
public class BuildLogHelper {
  // Max number of lines to read from a build.log file when searching for 'header' entries like
  // "started", "system properties", etc...
  private static final int MAX_LINES_TO_SCAN_FOR_LOG_HEADER = 100;
  private static final String BUCK_LOG_FILE = BuckConstant.BUCK_LOG_FILE_NAME;

  private final ProjectFilesystem projectFilesystem;

  public BuildLogHelper(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
  }

  public ImmutableList<BuildLogEntry> getBuildLogs() throws IOException {
    Collection<Path> logFiles = getAllBuckLogFiles();
    ImmutableList.Builder<BuildLogEntry> logEntries = ImmutableList.builder();
    for (Path logFile : logFiles) {
      logEntries.add(newBuildLogEntry(logFile));
    }
    return logEntries.build();
  }

  private BuildLogEntry newBuildLogEntry(Path logFile) throws IOException {
    Optional<InvocationInfo.ParsedLog> parsedLine = extractFirstMatchingLine(logFile);
    BuildLogEntry.Builder builder = BuildLogEntry.builder();

    if (parsedLine.isPresent()) {
      builder.setBuildId(parsedLine.get().getBuildId());
      builder.setCommandArgs(parsedLine.get().getArgs());
    }

    Path ruleKeyLoggerFile = logFile.getParent().resolve(BuckConstant.RULE_KEY_LOGGER_FILE_NAME);
    if (projectFilesystem.isFile(ruleKeyLoggerFile)) {
      builder.setRuleKeyLoggerLogFile(ruleKeyLoggerFile);
    }

    Path machineReadableLogFile =
        logFile.getParent().resolve(BuckConstant.BUCK_MACHINE_LOG_FILE_NAME);
    if (projectFilesystem.isFile(machineReadableLogFile)) {
      builder.setMachineReadableLogFile(machineReadableLogFile);
    }

    Optional <Path> traceFile = FluentIterable
        .from(projectFilesystem.getFilesUnderPath(logFile.getParent()))
        .filter(input -> input.toString().endsWith(".trace")).first();

    return builder
        .setRelativePath(logFile)
        .setSize(projectFilesystem.getFileSize(logFile))
        .setLastModifiedTime(new Date(projectFilesystem.getLastModifiedTime(logFile)))
        .setTraceFile(traceFile)
        .build();
  }

  private Optional<InvocationInfo.ParsedLog> extractFirstMatchingLine(Path logPath)
      throws IOException {
    try (Reader reader = projectFilesystem.getReaderIfFileExists(logPath).get();
         BufferedReader bufferedReader = new BufferedReader(reader)) {
      for (int i = 0; i < MAX_LINES_TO_SCAN_FOR_LOG_HEADER; ++i) {
        String line = bufferedReader.readLine();
        if (line == null) { // EOF.
          break;
        }

        Optional<InvocationInfo.ParsedLog> result = InvocationInfo.parseLogLine(line);
        if (result.isPresent()) {
          return result;
        }
      }
    }

    return Optional.absent();
  }

  public Collection<Path> getAllBuckLogFiles() throws IOException {
    final List<Path> logfiles = Lists.newArrayList();
    projectFilesystem.walkRelativeFileTree(
        projectFilesystem.getBuckPaths().getLogDir(),
        new FileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(
          Path dir, BasicFileAttributes attrs) throws IOException {
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(
          Path file, BasicFileAttributes attrs) throws IOException {
        if (file.getFileName().toString().equals(BUCK_LOG_FILE)) {
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

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractBuildLogEntry {
    public abstract Path getRelativePath();
    public abstract Optional<BuildId> getBuildId();
    public abstract Optional<String> getCommandArgs();
    public abstract Optional<Path> getRuleKeyLoggerLogFile();
    public abstract Optional<Path> getMachineReadableLogFile();
    public abstract Optional<Path> getTraceFile();
    public abstract long getSize();
    public abstract Date getLastModifiedTime();

    @Value.Check
    void pathIsRelative() {
      Preconditions.checkState(!getRelativePath().isAbsolute());
      if (getRuleKeyLoggerLogFile().isPresent()) {
        Preconditions.checkState(!getRuleKeyLoggerLogFile().get().isAbsolute());
      }
      if (getTraceFile().isPresent()) {
        Preconditions.checkState(!getTraceFile().get().isAbsolute());
      }
    }
  }
}
