/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.go;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

public class GoCompileStep extends ShellStep {

  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> compilerCommandPrefix;
  private final Path packageName;
  private final ImmutableList<String> flags;
  private final Iterable<Path> srcs;
  private final ImmutableMap<Path, Path> importPathMap;
  private final ImmutableList<Path> includeDirectories;
  private final Optional<Path> asmHeaderPath;
  private final boolean allowExternalReferences;
  private final GoPlatform platform;
  private final Path output;
  private static final Logger LOG = Logger.get(GoCompileStep.class);

  public GoCompileStep(
      Path workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> compilerCommandPrefix,
      ImmutableList<String> flags,
      Path packageName,
      Iterable<Path> srcs,
      ImmutableMap<Path, Path> importPathMap,
      ImmutableList<Path> includeDirectories,
      Optional<Path> asmHeaderPath,
      boolean allowExternalReferences,
      GoPlatform platform,
      Path output) {
    super(workingDirectory);
    this.environment = environment;
    this.compilerCommandPrefix = compilerCommandPrefix;
    this.flags = flags;
    this.packageName = packageName;
    this.srcs = srcs;
    this.importPathMap = importPathMap;
    this.includeDirectories = includeDirectories;
    this.asmHeaderPath = asmHeaderPath;
    this.allowExternalReferences = allowExternalReferences;
    this.platform = platform;
    this.output = output;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ArrayList<String> pathStrings = new ArrayList<>();
    for (Path path : srcs) {
      pathStrings.add(path.toString());
    }
    if (pathStrings.size() > 0) {
      ImmutableList.Builder<String> commandBuilder =
          ImmutableList.<String>builder()
              .addAll(compilerCommandPrefix)
              .add("-p", packageName.toString())
              .add("-pack")
              .add("-trimpath", workingDirectory.toString())
              .add("-nolocalimports")
              .addAll(flags)
              .add("-o", output.toString());

      for (Path dir : includeDirectories) {
        commandBuilder.add("-I", dir.toString());
      }

      for (Map.Entry<Path, Path> entry : importPathMap.entrySet()) {
        commandBuilder.add("-importmap", entry.getKey() + "=" + entry.getValue());
      }

      if (asmHeaderPath.isPresent()) {
        commandBuilder.add("-asmhdr", asmHeaderPath.get().toString());
      }

      if (!allowExternalReferences) {
        // -complete means the package does not use any non Go code, so external functions
        // (e.g. Cgo, asm) aren't allowed.
        commandBuilder.add("-complete");
      }

      commandBuilder.addAll(pathStrings);

      return commandBuilder.build();
    } else {
      LOG.warn("No source files found in " + workingDirectory);
      return ImmutableList.of();
    }
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return ImmutableMap.<String, String>builder()
        .putAll(environment)
        .put("GOOS", platform.getGoOs())
        .put("GOARCH", platform.getGoArch())
        .put("GOARM", platform.getGoArm())
        .build();
  }

  @Override
  public String getShortName() {
    return "go compile";
  }
}
