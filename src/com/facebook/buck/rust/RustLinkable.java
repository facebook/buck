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

package com.facebook.buck.rust;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.SymlinkFilesIntoDirectoryStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

abstract class RustLinkable extends AbstractBuildRule {
  @AddToRuleKey
  private final Tool compiler;
  @AddToRuleKey
  private final ImmutableSet<SourcePath> srcs;
  @AddToRuleKey
  private final ImmutableList<String> flags;
  @AddToRuleKey
  private final ImmutableSet<String> features;
  private final Path scratchDir;
  private final Path output;

  RustLinkable(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableSet<SourcePath> srcs,
      ImmutableList<String> flags,
      ImmutableSet<String> features,
      Path output,
      Tool compiler) {
    super(params, resolver);
    this.srcs = srcs;
    this.flags = ImmutableList.<String>builder()
        .add("--crate-name", getBuildTarget().getShortName())
        .addAll(flags)
        .build();
    this.features = features;
    this.output = output;
    this.compiler = compiler;
    this.scratchDir = BuildTargets.getScratchPath(getBuildTarget(), "container");

    for (String feature : features) {
      if (feature.contains("\"")) {
        throw new HumanReadableException(
            "%s contains an invalid feature name %s",
            getBuildTarget().getFullyQualifiedName(),
            feature);
      }
    }
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableMap.Builder<String, Path> externalCratesBuilder = ImmutableMap.builder();
    for (BuildRule buildRule : getDeps()) {
      if (!(buildRule instanceof RustLibrary)) {
        throw new HumanReadableException(
            "%s (dep of %s) is not an instance of rust_library!",
            buildRule.getBuildTarget().getFullyQualifiedName(),
            getBuildTarget().getFullyQualifiedName());
      }
      Path ruleOutput = buildRule.getPathToOutput();
      Preconditions.checkNotNull(ruleOutput);
      externalCratesBuilder.put(buildRule.getBuildTarget().getShortName(), ruleOutput);
    }
    return ImmutableList.of(
        new MakeCleanDirectoryStep(scratchDir),
        new SymlinkFilesIntoDirectoryStep(
            context.getProjectRoot(),
            getResolver().getAllPaths(srcs),
            scratchDir),
        new MakeCleanDirectoryStep(output.getParent()),
        new RustCompileStep(
            getProjectFilesystem().getRootPath(),
            compiler.getCommandPrefix(getResolver()),
            flags,
            features,
            output,
            externalCratesBuilder.build(),
            getCrateRoot()));
  }

  @VisibleForTesting
  Path getCrateRoot() {
    ImmutableList<Path> candidates = ImmutableList.copyOf(
        FluentIterable.from(getResolver().getAllPaths(srcs))
            .filter(
                new Predicate<Path>() {
                  @Override
                  public boolean apply(Path path) {
                    return path.endsWith("main.rs") ||
                        path.endsWith(String.format("%s.rs", getBuildTarget().getShortName()));
                  }
                }));
    if (candidates.size() != 1) {
      throw new HumanReadableException(
          "srcs of %s must contain either main.rs or %s.rs!",
          getBuildTarget().getFullyQualifiedName(),
          getBuildTarget().getShortName());
    }
    // We end up creating a symlink tree to ensure that the crate only uses the files that it
    // declares in the BUCK file.
    return scratchDir.resolve(candidates.get(0));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }
}
