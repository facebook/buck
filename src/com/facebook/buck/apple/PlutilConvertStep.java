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
package com.facebook.buck.apple;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class PlutilConvertStep extends ShellStep {

  public enum Format {
    xml1,
    binary1,
    json
  }

  private final ProjectFilesystem filesystem;
  private final ImmutableMap<String, String> environment;
  private final List<String> plutilCommand;
  private final Format format;
  private final Path input;
  private final Path output;

  public PlutilConvertStep(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> environment,
      List<String> plutilCommand,
      Format convertTo,
      Path input,
      Path output) {
    super(Optional.of(buildTarget), filesystem.getRootPath());
    this.filesystem = filesystem;
    this.environment = environment;
    this.plutilCommand = plutilCommand;
    this.format = convertTo;
    this.input = input;
    this.output = output;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder.addAll(plutilCommand);
    commandBuilder.add("-convert");
    commandBuilder.add(format.toString());
    commandBuilder.add(filesystem.resolve(input).toString());
    commandBuilder.add("-o");
    commandBuilder.add(filesystem.resolve(output).toString());
    return commandBuilder.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return environment;
  }

  @Override
  public String getShortName() {
    return "plutil";
  }
}
