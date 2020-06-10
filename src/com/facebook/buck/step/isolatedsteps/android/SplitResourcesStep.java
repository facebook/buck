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
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import java.io.IOException;

/** Step to construct build outputs for exo-for-resources. */
public class SplitResourcesStep implements Step {

  private final RelPath pathToAaptResources;
  private final RelPath pathToOriginalRDotTxt;
  private final RelPath pathToPrimaryResourceOutputPath;
  private final RelPath pathToUnalignedExoPath;
  private final RelPath pathTorDotTxtOutputPath;

  public SplitResourcesStep(
      RelPath pathToAaptResources,
      RelPath pathToOriginalRDotTxt,
      RelPath pathToPrimaryResourceOutputPath,
      RelPath pathToUnalignedExoPath,
      RelPath pathTorDotTxtOutputPath) {
    this.pathToAaptResources = pathToAaptResources;
    this.pathToOriginalRDotTxt = pathToOriginalRDotTxt;
    this.pathToPrimaryResourceOutputPath = pathToPrimaryResourceOutputPath;
    this.pathToUnalignedExoPath = pathToUnalignedExoPath;
    this.pathTorDotTxtOutputPath = pathTorDotTxtOutputPath;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) throws IOException {
    ExoResourcesRewriter.rewrite(
        context.getRuleCellRoot(),
        pathToAaptResources,
        pathToOriginalRDotTxt,
        pathToPrimaryResourceOutputPath,
        pathToUnalignedExoPath,
        pathTorDotTxtOutputPath);
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "splitting_exo_resources";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return String.format("split_exo_resources %s %s", pathToAaptResources, pathToOriginalRDotTxt);
  }
}
