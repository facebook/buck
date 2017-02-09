/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.step.fs;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

public class RmStep implements Step {

  private static final Logger LOG = Logger.get(RmStep.class);

  private final ProjectFilesystem filesystem;
  private final Path toDelete;
  private final Set<Mode> modes;

  public RmStep(
      ProjectFilesystem filesystem,
      Path toDelete,
      Set<Mode> modes) {
    this.filesystem = filesystem;
    this.toDelete = toDelete;
    this.modes = modes;
  }

  public RmStep(ProjectFilesystem filesystem, Path toDelete, Mode... modes) {
    this(
        filesystem,
        toDelete,
        modes.length > 0 ? EnumSet.copyOf(Arrays.asList(modes)) : EnumSet.noneOf(Mode.class));
  }

  public ImmutableList<String> getShellCommand() {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("rm");
    args.add("-f");

    for (Mode mode : modes) {
      args.add(mode.toShellArgument());
    }

    Path absolutePath = filesystem.resolve(toDelete);
    args.add(absolutePath.toString());

    return args.build();
  }

  @Override
  public String getShortName() {
    return "rm";
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) {
    try {
      if (shouldRecurse()) {
        // Delete a folder recursively
        filesystem.deleteRecursivelyIfExists(toDelete);
      } else {
        // Delete a single file
        filesystem.deleteFileAtPathIfExists(toDelete);
      }
    } catch (IOException e) {
      LOG.error(e);
      return StepExecutionResult.ERROR;
    }
    return StepExecutionResult.SUCCESS;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(" ").join(getShellCommand());
  }

  private boolean shouldRecurse() {
    return modes.contains(Mode.RECURSIVE);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof RmStep)) {
      return false;
    }
    return getShellCommand().equals(((RmStep) obj).getShellCommand());
  }

  @Override
  public int hashCode() {
    return getShellCommand().hashCode();
  }

  public enum Mode {
    RECURSIVE("-r");

    private final String shellArgument;

    Mode(String shellArgument) {
      this.shellArgument = shellArgument;
    }

    String toShellArgument() {
      return shellArgument;
    }
  }
}
