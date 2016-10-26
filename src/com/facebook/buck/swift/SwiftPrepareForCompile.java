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

package com.facebook.buck.swift;

import com.facebook.buck.cxx.CxxWriteArgsToFileStep;
import com.facebook.buck.cxx.HeaderSymlinkTree;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.Optional;

import javax.annotation.Nullable;

class SwiftPrepareForCompile extends AbstractBuildRule {

  private final ImmutableSet<SourcePath> srcs;
  private final Path fileListPath;

  SwiftPrepareForCompile(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      ImmutableSet<SourcePath> srcs) {
    super(buildRuleParams, resolver);
    this.srcs = srcs;
    this.fileListPath = BuildTargets.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "%s__filelist.txt");
  }

  private Step createFileListStep() {
    ImmutableList<Arg> sourceListArgs = FileListableLinkerInputArg.from(
        SourcePathArg.from(getResolver(), srcs).stream()
            .filter(SourcePathArg.class::isInstance)
            .map(input -> (SourcePathArg) input)
            .collect(MoreCollectors.toImmutableList()));

    return new CxxWriteArgsToFileStep(
        fileListPath,
        sourceListArgs,
        Optional.empty());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(fileListPath);
    return ImmutableList.of(createFileListStep());
  }

  /**
   * @see HeaderSymlinkTree#isCacheable()
   */
  @Override
  public boolean isCacheable() {
    return false;
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return fileListPath;
  }

}
