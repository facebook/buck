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
import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import org.immutables.value.Value;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Methods for finding and inspecting buck log files.
 *
 */
public class BuildLogHelper {
  // Max number of lines to read from a build.log file when searching for 'header' entries like
  // "started", "system properties", etc...
  private static final int MAX_LINES_TO_SCAN_FOR_LOG_HEADER = 100;
  // TODO(#9727949): we should switch over to a machine-readable log format.
  private static final Pattern LOG_LINE_PATTERN = Pattern.compile(
      "(\\[[^\\]]+\\])+" +
      "\\[command:(?<buildid>[\\p{XDigit}-]+)\\]" +
      "(\\[[^\\]]+\\])+" +
      "(?<line>.*)");
  private static final Pattern ARG_PATTERN = Pattern.compile(
      "args: \\[(?<args>.+)\\]");
  private static final String BUCK_LOG_FILE_GLOB = "buck-*.log*";
  private static final String BUCK_LOCK_FILE_EXTENSION = ".lck";
  private final ProjectFilesystem projectFilesystem;

  public BuildLogHelper(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
  }

  public ImmutableList<BuildLogEntry> getBuildLogs() throws IOException {
    Collection<Path> logFiles = projectFilesystem.getSortedMatchingDirectoryContents(
        BuckConstant.LOG_PATH,
        BUCK_LOG_FILE_GLOB);
    ImmutableList.Builder<BuildLogEntry> logEntries = ImmutableList.builder();
    for (Path logFile : logFiles) {
      if (logFile.getFileName().toString().endsWith(BUCK_LOCK_FILE_EXTENSION)) {
        continue;
      }
      logEntries.add(
          BuildLogEntry.builder()
              .setBuildId(getBuildId(logFile))
              .setCommandArgs(getCommandArgs(logFile))
              .setRelativePath(projectFilesystem.getRootPath().relativize(logFile))
              .setSize(projectFilesystem.getFileSize(logFile))
              .setLastModifiedTime(new Date(projectFilesystem.getLastModifiedTime(logFile)))
              .build());
    }
    return logEntries.build();
  }

  private Optional<Matcher> extractFirstMatchingLine(
      Path logPath,
      Pattern pattern) throws IOException {
    try (Reader reader = projectFilesystem.getReaderIfFileExists(logPath).get();
        BufferedReader bufferedReader = new BufferedReader(reader)) {
      for (int i = 0; i < MAX_LINES_TO_SCAN_FOR_LOG_HEADER; ++i) {
        String line = bufferedReader.readLine();
        if (line == null) { // EOF.
          break;
        }
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
          return Optional.of(matcher);
        }
      }
    }
    return Optional.absent();
  }

  private Optional<BuildId> getBuildId(Path logPath) throws IOException {
    return extractFirstMatchingLine(logPath, LOG_LINE_PATTERN)
        .transform(
            new Function<Matcher, BuildId>() {
              @Override
              public BuildId apply(Matcher input) {
                return new BuildId(input.group("buildid"));
              }
            });
  }

  private Optional<String> getCommandArgs(Path logPath) throws IOException {
    return extractFirstMatchingLine(logPath, ARG_PATTERN)
        .transform(
            new Function<Matcher, String>() {
              @Override
              public String apply(Matcher input) {
                return input.group("args");
              }
            });
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractBuildLogEntry {
    public abstract Path getRelativePath();
    public abstract Optional<BuildId> getBuildId();
    public abstract Optional<String> getCommandArgs();
    public abstract long getSize();
    public abstract Date getLastModifiedTime();

    @Value.Check
    void pathIsRelative() {
      Preconditions.checkState(!getRelativePath().isAbsolute());
    }
  }
}
