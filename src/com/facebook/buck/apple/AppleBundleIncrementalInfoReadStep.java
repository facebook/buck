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
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.BuildStepResultHolder;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import java.io.IOException;

/** Separate step to read {@link AppleBundleIncrementalInfo} from disk. */
public class AppleBundleIncrementalInfoReadStep extends AbstractExecutionStep {

  private ProjectFilesystem filesystem;
  private AbsPath hashesFilePath;
  private BuildStepResultHolder<AppleBundleIncrementalInfo> incrementalInfoHolder;

  AppleBundleIncrementalInfoReadStep(
      ProjectFilesystem filesystem,
      AbsPath hashesFilePath,
      BuildStepResultHolder<AppleBundleIncrementalInfo> incrementalInfoHolder) {
    super("apple-bundle-read-incremental-info");
    this.filesystem = filesystem;
    this.hashesFilePath = hashesFilePath;
    this.incrementalInfoHolder = incrementalInfoHolder;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    if (filesystem.exists(hashesFilePath.getPath())) {
      JsonParser parser = ObjectMappers.createParser(hashesFilePath.getPath());
      AppleBundleIncrementalInfo incrementalInfo =
          parser.readValueAs(AppleBundleIncrementalInfo.class);
      incrementalInfoHolder.setValue(incrementalInfo);
    }
    return StepExecutionResults.SUCCESS;
  }
}
