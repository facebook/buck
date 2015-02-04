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

import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.google.common.base.Optional;
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

  private final Path root;
  private final ImmutableMap<Path, SourcePath> links;
  private final ImmutableMap<Path, SourcePath> fullLinks;

  public SymlinkTree(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path root,
      ImmutableMap<Path, SourcePath> links) {
    super(params, resolver);
    this.root = root;
    this.links = links;

    ImmutableMap.Builder<Path, SourcePath> fullLinks = ImmutableMap.builder();
    for (ImmutableMap.Entry<Path, SourcePath> entry : links.entrySet()) {
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
    return ImmutableList.of(
        new MakeCleanDirectoryStep(root),
        new SymlinkTreeStep(root, resolveLinks()));
  }

  /**
   * @return The root of the symlinks directory or {@link Optional#absent()} if there were no
   *     files to symlink.
   */
  public Optional<Path> getRootOfSymlinksDirectory() {
    return links.isEmpty() ? Optional.<Path>absent() : Optional.of(root);
  }

  // Put the link map into the rule key, as if it changes at all, we need to
  // re-run it.
  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    List<Path> keyList = Lists.newArrayList(links.keySet());
    Collections.sort(keyList);
    for (Path key : keyList) {
      builder.setReflectively(
          "link(" + key.toString() + ")",
          getResolver().getPath(links.get(key)).toString());
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

  // We never want to cache this step, as we're only writing symlinks, and caching can
  // *only* ever make this slower.
  @Override
  public CacheMode getCacheMode() {
    return CacheMode.DISABLED;
  }

  // Since we're just setting up symlinks to existing files, we don't actually need to
  // re-run this rule if our deps change in any way.  We only need to re-run if our symlinks
  // or symlink targets change, which is modeled above in the rule key.
  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    return Sha1HashCode.fromHashCode(HashCode.fromInt(0));
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
