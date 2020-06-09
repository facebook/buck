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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.common.AbstractIsolatedExecutionStep;
import java.io.IOException;

/** Make Missing Output directories step. */
public class MakeMissingOutputsStep extends AbstractIsolatedExecutionStep {

  private final RelPath rootOutput;
  private final RelPath pathToClassHashes;
  private final RelPath annotationsPath;

  public MakeMissingOutputsStep(
      RelPath rootOutput, RelPath pathToClassHashes, RelPath annotationsPath) {
    super("make_missing_outputs");
    this.rootOutput = rootOutput;
    this.pathToClassHashes = pathToClassHashes;
    this.annotationsPath = annotationsPath;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException {
    AbsPath root = context.getRuleCellRoot();
    if (!ProjectFilesystemUtils.exists(root, rootOutput.getPath())) {
      ProjectFilesystemUtils.mkdirs(root, rootOutput.getPath());
    }
    if (!ProjectFilesystemUtils.exists(root, pathToClassHashes.getPath())) {
      ProjectFilesystemUtils.createParentDirs(root, pathToClassHashes.getPath());
      ProjectFilesystemUtils.touch(root, pathToClassHashes.getPath());
    }
    if (!ProjectFilesystemUtils.exists(root, annotationsPath.getPath())) {
      ProjectFilesystemUtils.mkdirs(root, annotationsPath.getPath());
    }
    return StepExecutionResults.SUCCESS;
  }
}
