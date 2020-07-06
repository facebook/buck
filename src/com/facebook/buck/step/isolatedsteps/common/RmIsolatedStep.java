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

package com.facebook.buck.step.isolatedsteps.common;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import java.io.IOException;
import java.nio.file.Files;
import java.util.StringJoiner;

/** Removes a path if it exists. */
@BuckStyleValue
public abstract class RmIsolatedStep extends IsolatedStep {

  abstract RelPath getPath();

  abstract boolean isRecursive();

  @Override
  public String getShortName() {
    return "rm";
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException {

    AbsPath absolutePath = getAbsPath(context);

    if (isRecursive()) {
      // Delete a folder recursively
      MostFiles.deleteRecursivelyIfExists(absolutePath);
    } else {
      // Delete a single file
      Files.deleteIfExists(absolutePath.getPath());
    }
    return StepExecutionResults.SUCCESS;
  }

  private AbsPath getAbsPath(IsolatedExecutionContext context) {
    return ProjectFilesystemUtils.getAbsPathForRelativePath(context.getRuleCellRoot(), getPath());
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    StringJoiner args = new StringJoiner(" ");
    args.add("rm");
    args.add("-f");

    if (isRecursive()) {
      args.add("-r");
    }

    args.add(getAbsPath(context).toString());

    return args.toString();
  }

  public static RmIsolatedStep of(RelPath path, boolean recursive) {
    return ImmutableRmIsolatedStep.ofImpl(path, recursive);
  }

  public static RmIsolatedStep of(RelPath path) {
    return of(path, false);
  }
}
