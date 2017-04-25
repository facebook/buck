/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.cxx.StripStep;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

public class StripLinkable extends AbstractBuildRule {

  @AddToRuleKey private final Tool stripTool;

  @AddToRuleKey private final SourcePath sourcePathToStrip;

  @AddToRuleKey private final String strippedObjectName;

  private final Path resultDir;

  public StripLinkable(
      BuildRuleParams buildRuleParams,
      Tool stripTool,
      SourcePath sourcePathToStrip,
      String strippedObjectName) {
    super(buildRuleParams);
    this.stripTool = stripTool;
    this.strippedObjectName = strippedObjectName;
    this.sourcePathToStrip = sourcePathToStrip;
    this.resultDir =
        BuildTargets.getGenPath(getProjectFilesystem(), buildRuleParams.getBuildTarget(), "%s");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(MkdirStep.of(getProjectFilesystem(), resultDir));
    Path output = context.getSourcePathResolver().getRelativePath(getSourcePathToOutput());
    steps.add(
        new StripStep(
            getProjectFilesystem().getRootPath(),
            stripTool.getEnvironment(context.getSourcePathResolver()),
            stripTool.getCommandPrefix(context.getSourcePathResolver()),
            ImmutableList.of("--strip-unneeded"),
            context.getSourcePathResolver().getAbsolutePath(sourcePathToStrip),
            output));

    buildableContext.recordArtifact(output);

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(
        getBuildTarget(), resultDir.resolve(strippedObjectName));
  }
}
