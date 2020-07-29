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

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Computes the map "relative path to item" to "content hash of the item" for first level items of
 * the given directory.
 */
public class AppleComputeDirectoryFirstLevelContentHashesStep extends AbstractExecutionStep {

  private final AbsPath dirPath;
  private final ProjectFilesystem projectFilesystem;
  private final ImmutableMap.Builder<RelPath, String> pathToHashBuilder;

  public AppleComputeDirectoryFirstLevelContentHashesStep(
      AbsPath dirPath,
      ProjectFilesystem projectFilesystem,
      ImmutableMap.Builder<RelPath, String> pathToHashBuilder) {
    super("apple-compute-directory-first-level-content-hashes");
    this.dirPath = dirPath;
    this.projectFilesystem = projectFilesystem;
    this.pathToHashBuilder = pathToHashBuilder;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {

    for (RelPath path : collectFirstLevelItems()) {
      AbsPath absPath = dirPath.resolve(path);
      StringBuilder hashBuilder = new StringBuilder();
      Step substep;
      if (projectFilesystem.isDirectory(absPath)) {
        substep = new AppleComputeDirectoryContentHashStep(hashBuilder, absPath, projectFilesystem);
      } else {
        substep =
            new AppleComputeFileHashStep(hashBuilder, absPath, false, projectFilesystem, true);
      }
      StepExecutionResult subresult = substep.execute(context);
      if (!subresult.isSuccess()) {
        return subresult;
      }
      pathToHashBuilder.put(path, hashBuilder.toString());
    }

    return StepExecutionResults.SUCCESS;
  }

  private ImmutableList<RelPath> collectFirstLevelItems() {
    return Stream.of(Objects.requireNonNull(new File(dirPath.toUri()).list()))
        .map(dirPath::resolve)
        .map(filePath -> dirPath.relativize(AbsPath.of(filePath.getPath())))
        .collect(ImmutableList.toImmutableList());
  }
}
