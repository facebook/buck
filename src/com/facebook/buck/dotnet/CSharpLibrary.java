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

package com.facebook.buck.dotnet;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.util.Map;

public class CSharpLibrary extends AbstractBuildRule {

  @AddToRuleKey(stringify = true)
  private final Path output;
  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey
  private final ImmutableList<Either<BuildRule, String>> refs;
  @AddToRuleKey
  private final ImmutableMap<String, SourcePath> resources;
  @AddToRuleKey
  private final FrameworkVersion version;

  protected CSharpLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      String dllName,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableList<Either<BuildRule, String>> refs,
      ImmutableMap<String, SourcePath> resources,
      FrameworkVersion version) {
    super(params, resolver);

    Preconditions.checkArgument(dllName.endsWith(".dll"));

    this.srcs = srcs;
    this.refs = refs;
    this.resources = resources;
    this.version = version;

    this.output = BuildTargets.getGenPath(
        params.getBuildTarget(),
        String.format("%%s/%s", dllName));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ProjectFilesystem filesystem = getProjectFilesystem();

    ImmutableSortedSet<Path> sourceFiles = FluentIterable.from(srcs)
        .transform(getResolver().getResolvedPathFunction())
        .toSortedSet(Ordering.natural());

    ImmutableListMultimap.Builder<Path, String> resolvedResources = ImmutableListMultimap.builder();
    for (Map.Entry<String, SourcePath> resource : resources.entrySet()) {
      resolvedResources.put(getResolver().getResolvedPath(resource.getValue()), resource.getKey());
    }

    ImmutableList<Either<Path, String>> references = resolveReferences(refs);

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MakeCleanDirectoryStep(filesystem, output.getParent()));
    steps.add(
        new CSharpLibraryCompile(
            filesystem.resolve(output),
            sourceFiles,
            references,
            resolvedResources.build(),
            version));

    return steps.build();
  }

  private ImmutableList<Either<Path, String>> resolveReferences(
      ImmutableList<Either<BuildRule, String>> refs) {
    ImmutableList.Builder<Either<Path, String>> resolved = ImmutableList.builder();

    for (Either<BuildRule, String> ref : refs) {
      if (ref.isLeft()) {
        // TODO(simons): Do this in the constructor? Or the Description?
        BuildRule rule = ref.getLeft();
        Preconditions.checkArgument(
            rule instanceof CSharpLibrary ||
            rule instanceof PrebuiltDotNetLibrary);

        Path outputPath = Preconditions.checkNotNull(rule.getPathToOutput());
        resolved.add(Either.<Path, String>ofLeft(rule.getProjectFilesystem().resolve(outputPath)));
      } else {
        resolved.add(Either.<Path, String>ofRight(ref.getRight()));
      }
    }

    return resolved.build();
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }
}
