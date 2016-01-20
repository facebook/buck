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
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class Zip extends AbstractBuildRule {

  @AddToRuleKey(stringify = true)
  private final Path output;
  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> sources;
  private final Path scratchDir;

  public Zip(
      BuildRuleParams params,
      SourcePathResolver resolver,
      String outputName,
      ImmutableSortedSet<SourcePath> sources) {
    super(params, resolver);
    this.sources = Preconditions.checkNotNull(sources);

    this.output = BuildTargets.getGenPath(getBuildTarget(), outputName);
    this.scratchDir = BuildTargets.getScratchPath(getBuildTarget(), "%s.zip.scratch");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new RmStep(getProjectFilesystem(), output, true));
    steps.add(new MkdirStep(getProjectFilesystem(), output.getParent()));
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir));

    SrcZipAwareFileBundler bundler = new SrcZipAwareFileBundler(getBuildTarget());
    bundler.copy(getProjectFilesystem(), getResolver(), steps, scratchDir, sources, false);

    steps.add(
        new ZipStep(
            getProjectFilesystem(),
            output,
            ImmutableSortedSet.<Path>of(),
            /* junk paths */ false,
            ZipStep.DEFAULT_COMPRESSION_LEVEL,
            scratchDir));

    buildableContext.recordArtifact(output);

    return steps.build();
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }
}
