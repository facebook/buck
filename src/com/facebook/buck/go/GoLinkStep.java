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

package com.facebook.buck.go;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class GoLinkStep extends ShellStep {
  enum LinkMode {
    EXECUTABLE("exe");
    // Other gc modes: http://blog.ralch.com/tutorial/golang-sharing-libraries/

    private final String buildMode;
    LinkMode(String buildMode) {
      this.buildMode = buildMode;
    }

    String getBuildMode() {
      return buildMode;
    }
  }

  private final ImmutableList<String> cxxLinkCommandPrefix;
  private final ImmutableList<String> linkCommandPrefix;
  private final ImmutableList<String> flags;
  private final ImmutableList<Path> libraryPaths;
  private final Path mainArchive;
  private final LinkMode linkMode;
  private final Path output;

  public GoLinkStep(
      Path workingDirectory,
      ImmutableList<String> cxxLinkCommandPrefix,
      ImmutableList<String> linkCommandPrefix,
      ImmutableList<String> flags,
      ImmutableList<Path> libraryPaths,
      Path mainArchive,
      LinkMode linkMode,
      Path output) {
    super(workingDirectory);
    this.cxxLinkCommandPrefix = cxxLinkCommandPrefix;
    this.linkCommandPrefix = linkCommandPrefix;
    this.flags = flags;
    this.libraryPaths = libraryPaths;
    this.mainArchive = mainArchive;
    this.linkMode = linkMode;
    this.output = output;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> command = ImmutableList.<String>builder()
        .addAll(linkCommandPrefix)
        .addAll(flags)
        .add("-o", output.toString())
        .add("-buildmode", linkMode.getBuildMode());

    for (Path libraryPath : libraryPaths) {
      command.add("-L", libraryPath.toString());
    }

    if (cxxLinkCommandPrefix.size() > 0) {
      command.add("-extld", cxxLinkCommandPrefix.get(0));
      if (cxxLinkCommandPrefix.size() > 1) {
        command.add(
            "-extldflags",
            FluentIterable.from(cxxLinkCommandPrefix).skip(1)
                .transform(Escaper.BASH_ESCAPER).join(Joiner.on(" ")));
      }
    }
    command.add(mainArchive.toString());

    return command.build();
  }

  @Override
  public String getShortName() {
    return "go link";
  }
}
