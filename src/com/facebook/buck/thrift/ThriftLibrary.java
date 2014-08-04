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

package com.facebook.buck.thrift;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class ThriftLibrary extends AbstractBuildRule {
  private ImmutableSortedSet<SourcePath> srcs;
  private ImmutableSortedSet<String> langs = ImmutableSortedSet.<String>of("java");

  public ThriftLibrary(
      BuildRuleParams params,
      ImmutableSortedSet<SourcePath> srcs) {
    super(params);
    this.srcs = Preconditions.checkNotNull(srcs);
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder.set("langs", langs);
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(srcs);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    final Path genPath = BuildTargets.getGenPath(getBuildTarget(), "__thrift_%s__");
    steps.add(new MakeCleanDirectoryStep(genPath));
    steps.addAll(FluentIterable.from(srcs).transform(
        new Function<SourcePath, Step>() {
          @Override
          public Step apply(SourcePath input) {
            return new ThriftStep(
                input.resolve(),
                /* outputDir */ Optional.of(genPath),
                /* outputLocation */ Optional.<Path>absent(),
                /* includePaths */ ImmutableSortedSet.<Path>of(),
                langs,
                /* commandLineArgs */ ImmutableSortedSet.<String>of());
          }
        }).toList());
    buildableContext.recordArtifactsInDirectory(genPath);
    return steps.build();
  }

}
