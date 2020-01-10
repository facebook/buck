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

package com.facebook.buck.sandbox.darwin;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.sandbox.SandboxProperties;
import com.facebook.buck.shell.programrunner.ProgramRunner;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;

/** {@link ProgramRunner} that runs a program in an OS X sandbox. */
public class DarwinSandboxProgramRunner implements ProgramRunner {

  private final DarwinSandbox darwinSandbox;

  public DarwinSandboxProgramRunner(SandboxProperties sandboxProperties) {
    this.darwinSandbox = new DarwinSandbox(sandboxProperties);
  }

  @Override
  public void prepareForRun(ProjectFilesystem projectFilesystem, Path programPath)
      throws IOException {
    SandboxProperties additionalSandboxProperties =
        SandboxProperties.builder()
            .addAllowedToReadPaths(programPath.getParent().toAbsolutePath().toString())
            .build();
    darwinSandbox.init(projectFilesystem, additionalSandboxProperties);
  }

  @Override
  public ImmutableList<String> enhanceCommandLine(ImmutableList<String> commandLine) {
    return ImmutableList.<String>builder()
        .addAll(darwinSandbox.createCommandLineArguments())
        .addAll(commandLine)
        .build();
  }

  @Override
  public ImmutableList<String> enhanceCommandLineForDescription(ImmutableList<String> commandLine) {
    return ImmutableList.<String>builder()
        .addAll(darwinSandbox.createCommandLineArgumentsForDescription())
        .addAll(commandLine)
        .build();
  }
}
