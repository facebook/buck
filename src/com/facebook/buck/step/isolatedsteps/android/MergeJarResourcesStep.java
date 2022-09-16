/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import com.facebook.buck.android.resources.MergeThirdPartyJarResourcesUtils;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Collectors;

/** Step to construct build outputs for exo-for-resources. */
public class MergeJarResourcesStep extends IsolatedStep {

  private final ImmutableSortedSet<RelPath> pathsToJars;
  private final Path mergedPath;

  public MergeJarResourcesStep(ImmutableSortedSet<RelPath> pathsToJars, Path mergedPath) {
    this.pathsToJars = pathsToJars;
    this.mergedPath = mergedPath;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException {
    MergeThirdPartyJarResourcesUtils.mergeThirdPartyJarResources(
        context.getRuleCellRoot(), pathsToJars, mergedPath);

    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "merge_jar_resources";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return String.join(
        " ",
        "merge_jar_resources",
        mergedPath.toString(),
        pathsToJars.stream().map(RelPath::toString).collect(Collectors.joining(",")));
  }
}
