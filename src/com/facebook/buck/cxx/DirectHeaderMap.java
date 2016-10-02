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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.util.Optional;

public class DirectHeaderMap extends HeaderSymlinkTree {

  private static final Logger LOG = Logger.get(DirectHeaderMap.class);

  @AddToRuleKey(stringify = true)
  private final Path headerMapPath;

  public DirectHeaderMap(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path root,
      ImmutableMap<Path, SourcePath> links) {
    super(params, resolver, root, links);
    this.headerMapPath = getPath(params.getProjectFilesystem(), params.getBuildTarget());
  }

  @Override
  public Path getPathToOutput() {
    return headerMapPath;
  }

  // We generate the symlink tree and the header map using post-build steps for reasons explained in
  // the superclass.
  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    LOG.debug("Generating post-build steps to write header map to %s", headerMapPath);
    ImmutableMap.Builder<Path, Path> headerMapEntries = ImmutableMap.builder();
    for (Path key : getLinks().keySet()) {
      LOG.debug("header map %s -> %s", key, getResolver().getAbsolutePath(getLinks().get(key)));
      headerMapEntries.put(key, getResolver().getAbsolutePath(getLinks().get(key)));
    }
    return ImmutableList.<Step>builder()
        .add(getVerifiyStep())
        .add(new MakeCleanDirectoryStep(getProjectFilesystem(), getRoot()))
        .add(new HeaderMapStep(getProjectFilesystem(), headerMapPath, headerMapEntries.build()))
        .build();
  }

  @Override
  public Path getIncludePath() {
    return getProjectFilesystem().resolve(getProjectFilesystem().getBuckPaths().getBuckOut());
  }

  @Override
  public Optional<Path> getHeaderMap() {
    return Optional.of(getProjectFilesystem().resolve(headerMapPath));
  }

  @VisibleForTesting
  static Path getPath(ProjectFilesystem filesystem, BuildTarget target) {
    return BuildTargets.getGenPath(filesystem, target, "%s.hmap");
  }
}
