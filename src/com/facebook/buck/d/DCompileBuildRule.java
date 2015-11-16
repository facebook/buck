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

package com.facebook.buck.d;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * A build rule for invoking the D compiler.
 */
public class DCompileBuildRule extends AbstractBuildRule {
  private Tool compiler;
  private ImmutableList<String> compilerFlags;
  private Path outputPath;
  private SourcePathResolver sourcePathResolver;
  private ImmutableSortedSet<SourcePath> sources;

  public DCompileBuildRule(
      BuildRuleParams params,
      SourcePathResolver sourcePathResolver,
      ImmutableSortedSet<SourcePath> sources,
      ImmutableList<String> compilerFlags,
      Tool compiler) {
    this(
        params,
        sourcePathResolver,
        sources,
        compilerFlags,
        compiler,
        BuildTargets.getGenPath(
            params.getBuildTarget(), "%s/" + params.getBuildTarget().getShortName()));
  }

  public DCompileBuildRule(
      BuildRuleParams params,
      SourcePathResolver sourcePathResolver,
      ImmutableSortedSet<SourcePath> sources,
      ImmutableList<String> compilerFlags,
      Tool compiler,
      Path outputPath) {
    super(params, sourcePathResolver);
    this.compiler = compiler;
    this.compilerFlags = compilerFlags;
    this.outputPath = outputPath;
    this.sourcePathResolver = sourcePathResolver;
    this.sources = sources;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), outputPath.getParent()));
    steps.add(
        new DCompileStep(
            getProjectFilesystem().getRootPath(),
            compiler.getEnvironment(getResolver()),
            compiler.getCommandPrefix(getResolver()),
            compilerFlags,
            outputPath,
            sourcePathResolver.deprecatedAllPaths(sources)));
    return steps.build();
  }

  @Override
  public Path getPathToOutput() {
    return outputPath;
  }

  @Override
  public BuildableProperties getProperties() {
    return new BuildableProperties(BuildableProperties.Kind.LIBRARY);
  }

}
