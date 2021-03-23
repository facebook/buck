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

package com.facebook.buck.apple;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;

/**
 * Extracts the warnings from a DWARF file which must have been embedded by dsymutil previously (by
 * passing --papertrail).
 */
public class AppleDsymExtractPapertrailStep extends IsolatedShellStep {
  private final ProjectFilesystem filesystem;

  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> command;

  private final Path dsymFilePath;
  private final Path warningsOutputPath;

  protected AppleDsymExtractPapertrailStep(
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> environment,
      ImmutableList<String> command,
      Path dsymFilePath,
      Path warningsOutputPath,
      RelPath cellPath,
      boolean withDownwardApi) {
    super(filesystem.getRootPath(), cellPath, withDownwardApi);
    this.filesystem = filesystem;
    this.environment = environment;
    this.command = command;
    this.dsymFilePath = dsymFilePath;
    this.warningsOutputPath = warningsOutputPath;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();

    commandBuilder.addAll(command);
    commandBuilder.add("--name", "dsymutil_warning");
    commandBuilder.add(
        "-o",
        filesystem.resolve(warningsOutputPath).toString(),
        filesystem.resolve(dsymFilePath).toString());

    return commandBuilder.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
    return environment;
  }

  @Override
  public String getShortName() {
    return "dwarfdump";
  }
}
