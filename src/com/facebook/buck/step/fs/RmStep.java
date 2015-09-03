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
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;

public class RmStep implements Step {

  private static final Logger LOG = Logger.get(RmStep.class);

  private final ProjectFilesystem filesystem;
  private final Path toDelete;
  private final boolean shouldForceDeletion;
  private final boolean shouldRecurse;

  public RmStep(ProjectFilesystem filesystem, Path toDelete, boolean shouldForceDeletion) {
    this(filesystem, toDelete, shouldForceDeletion, false /* shouldRecurse */);
  }

  public RmStep(
      ProjectFilesystem filesystem,
      Path toDelete,
      boolean shouldForceDeletion,
      boolean shouldRecurse) {
    this.filesystem = filesystem;
    this.toDelete = toDelete;
    this.shouldForceDeletion = shouldForceDeletion;
    this.shouldRecurse = shouldRecurse;
  }

  public ImmutableList<String> getShellCommand() {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("rm");

    if (shouldRecurse) {
      args.add("-r");
    }

    if (shouldForceDeletion) {
      args.add("-f");
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
  public int execute(ExecutionContext context) {
    try {
      if (shouldRecurse) {
        // Delete a folder recursively
        if (shouldForceDeletion) {
          filesystem.deleteRecursivelyIfExists(toDelete);
        } else {
          filesystem.deleteRecursively(toDelete);
        }
      } else {
        // Delete a single file
        if (shouldForceDeletion) {
          filesystem.deleteFileAtPathIfExists(toDelete);
        } else {
          filesystem.deleteFileAtPath(toDelete);
        }
      }
    } catch (IOException e) {
      LOG.error(e);
      return 1;
    }
    return 0;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(" ").join(getShellCommand());
  }
}
