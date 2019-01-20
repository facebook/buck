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
  enum ListType {
    GoFiles,
    CgoFiles,
    SFiles,
    HFiles,
    TestGoFiles,
    XTestGoFiles,
    Name
  }

  private final List<ListType> listTypes;
  private final GoPlatform platform;

  // File relative to the workspace. It should be used only if go list is going
  // to read the information from specific file (not the directory / package)
  private final Optional<Path> targetFile;

  public GoListStep(
      Path workingDirectory,
      Optional<Path> targetFile,
      GoPlatform platform,
      List<ListType> listTypes) {
    super(workingDirectory);
    this.targetFile = targetFile;
    this.platform = platform;
    this.listTypes = listTypes;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder
        .add(platform.getGoRoot().resolve("bin").resolve("go").toString())
        .add("list")
        .add("-e")
        .add("-f");

    if (listTypes.get(0) == ListType.Name) {
      commandBuilder.add("{{ ." + listTypes.get(0).name() + "}}");
    } else {
      commandBuilder.add(
          listTypes
              .stream()
              .map(fileType -> "{{join ." + fileType.name() + " \":\"}}")
              .collect(Collectors.joining(":")));
    }

    if (targetFile.isPresent()) {
      commandBuilder.add(targetFile.get().toString());
    }

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
        // without this env variable go tool list tries to download packages from go.mod
        // for source files outside GOROOT that's always true for Buck
        .put("GO111MODULE", "off")
        .build();
  }

  public String getRawOutput() {
    return getStdout().trim();
  }

  public Set<Path> getSourceFiles() {
    String stdout = getStdout();
    return Arrays.stream(stdout.trim().split(":"))
        .map(workingDirectory::resolve)
        .collect(Collectors.toSet());
  }
}
