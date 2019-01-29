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

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@link ShellStep} implementation which invokes Apple's {@code ibtool} utility to compile {@code
 * XIB} files to {@code NIB} files.
 */
class IbtoolStep extends ShellStep {

  private final ProjectFilesystem filesystem;
  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> ibtoolCommand;
  private final Optional<String> moduleName;
  private final Path input;
  private final Path output;
  private final ImmutableList<String> flags;

  public IbtoolStep(
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> environment,
      List<String> ibtoolCommand,
      Optional<String> moduleName,
      List<String> flags,
      Path input,
      Path output) {
    super(filesystem.getRootPath());
    this.filesystem = filesystem;
    this.environment = environment;
    this.ibtoolCommand = ImmutableList.copyOf(ibtoolCommand);
    this.moduleName = moduleName;
    this.input = input;
    this.output = output;
    this.flags = ImmutableList.copyOf(flags);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();

    commandBuilder.addAll(ibtoolCommand);
    if (moduleName.isPresent()) {
      commandBuilder.add("--module");
      commandBuilder.add(moduleName.orElse(""));
    }
    commandBuilder.addAll(flags);
    commandBuilder.add(filesystem.resolve(output).toString(), filesystem.resolve(input).toString());

    return commandBuilder.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return environment;
  }

  @Override
  public String getShortName() {
    return "ibtool";
  }
}
