/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

public class SymlinkTree extends AbstractBuildRule implements AbiRule {

  private final ImmutableMap<Path, Path> links;

  public SymlinkTree(BuildRuleParams params, ImmutableMap<Path, Path> links) {
    super(params);
    this.links = Preconditions.checkNotNull(links);
  }

  private Path getSymlinkTreeRoot() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s/symlink-tree-root");
  }

  /**
   * @return a map of the full relative path of the links to their targets.
   */
  public ImmutableMap<Path, Path> getLinks() {
    Path root = getSymlinkTreeRoot();
    ImmutableMap.Builder<Path, Path> resolvedLinks = ImmutableMap.builder();
    for (ImmutableMap.Entry<Path, Path> entry : links.entrySet()) {
      resolvedLinks.put(root.resolve(entry.getKey()), entry.getValue());
    }
    return resolvedLinks.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    Path root = getSymlinkTreeRoot();
    return ImmutableList.<Step>of(
        new MakeCleanDirectoryStep(root),
        new SymlinkTreeStep(root, links));
  }

  // Put the link map into the rule key, as if it changes at all, we need to
  // re-run it.
  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    List<Path> keyList = Lists.newArrayList(links.keySet());
    Collections.sort(keyList);
    for (Path key : keyList) {
      builder.set("link(" + key.toString() + ")", links.get(key).toString());
    }
    return builder;
  }

  // Since we produce a directory tree of symlinks, rather than a single file, return
  // null here.
  @Override
  @Nullable
  public Path getPathToOutputFile() {
    return null;
  }

  // There are no files we care about which would cause us to need to re-create the
  // symlink tree.
  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  // Since we're just setting up symlinks to existing files, we don't actually need to
  // re-run this rule if our deps change in any way.  We only need to re-run if our symlinks
  // or symlink targets change, which is modeled above in the rule key.
  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    return Sha1HashCode.fromHashCode(HashCode.fromInt(0));
  }

}
