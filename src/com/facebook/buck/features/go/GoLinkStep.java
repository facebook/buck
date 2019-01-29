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

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.util.Escaper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GoLinkStep extends ShellStep {

  enum BuildMode {
    EXECUTABLE("exe");
    // Other gc modes: http://blog.ralch.com/tutorial/golang-sharing-libraries/

    private final String buildMode;

    BuildMode(String buildMode) {
      this.buildMode = buildMode;
    }

    String getBuildMode() {
      return buildMode;
    }
  }

  enum LinkMode {
    INTERNAL("internal"),
    EXTERNAL("external");

    private final String linkMode;

    LinkMode(String linkMode) {
      this.linkMode = linkMode;
    }

    String getLinkMode() {
      return linkMode;
    }
  }

  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> cxxLinkCommandPrefix;
  private final ImmutableList<String> linkCommandPrefix;
  private final ImmutableList<String> linkerFlags;
  private final ImmutableList<String> externalLinkerFlags;
  private final ImmutableList<Path> libraryPaths;
  private final GoPlatform platform;
  private final Path mainArchive;
  private final BuildMode buildMode;
  private final LinkMode linkMode;
  private final Path output;

  public GoLinkStep(
      Path workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> cxxLinkCommandPrefix,
      ImmutableList<String> linkCommandPrefix,
      ImmutableList<String> linkerFlags,
      ImmutableList<String> externalLinkerFlags,
      ImmutableList<Path> libraryPaths,
      GoPlatform platform,
      Path mainArchive,
      BuildMode buildMode,
      LinkMode linkMode,
      Path output) {
    super(workingDirectory);
    this.environment = environment;
    this.cxxLinkCommandPrefix = cxxLinkCommandPrefix;
    this.linkCommandPrefix = linkCommandPrefix;
    this.linkerFlags = linkerFlags;
    this.externalLinkerFlags = externalLinkerFlags;
    this.libraryPaths = libraryPaths;
    this.platform = platform;
    this.mainArchive = mainArchive;
    this.buildMode = buildMode;
    this.linkMode = linkMode;
    this.output = output;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> command =
        ImmutableList.<String>builder()
            .addAll(linkCommandPrefix)
            .addAll(linkerFlags)
            .add("-o", output.toString())
            .add("-buildmode", buildMode.getBuildMode())
            .add("-linkmode", linkMode.getLinkMode());

    for (Path libraryPath : libraryPaths) {
      command.add("-L", libraryPath.toString());
    }

    if (linkMode == LinkMode.EXTERNAL) {
      command.add("-extld", cxxLinkCommandPrefix.get(0));
      if (cxxLinkCommandPrefix.size() > 1 || externalLinkerFlags.size() > 0) {
        command.add(
            "-extldflags="
                + Stream.concat(cxxLinkCommandPrefix.stream().skip(1), externalLinkerFlags.stream())
                    .map(Escaper.BASH_ESCAPER)
                    .collect(Collectors.joining(" ")));
      }
    }
    command.add(mainArchive.toString());

    return command.build();
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
    return "go link";
  }
}
