/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.doctor.config;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractBuildLogEntry {
  public abstract Path getRelativePath();

  public abstract Optional<BuildId> getBuildId();

  public abstract Optional<List<String>> getCommandArgs();

  public abstract OptionalInt getExitCode();

  public abstract Optional<Path> getRuleKeyLoggerLogFile();

  public abstract Optional<Path> getMachineReadableLogFile();

  public abstract Optional<Path> getRuleKeyDiagKeysFile();

  public abstract Optional<Path> getRuleKeyDiagGraphFile();

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
