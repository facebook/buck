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

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Step to strip the debug symbol table on linked binaries with no focused targets. */
public class StripNonFocusedDebugSymbolStep extends IsolatedShellStep {

  private static final Logger LOG = Logger.get(StripNonFocusedDebugSymbolStep.class);

  private final AbsPath focusedTargetsPath;
  private final Tool strip;
  private final SourcePathResolverAdapter sourcePathResolver;
  private final Path input;
  private final ProjectFilesystem filesystem;

  public StripNonFocusedDebugSymbolStep(
      AbsPath focusedTargetsPath,
      Path input,
      Tool strip,
      SourcePathResolverAdapter sourcePathResolver,
      ProjectFilesystem filesystem,
      AbsPath workingDirectory,
      RelPath cellPath,
      boolean withDownwardApi) {
    super(workingDirectory, cellPath, withDownwardApi);
    this.focusedTargetsPath = focusedTargetsPath;
    this.strip = strip;
    this.sourcePathResolver = sourcePathResolver;
    this.filesystem = filesystem;
    this.input = input;
  }

  @Override
  public String getShortName() {
    return "strip_debug_symbol";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
    try {
      List<String> focusedTargets =
          ObjectMappers.READER.readValue(
              ObjectMappers.createParser(focusedTargetsPath.getPath()),
              new TypeReference<List<String>>() {});
      // If we have focused targets, skip debug symbol table stripping.
      if (!focusedTargets.isEmpty()) {
        return ImmutableList.of();
      }
    } catch (IOException exception) {
      // If we can't read from the focused targets paths, log the error and continue scrubbing
      // as if no focused targets are provided.
      LOG.error(exception.getMessage());
    }

    Path inputFilePath = filesystem.resolve(input);
    return ImmutableList.<String>builder()
        .addAll(strip.getCommandPrefix(sourcePathResolver))
        .add("-S")
        .add(inputFilePath.toAbsolutePath().toString())
        .build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
    return strip.getEnvironment(sourcePathResolver);
  }
}
