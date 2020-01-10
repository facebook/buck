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

package com.facebook.buck.apple;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;

/** ShellStep for calling libtool. */
public class LibtoolStep extends ShellStep {

  /** Style of library to be created, static or dynamic. */
  public enum Style {
    STATIC,
    DYNAMIC,
  }

  private final ProjectFilesystem filesystem;
  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> libtoolCommand;
  private final Path argsfile;
  private final Path output;
  private final ImmutableList<String> flags;
  private final LibtoolStep.Style style;

  public LibtoolStep(
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> environment,
      ImmutableList<String> libtoolCommand,
      Path argsfile,
      Path output,
      ImmutableList<String> flags,
      LibtoolStep.Style style) {
    super(filesystem.getRootPath());
    this.filesystem = filesystem;
    this.environment = environment;
    this.libtoolCommand = libtoolCommand;
    this.argsfile = argsfile;
    this.output = output;
    this.flags = ImmutableList.copyOf(flags);
    this.style = style;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder.addAll(libtoolCommand);
    switch (style) {
      case STATIC:
        commandBuilder.add("-static");
        break;
      case DYNAMIC:
        commandBuilder.add("-dynamic");
        break;
    }
    commandBuilder.add("-o");
    commandBuilder.add(filesystem.resolve(output).toString());
    commandBuilder.add("-filelist");
    commandBuilder.add(filesystem.resolve(argsfile).toString());
    commandBuilder.addAll(flags);
    return commandBuilder.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return environment;
  }

  @Override
  public String getShortName() {
    return "libtool";
  }
}
