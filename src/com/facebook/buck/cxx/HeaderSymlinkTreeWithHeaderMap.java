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

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

public class HeaderSymlinkTreeWithHeaderMap extends HeaderSymlinkTree {

  @AddToRuleKey(stringify = true)
  private final Path headerMapPath;

  public HeaderSymlinkTreeWithHeaderMap(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path root,
      Path headerMapPath,
      ImmutableMap<Path, SourcePath> links) throws SymlinkTree.InvalidSymlinkTreeException {
    super(params, resolver, root, links);
    this.headerMapPath = headerMapPath;
  }

  @Override
  public Path getPathToOutput() {
    return headerMapPath;
  }

  // We generate the symlink tree and the header map using post-build steps for reasons explained in
  // the superclass.
  @Override
  public ImmutableList<Step> getPostBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableMap.Builder<Path, Path> headerMapEntries = ImmutableMap.builder();
    for (Path key : getLinks().keySet()) {
      headerMapEntries.put(key, BuckConstant.BUCK_OUTPUT_PATH.relativize(getRoot().resolve(key)));
    }
    return ImmutableList.<Step>builder()
        .addAll(super.getPostBuildSteps(context, buildableContext))
        .add(new HeaderMapStep(headerMapPath, headerMapEntries.build()))
        .build();
  }

  @Override
  public Path getIncludePath() {
    return BuckConstant.BUCK_OUTPUT_PATH;
  }

  @Override
  public Optional<Path> getHeaderMap() {
    return Optional.of(headerMapPath);
  }

}
