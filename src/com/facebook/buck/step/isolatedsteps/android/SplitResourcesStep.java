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

package com.facebook.buck.step.isolatedsteps.android;

import com.facebook.buck.android.resources.ExoResourcesRewriter;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import java.io.IOException;
import java.nio.file.Path;

/** Step to construct build outputs for exo-for-resources. */
public class SplitResourcesStep implements Step {
  private final Path absolutePathToAaptResources;
  private final Path absolutePathToOriginalRDotTxt;
  private final Path relativePathToPrimaryResourceOutputPath;
  private final Path relativePathToUnalignedExoPath;
  private final Path relativePathTorDotTxtOutputPath;

  public SplitResourcesStep(
      Path absolutePathToAaptResources,
      Path absolutePathToOriginalRDotTxt,
      Path relativePathToPrimaryResourceOutputPath,
      Path relativePathToUnalignedExoPath,
      Path relativePathTorDotTxtOutputPath) {
    this.absolutePathToAaptResources = absolutePathToAaptResources;
    this.absolutePathToOriginalRDotTxt = absolutePathToOriginalRDotTxt;
    this.relativePathToPrimaryResourceOutputPath = relativePathToPrimaryResourceOutputPath;
    this.relativePathToUnalignedExoPath = relativePathToUnalignedExoPath;
    this.relativePathTorDotTxtOutputPath = relativePathTorDotTxtOutputPath;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    ExoResourcesRewriter.rewrite(
        absolutePathToAaptResources,
        absolutePathToOriginalRDotTxt,
        relativePathToPrimaryResourceOutputPath,
        relativePathToUnalignedExoPath,
        relativePathTorDotTxtOutputPath);
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "splitting_exo_resources";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        "split_exo_resources %s %s", absolutePathToAaptResources, absolutePathToOriginalRDotTxt);
  }
}
