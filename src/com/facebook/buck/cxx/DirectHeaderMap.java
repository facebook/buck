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

package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

class DirectHeaderMap extends HeaderSymlinkTree {

  private static final Logger LOG = Logger.get(DirectHeaderMap.class);

  @AddToRuleKey(stringify = true)
  private final Path headerMapPath;

  public DirectHeaderMap(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      SourcePathRuleFinder ruleFinder) {
    super(target, filesystem, root, links, ruleFinder);
    this.headerMapPath = BuildTargets.getGenPath(filesystem, target, "%s.hmap");
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), headerMapPath);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    LOG.debug("Generating build steps to write header map to %s", headerMapPath);
    ImmutableMap.Builder<Path, Path> entriesBuilder = ImmutableMap.builder();
    for (Map.Entry<Path, SourcePath> entry : getLinks().entrySet()) {
      entriesBuilder.put(
          entry.getKey(), context.getSourcePathResolver().getAbsolutePath(entry.getValue()));
    }
    return ImmutableList.<Step>builder()
        .add(getVerifyStep())
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    headerMapPath.getParent())))
        .add(
            RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), headerMapPath)))
        .add(new HeaderMapStep(getProjectFilesystem(), headerMapPath, entriesBuilder.build()))
        .build();
  }

  @Override
  public Path getIncludePath() {
    return getProjectFilesystem().resolve(getProjectFilesystem().getBuckPaths().getBuckOut());
  }

  @Override
  public Optional<SourcePath> getHeaderMapSourcePath() {
    return Optional.of(ExplicitBuildTargetSourcePath.of(getBuildTarget(), headerMapPath));
  }
}
