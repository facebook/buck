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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class AssembleDirectories extends AbstractBuildRule {

  private final Path destinationDirectory;
  private final ImmutableCollection<SourcePath> originalDirectories;

  public AssembleDirectories(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      ImmutableCollection<SourcePath> directories) {
    super(buildRuleParams, resolver);
    this.originalDirectories = directories;
    this.destinationDirectory = BuildTargets.getGenPath(
        buildRuleParams.getBuildTarget(),
        "__assembled_%s__");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(new MakeCleanDirectoryStep(destinationDirectory));
    for (SourcePath directory : originalDirectories) {
      Path resolvedPath = getResolver().getPath(directory);
      steps.add(CopyStep.forDirectory(
              resolvedPath,
              destinationDirectory,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }
    buildableContext.recordArtifact(destinationDirectory);
    return steps.build();
  }

  @Override
  public Path getPathToOutputFile() {
    return destinationDirectory;
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    ImmutableList.Builder<Path> builder = ImmutableList.builder();
    builder.addAll(getResolver().filterInputsToCompareToOutput(originalDirectories));
    return builder.build();
  }

  @Override
  protected Builder appendDetailsToRuleKey(Builder builder) {
    return builder;
  }
}
