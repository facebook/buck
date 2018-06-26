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

package com.facebook.buck.features.go;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProcessExecutor.Option;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class GoListStep extends ShellStep {
  enum FileType {
    GoFiles,
    CgoFiles,
    SFiles,
    HFiles,
    TestGoFiles,
    XTestGoFiles
  }

  private final List<FileType> fileTypes;
  private final GoPlatform platform;

  public GoListStep(
      BuildTarget buildTarget,
      Path workingDirectory,
      GoPlatform platform,
      List<FileType> fileTypes) {
    super(Optional.of(buildTarget), workingDirectory);
    this.platform = platform;
    this.fileTypes = fileTypes;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder =
        ImmutableList.<String>builder()
            .add(platform.getGoRoot().resolve("bin").resolve("go").toString())
            .add("list")
            .add("-e")
            .add("-f")
            .add(
                String.join(
                    ":",
                    fileTypes
                        .stream()
                        .map(fileType -> "{{join ." + fileType.name() + " \":\"}}")
                        .collect(Collectors.toList())));

    return commandBuilder.build();
  }

  @Override
  public String getShortName() {
    return "go list";
  }

  @Override
  protected void addOptions(ImmutableSet.Builder<Option> options) {
    super.addOptions(options);
    options.add(Option.EXPECTING_STD_OUT);
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return ImmutableMap.<String, String>builder()
        // The go list command relies on these environment variables, so they are set here
        // in case the inherited/default ones are wrong
        .put("GOROOT", platform.getGoRoot().toString())
        .put("GOOS", platform.getGoOs())
        .put("GOARCH", platform.getGoArch())
        .put("GOARM", platform.getGoArm())
        .build();
  }

  public Set<Path> getSourceFiles() {
    String stdout = getStdout();
    return Arrays.stream(stdout.trim().split(":"))
        .map(workingDirectory::resolve)
        .collect(Collectors.toSet());
  }
}
