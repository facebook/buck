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

package com.facebook.buck.features.d;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

/** A build rule for invoking the D compiler. */
public class DCompileBuildRule extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey private final Tool compiler;

  @AddToRuleKey private final ImmutableList<String> compilerFlags;

  @AddToRuleKey private final String name;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> sources;

  @AddToRuleKey private final ImmutableList<DIncludes> includes;

  public DCompileBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      Tool compiler,
      ImmutableList<String> compilerFlags,
      String name,
      ImmutableSortedSet<SourcePath> sources,
      ImmutableList<DIncludes> includes) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.compiler = compiler;
    this.compilerFlags = compilerFlags;
    this.name = name;
    this.sources = sources;
    this.includes = includes;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Path output =
        context
            .getSourcePathResolver()
            .getRelativePath(Preconditions.checkNotNull(getSourcePathToOutput()));
    buildableContext.recordArtifact(output);

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));

    ImmutableList.Builder<String> flagsBuilder = ImmutableList.builder();
    flagsBuilder.addAll(compilerFlags);
    for (DIncludes include : includes) {
      flagsBuilder.add(
          "-I" + context.getSourcePathResolver().getAbsolutePath(include.getLinkTree()));
    }
    ImmutableList<String> flags = flagsBuilder.build();

    steps.add(
        new DCompileStep(
            getBuildTarget(),
            getProjectFilesystem().getRootPath(),
            compiler.getEnvironment(context.getSourcePathResolver()),
            compiler.getCommandPrefix(context.getSourcePathResolver()),
            flags,
            context.getSourcePathResolver().getRelativePath(getSourcePathToOutput()),
            context.getSourcePathResolver().getAllAbsolutePaths(sources)));
    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/" + name + ".o"));
  }
}
