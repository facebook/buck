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

package com.facebook.buck.apple;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.List;

/**
 * {@link ShellStep} implementation which invokes Apple's {@code ibtool}
 * utility to compile {@code XIB} files to {@code NIB} files.
 */
public class IbtoolStep extends ShellStep {

  private final ImmutableList<String> ibtoolCommand;
  private final Path input;
  private final Path output;

  public IbtoolStep(
      List<String> ibtoolCommand,
      Path input,
      Path output) {
    this.ibtoolCommand = ImmutableList.copyOf(ibtoolCommand);
    this.input = input;
    this.output = output;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();

    commandBuilder.addAll(ibtoolCommand);
    commandBuilder.add(
        "--output-format", "human-readable-text",
        "--notices",
        "--warnings",
        "--errors",
        "--compile",
        context.getProjectFilesystem().resolve(output).toString(),
        context.getProjectFilesystem().resolve(input).toString());

    return commandBuilder.build();
  }

  @Override
  public String getShortName() {
    return "ibtool";
  }
}
