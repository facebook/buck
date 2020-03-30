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

package com.facebook.buck.features.go;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.shell.ShellStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;

public class CGoCompileStep extends ShellStep {

  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> cgoCommandPrefix;
  private final ImmutableList<String> cppCommandPrefix;
  private final ImmutableList<String> cgoCompilerFlags;
  private final ImmutableList<String> cxxCompilerFlags;
  private final ImmutableList<Path> srcs;
  private final GoPlatform platform;
  private final Path outputDir;

  public CGoCompileStep(
      AbsPath workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> cgoCommandPrefix,
      ImmutableList<String> cppCommandPrefix,
      ImmutableList<String> cgoCompilerFlags,
      ImmutableList<String> cxxCompilerFlags,
      ImmutableList<Path> srcs,
      GoPlatform platform,
      Path outputDir) {
    super(workingDirectory);
    this.environment = environment;
    this.cgoCommandPrefix = cgoCommandPrefix;
    this.cppCommandPrefix = cppCommandPrefix;
    this.cgoCompilerFlags = cgoCompilerFlags;
    this.cxxCompilerFlags = cxxCompilerFlags;
    this.srcs = srcs;
    this.outputDir = outputDir;
    this.platform = platform;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .addAll(cgoCommandPrefix)
        .add("-importpath", context.getBuildCellRootPath().toString())
        .add("-srcdir", context.getBuildCellRootPath().toString())
        .add("-objdir", outputDir.toString())
        .addAll(cgoCompilerFlags)
        .add("--")
        .addAll(cxxCompilerFlags)
        .addAll(srcs.stream().map(Object::toString).iterator())
        .build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return ImmutableMap.<String, String>builder()
        .putAll(environment)
        // cgo silently calls C preprocessor, so we need to set CC env in order
        // to use toolchain provided via cxxPlatform (not the system one)
        .put("CC", String.join(" ", cppCommandPrefix))
        .put("GOOS", platform.getGoOs().getEnvVarValue())
        .put("GOARCH", platform.getGoArch().getEnvVarValue())
        .put("GOARM", platform.getGoArch().getEnvVarValueForArm())
        .build();
  }

  @Override
  public String getShortName() {
    return "cgo compile";
  }
}
