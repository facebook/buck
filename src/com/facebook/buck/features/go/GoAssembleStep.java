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

package com.facebook.buck.features.go;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.shell.ShellStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.ArrayList;

public class GoAssembleStep extends ShellStep {

  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> asmCommandPrefix;
  private final ImmutableList<String> flags;
  private final Iterable<Path> srcs;
  private final ImmutableList<Path> includeDirectories;
  private final GoPlatform platform;
  private final Path output;

  public GoAssembleStep(
      Path workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> asmCommandPrefix,
      ImmutableList<String> flags,
      Iterable<Path> srcs,
      ImmutableList<Path> includeDirectories,
      GoPlatform platform,
      Path output) {
    super(workingDirectory);
    this.environment = environment;
    this.asmCommandPrefix = asmCommandPrefix;
    this.flags = flags;
    this.srcs = srcs;
    this.includeDirectories = includeDirectories;
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
              .addAll(asmCommandPrefix)
              .add("-trimpath", workingDirectory.toString())
              .addAll(flags)
              .add("-D", "GOOS_" + platform.getGoOs())
              .add("-D", "GOARCH_" + platform.getGoArch())
              .add("-o", output.toString());

      for (Path dir : includeDirectories) {
        commandBuilder.add("-I", dir.toString());
      }
      commandBuilder.addAll(pathStrings);
      return commandBuilder.build();
    } else {
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
    return "go asm";
  }
}
