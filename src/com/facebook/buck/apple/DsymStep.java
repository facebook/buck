/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.List;

/**
 * Invokes {@code dsymutil} to generate a dsym file.
 *
 * The linking step for apple executables does not actually embed debug information from the object
 * files that are being linked. Instead, the executable contains paths to object files from which
 * the debug information can be read. {@code dsymutil} reads this section of the executable and
 * collects the debugging information from all the mentioned object files and generates a
 * {@code .dSYM} bundle alongside the executable. This bundle is then loaded automatically by the
 * debugger for its debug information.
 *
 * @see <a href="http://wiki.dwarfstd.org/index.php?title=Apple%27s_%22Lazy%22_DWARF_Scheme">
 *   Information on DWARF and DSYM on Macs</a>
 */
public class DsymStep extends ShellStep {

  private final ProjectFilesystem filesystem;
  private final ImmutableList<String> command;
  private final Path input;
  private final Path output;

  public DsymStep(ProjectFilesystem filesystem, List<String> command, Path input, Path output) {
    super(filesystem.getRootPath());

    this.filesystem = filesystem;
    this.command = ImmutableList.copyOf(command);
    this.input = input;
    this.output = output;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();

    commandBuilder.addAll(command);
    commandBuilder.add(
        "-o", filesystem.resolve(output).toString(),
        filesystem.resolve(input).toString());

    return commandBuilder.build();
  }

  @Override
  public String getShortName() {
    return "dsymutil";
  }
}
