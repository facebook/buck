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
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class GoCompileStep extends ShellStep {

  private final ImmutableList<String> compilerCommandPrefix;
  private final Path packageName;
  private final ImmutableList<String> flags;
  private final ImmutableList<Path> srcs;
  private ImmutableList<Path> includeDirectories;
  private final Path output;

  public GoCompileStep(
      Path workingDirectory,
      ImmutableList<String> compilerCommandPrefix,
      ImmutableList<String> flags,
      Path packageName,
      ImmutableList<Path> srcs,
      ImmutableList<Path> includeDirectories,
      Path output) {
    super(workingDirectory);
    this.compilerCommandPrefix = compilerCommandPrefix;
    this.flags = flags;
    this.packageName = packageName;
    this.srcs = srcs;
    this.includeDirectories = includeDirectories;
    this.output = output;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.<String>builder()
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

    commandBuilder.addAll(FluentIterable.from(srcs).transform(
            new Function<Path, String>() {
              @Override
              public String apply(Path input) {
                return input.toString();
              }
            }));

    return commandBuilder.build();
  }

  @Override
  public String getShortName() {
    return "go compile";
  }
}
