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

package com.facebook.buck.features.dotnet;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Map;

public class CsharpLibrary extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey private final ImmutableList<Either<BuildRule, String>> refs;
  @AddToRuleKey private final ImmutableMap<String, SourcePath> resources;
  @AddToRuleKey private final FrameworkVersion version;

  protected CsharpLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      String dllName,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableList<Either<BuildRule, String>> refs,
      ImmutableMap<String, SourcePath> resources,
      FrameworkVersion version) {
    super(buildTarget, projectFilesystem, params);

    Preconditions.checkArgument(dllName.endsWith(".dll"));

    this.srcs = srcs;
    this.refs = refs;
    this.resources = resources;
    this.version = version;

    this.output = BuildTargetPaths.getGenPath(getProjectFilesystem(), buildTarget, "%s/" + dllName);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ProjectFilesystem filesystem = getProjectFilesystem();

    ImmutableSortedSet<Path> sourceFiles =
        context.getSourcePathResolver().getAllAbsolutePaths(srcs);

    ImmutableListMultimap.Builder<Path, String> resolvedResources = ImmutableListMultimap.builder();
    for (Map.Entry<String, SourcePath> resource : resources.entrySet()) {
      resolvedResources.put(
          context.getSourcePathResolver().getAbsolutePath(resource.getValue()), resource.getKey());
    }

    ImmutableList<Either<Path, String>> references =
        resolveReferences(context.getSourcePathResolver(), refs);

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));
    steps.add(
        new CsharpLibraryCompile(
            filesystem.resolve(output),
            sourceFiles,
            references,
            resolvedResources.build(),
            version));

    return steps.build();
  }

  private ImmutableList<Either<Path, String>> resolveReferences(
      SourcePathResolver pathResolver, ImmutableList<Either<BuildRule, String>> refs) {
    ImmutableList.Builder<Either<Path, String>> resolved = ImmutableList.builder();

    for (Either<BuildRule, String> ref : refs) {
      if (ref.isLeft()) {
        // TODO(simons): Do this in the constructor? Or the Description?
        BuildRule rule = ref.getLeft();
        Preconditions.checkArgument(
            rule instanceof CsharpLibrary || rule instanceof PrebuiltDotnetLibrary);

        SourcePath outputPath = Preconditions.checkNotNull(rule.getSourcePathToOutput());
        resolved.add(Either.ofLeft(pathResolver.getAbsolutePath(outputPath)));
      } else {
        resolved.add(Either.ofRight(ref.getRight()));
      }
    }

    return resolved.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }
}
