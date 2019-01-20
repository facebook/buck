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

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.stream.Collectors;

public class CGoCompileStep extends ShellStep {

  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> cgoCommandPrefix;
  private final ImmutableList<String> cgoCompilerFlags;
  private final ImmutableList<Path> srcs;
  private final ImmutableList<Path> includeDirs;
  private final GoPlatform platform;
  private final Path outputDir;

  public CGoCompileStep(
      Path workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> cgoCommandPrefix,
      ImmutableList<String> cgoCompilerFlags,
      ImmutableList<Path> srcs,
      ImmutableList<Path> includeDirs,
      GoPlatform platform,
      Path outputDir) {
    super(workingDirectory);
    this.environment = environment;
    this.cgoCommandPrefix = cgoCommandPrefix;
    this.cgoCompilerFlags = cgoCompilerFlags;
    this.srcs = srcs;
    this.includeDirs = includeDirs;
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
        .addAll(includeDirs.stream().map(dir -> "-I" + dir.toString()).collect(Collectors.toSet()))
        .addAll(srcs.stream().map(Object::toString).iterator())
        .build();
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
    return "cgo compile";
  }
}
