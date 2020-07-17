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

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

class CxxLinkStep extends ShellStep {

  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> linker;
  private final Path argFilePath;

  /** Directory to use to store intermediate/temp files used for linking. */
  private final Path scratchDir;

  private final Optional<AbsPath> skipLinkingStep;

  public CxxLinkStep(
      AbsPath workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> linker,
      Path argFilePath,
      Path scratchDir,
      boolean withDownwardApi) {
    this(
        workingDirectory,
        environment,
        linker,
        argFilePath,
        scratchDir,
        withDownwardApi,
        Optional.empty());
  }

  public CxxLinkStep(
      AbsPath workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> linker,
      Path argFilePath,
      Path scratchDir,
      boolean withDownwardApi,
      Optional<AbsPath> skipLinkingFilePath) {
    super(workingDirectory, withDownwardApi);
    this.environment = environment;
    this.linker = linker;
    this.argFilePath = argFilePath;
    this.scratchDir = scratchDir;
    this.skipLinkingStep = skipLinkingFilePath;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(StepExecutionContext context) {
    if (skipLinkingStep.isPresent() && Files.exists(skipLinkingStep.get().getPath())) {
      return ImmutableList.of();
    }

    return ImmutableList.<String>builder().addAll(linker).add("@" + argFilePath).build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
    return ImmutableMap.<String, String>builder()
        .putAll(environment)
        // Set `TMPDIR` to `scratchDir` so that the linker uses it for it's temp and intermediate
        // files.
        .put("TMPDIR", scratchDir.toString())
        .build();
  }

  @Override
  public String getShortName() {
    return "c++ link";
  }

  @Override
  public boolean shouldPrintStderr(Verbosity verbosity) {
    // Always print warnings and errors
    return true;
  }
}
