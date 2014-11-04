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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class RmStep implements Step {

  private final Path toDelete;
  private final boolean shouldForceDeletion;
  private final boolean shouldRecurse;

  public RmStep(Path toDelete, boolean shouldForceDeletion) {
    this(toDelete, shouldForceDeletion, false /* shouldRecurse */);
  }

  public RmStep(Path toDelete,
      boolean shouldForceDeletion,
      boolean shouldRecurse) {
    this.toDelete = toDelete;
    this.shouldForceDeletion = shouldForceDeletion;
    this.shouldRecurse = shouldRecurse;
  }

  public ImmutableList<String> getShellCommand(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("rm");

    if (shouldRecurse) {
      args.add("-r");
    }

    if (shouldForceDeletion) {
      args.add("-f");
    }

    Path absolutePath = context.getProjectFilesystem().resolve(toDelete);
    args.add(absolutePath.toString());

    return args.build();
  }

  @Override
  public String getShortName() {
    return "rm";
  }

  @Override
  public int execute(ExecutionContext context) {
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    if (shouldRecurse) {
      // Delete a folder recursively
      try {
        projectFilesystem.rmdir(toDelete);
      } catch (IOException e) {
        if (shouldForceDeletion) {
          return 0;
        }
        e.printStackTrace(context.getStdErr());
        return 1;
      }
    } else {
      // Delete a single file
      File file = projectFilesystem.resolve(toDelete).toFile();
      if (!file.delete() && !shouldForceDeletion) {
        return 1;
      }
    }
    return 0;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(" ").join(getShellCommand(context));
  }
}
