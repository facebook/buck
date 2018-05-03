/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.features.filegroup;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.HasOutputName;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.features.filebundler.CopyingFileBundler;
import com.facebook.buck.features.filebundler.FileBundler;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

/** A build rule that copies inputs provided in {@code srcs} to an output directory. */
public class Filegroup extends ModernBuildRule<Filegroup> implements HasOutputName, Buildable {

  @AddToRuleKey private final String name;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey private final OutputPath outputPath;

  public Filegroup(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      String name,
      ImmutableSortedSet<SourcePath> srcs) {
    super(buildTarget, projectFilesystem, ruleFinder, Filegroup.class);
    this.name = name;
    this.srcs = srcs;

    outputPath = new OutputPath(name);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    Path outputPath = outputPathResolver.resolvePath(this.outputPath);

    FileBundler bundler = new CopyingFileBundler(getBuildTarget());

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    bundler.copy(
        filesystem,
        buildCellPathFactory,
        steps,
        outputPath,
        srcs,
        buildContext.getSourcePathResolver());

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(outputPath);
  }

  @Override
  public String getOutputName() {
    return name;
  }
}
