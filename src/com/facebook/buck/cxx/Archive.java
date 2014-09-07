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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * A {@link com.facebook.buck.rules.BuildRule} which builds an "ar" archive from input files
 * represented as {@link com.facebook.buck.rules.SourcePath}.
 */
public class Archive extends AbstractBuildRule {

  private final Path archiver;
  private final Path output;
  private final ImmutableList<SourcePath> inputs;

  public Archive(
      BuildRuleParams params,
      Path archiver,
      Path output,
      ImmutableList<SourcePath> inputs) {
    super(params);
    this.archiver = Preconditions.checkNotNull(archiver);
    this.output = Preconditions.checkNotNull(output);
    this.inputs = Preconditions.checkNotNull(inputs);
  }

  @Override
  protected Iterable<Path> getInputsToCompareToOutput() {
    return SourcePaths.toPaths(inputs);
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setInput("archiver", archiver)
        .set("output", output.toString());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    // Cache the archive we built.
    buildableContext.recordArtifact(output);

    return ImmutableList.of(
        new MkdirStep(output.getParent()),
        new RmStep(output, /* shouldForceDeletion */ true),
        new ArchiveStep(archiver, output, SourcePaths.toPaths(inputs)),
        new ArchiveScrubberStep(output));
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }

}
