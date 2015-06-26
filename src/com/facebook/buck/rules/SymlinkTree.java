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

import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;

import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nullable;

public class SymlinkTree
    extends AbstractBuildRule
    implements RuleKeyAppendable, HasPostBuildSteps, SupportsInputBasedRuleKey {

  private final Path root;
  private final ImmutableSortedMap<Path, SourcePath> links;
  private final ImmutableMap<Path, SourcePath> fullLinks;

  public SymlinkTree(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path root,
      ImmutableMap<Path, SourcePath> links) {
    super(params, resolver);
    this.root = root;
    this.links = ImmutableSortedMap.copyOf(links);

    ImmutableMap.Builder<Path, SourcePath> fullLinks = ImmutableMap.builder();
    // Maintain ordering
    for (ImmutableMap.Entry<Path, SourcePath> entry : this.links.entrySet()) {
      fullLinks.put(root.resolve(entry.getKey()), entry.getValue());
    }
    this.fullLinks = fullLinks.build();
  }

  /**
   * Resolve {@link com.facebook.buck.rules.SourcePath} references in the link map.
   */
  private ImmutableMap<Path, Path> resolveLinks() {
    ImmutableMap.Builder<Path, Path> resolvedLinks = ImmutableMap.builder();
    for (ImmutableMap.Entry<Path, SourcePath> entry : links.entrySet()) {
      resolvedLinks.put(entry.getKey(), getResolver().getPath(entry.getValue()));
    }
    return resolvedLinks.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  // Put the link map into the rule key, as if it changes at all, we need to
  // re-run it.
  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    for (Map.Entry<Path, SourcePath> entry : links.entrySet()) {
      builder.setReflectively(
          "link(" + entry.getKey().toString() + ")",
          getResolver().getPath(entry.getValue()).toString());
    }
    return builder;
  }

  // Since we produce a directory tree of symlinks, rather than a single file, return
  // null here.
  @Override
  @Nullable
  public Path getPathToOutput() {
    return null;
  }

  // We generate the symlinks using post-build steps to avoid the cache because:
  // 1) We don't currently support caching symlinks
  // 2) It's almost certainly always more expensive to cache them rather than just re-create them.
  @Override
  public ImmutableList<Step> getPostBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of(
        new MakeCleanDirectoryStep(root),
        new SymlinkTreeStep(root, resolveLinks()));
  }

  public Path getRoot() {
    return root;
  }

  public ImmutableMap<Path, SourcePath> getLinks() {
    return links;
  }

  public ImmutableMap<Path, SourcePath> getFullLinks() {
    return fullLinks;
  }

}
