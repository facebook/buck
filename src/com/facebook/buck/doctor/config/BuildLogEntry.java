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

package com.facebook.buck.doctor.config;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.immutables.value.Value;

@BuckStyleValue
public interface BuildLogEntry {

  Path getRelativePath();

  Optional<BuildId> getBuildId();

  Optional<List<String>> getCommandArgs();

  Optional<List<String>> getExpandedCommandArgs();

  OptionalInt getExitCode();

  OptionalInt getBuildTimeMs();

  Optional<Path> getRuleKeyLoggerLogFile();

  Optional<Path> getMachineReadableLogFile();

  Optional<Path> getRuleKeyDiagKeysFile();

  Optional<Path> getRuleKeyDiagGraphFile();

  Optional<Path> getTraceFile();

  Optional<Path> getConfigJsonFile();

  Optional<Path> getBuckFixSpecFile();

  long getSize();

  Date getLastModifiedTime();

  @Value.Check
  default void pathIsRelative() {
    Preconditions.checkState(!getRelativePath().isAbsolute());
    if (getRuleKeyLoggerLogFile().isPresent()) {
      Preconditions.checkState(!getRuleKeyLoggerLogFile().get().isAbsolute());
    }
    if (getTraceFile().isPresent()) {
      Preconditions.checkState(!getTraceFile().get().isAbsolute());
    }
  }

  static BuildLogEntry of(
      Path relativePath,
      Optional<? extends BuildId> buildId,
      Optional<? extends List<String>> commandArgs,
      Optional<? extends List<String>> expandedCommandArgs,
      OptionalInt exitCode,
      OptionalInt buildTimeMs,
      Optional<? extends Path> ruleKeyLoggerLogFile,
      Optional<? extends Path> machineReadableLogFile,
      Optional<? extends Path> ruleKeyDiagKeysFile,
      Optional<? extends Path> ruleKeyDiagGraphFile,
      Optional<? extends Path> traceFile,
      Optional<? extends Path> configJsonFile,
      Optional<? extends Path> buckFixSpecFile,
      long size,
      Date lastModifiedTime) {
    return ImmutableBuildLogEntry.of(
        relativePath,
        buildId,
        commandArgs,
        expandedCommandArgs,
        exitCode,
        buildTimeMs,
        ruleKeyLoggerLogFile,
        machineReadableLogFile,
        ruleKeyDiagKeysFile,
        ruleKeyDiagGraphFile,
        traceFile,
        configJsonFile,
        buckFixSpecFile,
        size,
        lastModifiedTime);
  }
}
