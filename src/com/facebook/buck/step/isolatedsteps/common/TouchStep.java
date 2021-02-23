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
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import java.io.IOException;
import java.nio.file.Path;

/** {@link IsolatedStep} that runs {@code touch <filename>} in the shell. */
public class TouchStep extends IsolatedStep {
  private final Path fileToTouch;

  public TouchStep(Path fileToTouch) {
    this.fileToTouch = fileToTouch;
  }

  public TouchStep(RelPath fileToTouch) {
    this(fileToTouch.getPath());
  }

  @Override
  public String getShortName() {
    return "touch";
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    ProjectFilesystemUtils.touch(context.getRuleCellRoot(), fileToTouch);
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return "touch "
        + ProjectFilesystemUtils.getPathForRelativePath(context.getRuleCellRoot(), fileToTouch);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof TouchStep)) {
      return false;
    }

    TouchStep touchStep = (TouchStep) o;

    return fileToTouch.equals(touchStep.fileToTouch);
  }

  @Override
  public int hashCode() {
    return fileToTouch.hashCode();
  }
}
