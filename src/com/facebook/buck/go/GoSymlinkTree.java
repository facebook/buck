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

package com.facebook.buck.go;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasPostBuildSteps;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import javax.annotation.Nullable;

public class GoSymlinkTree extends AbstractBuildRule implements HasPostBuildSteps {
  private final Path root;
  private final ImmutableMap<Path, Path> symlinkMap;

  protected GoSymlinkTree(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Path root) {
    super(buildRuleParams, resolver);
    this.root = root;

    ImmutableMap.Builder<Path, Path> mapBuilder = ImmutableMap.builder();
    for (BuildRule rule : getDeclaredDeps()) {
      if (!(rule instanceof GoLinkable)) {
        throw new HumanReadableException(
            "%s (dep of %s) is not an instance of go_library!",
            rule.getBuildTarget().getFullyQualifiedName(),
            getBuildTarget().getFullyQualifiedName());
      }

      GoLinkable goRule = (GoLinkable) rule;

      mapBuilder.put(
          goRule.getPathInSymlinkTree(),
          getProjectFilesystem().resolve(goRule.getPathToOutput()));
    }
    this.symlinkMap = mapBuilder.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  // Since we produce a directory tree of symlinks, rather than a single file, return
  // null here.
  @Override
  @Nullable
  public Path getPathToOutput() {
    return null;
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of(
        new MakeCleanDirectoryStep(getProjectFilesystem(), root),
        new SymlinkTreeStep(
            getProjectFilesystem(),
            root,
            symlinkMap)
    );
  }

  public Path getRoot() {
    return root;
  }
}
