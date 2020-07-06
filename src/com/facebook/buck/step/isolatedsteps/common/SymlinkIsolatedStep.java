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

import static com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils.getAbsPathForRelativePath;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import java.io.IOException;

/** Creates a symlink from a desired path to an existing path. */
@BuckStyleValue
public abstract class SymlinkIsolatedStep extends IsolatedStep {

  abstract RelPath getExistingPath();

  abstract RelPath getDesiredPath();

  @Override
  public String getShortName() {
    return "symlink_file";
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException {
    AbsPath ruleCellRoot = context.getRuleCellRoot();

    AbsPath existingAbsPath = getAbsPathForRelativePath(ruleCellRoot, getExistingPath());
    AbsPath desiredAbsPath = getAbsPathForRelativePath(ruleCellRoot, getDesiredPath());

    ProjectFilesystemUtils.createSymLink(
        ruleCellRoot, desiredAbsPath.getPath(), existingAbsPath.getPath(), /* force */ true);

    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    AbsPath ruleCellRoot = context.getRuleCellRoot();
    return String.join(
        " ",
        "ln",
        "-f",
        "-s",
        getAbsPathForRelativePath(ruleCellRoot, getExistingPath()).toString(),
        getAbsPathForRelativePath(ruleCellRoot, getDesiredPath()).toString());
  }

  public static SymlinkIsolatedStep of(RelPath existingPath, RelPath desiredPath) {
    return ImmutableSymlinkIsolatedStep.ofImpl(existingPath, desiredPath);
  }
}
