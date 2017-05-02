/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.zip;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

public class Zip extends AbstractBuildRule implements HasOutputName, SupportsInputBasedRuleKey {

  @AddToRuleKey private final String name;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> sources;

  public Zip(BuildRuleParams params, String outputName, ImmutableSortedSet<SourcePath> sources) {
    super(params);
    this.name = outputName;
    this.sources = sources;
  }

  private Path getOutput() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s")
        .resolve(this.name);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    Path output = getOutput();
    Path scratchDir =
        BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s.zip.scratch");

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(RmStep.of(getProjectFilesystem(), output));
    steps.add(MkdirStep.of(getProjectFilesystem(), output.getParent()));
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), scratchDir));

    SrcZipAwareFileBundler bundler = new SrcZipAwareFileBundler(getBuildTarget());
    bundler.copy(
        getProjectFilesystem(), context.getSourcePathResolver(), steps, scratchDir, sources);

    steps.add(
        new ZipStep(
            getProjectFilesystem(),
            output,
            ImmutableSortedSet.of(),
            /* junk paths */ false,
            ZipCompressionLevel.DEFAULT_COMPRESSION_LEVEL,
            scratchDir));

    buildableContext.recordArtifact(output);

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), getOutput());
  }

  @Override
  public String getOutputName() {
    return name;
  }
}
