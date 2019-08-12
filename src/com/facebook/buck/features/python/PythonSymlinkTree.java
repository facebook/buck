/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.features.python;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.SymlinkTreeMergeStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Supplier;

/** Creates a tree of symlinks inside of a given directory */
public class PythonSymlinkTree extends SymlinkTree {

  @AddToRuleKey
  private final Supplier<ImmutableSortedMap<String, ImmutableList<SourcePath>>>
      directoriesToMergeForRuleKey = this::directoriesToMergeForRuleKey;

  private final ImmutableMultimap<Path, SourcePath> directoriesToMerge;
  private final ImmutableSortedSet<BuildRule> buildDeps;

  /**
   * Creates an instance of {@link SymlinkTree}
   *
   * @param category A name used in the symlink steps
   * @param target The target for this rule
   * @param filesystem The filesystem that the tree lives on
   * @param root The directory to create symlinks in
   * @param links A map of path within the link tree to the target of the symlikm
   * @param directoriesToMerge A map of relative paths within the link tree into which files from
   *     the value will be recursively linked. e.g. if a file at /tmp/foo/bar should be linked as
   *     /tmp/symlink-root/subdir/bar, the map should contain {Paths.get("subdir"),
   *     SourcePath(Paths.get("tmp", "foo")) }
   * @param ruleFinder Used to iterate over {@code directoriesToMerge} in order get the build time
   */
  public PythonSymlinkTree(
      String category,
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      ImmutableMultimap<Path, SourcePath> directoriesToMerge,
      SourcePathRuleFinder ruleFinder) {
    super(category, target, filesystem, root, links);

    this.directoriesToMerge = directoriesToMerge;
    this.buildDeps =
        directoriesToMerge.values().stream()
            .map(ruleFinder::getRule)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));

    Preconditions.checkState(
        !root.isAbsolute(), "Expected symlink tree root to be relative: %s", root);
  }

  // Turn our multimap into something properly ordered by path with the multimap values sorted
  // TODO(cjhopman): We should just hold the sorted version of this list and then an unsorted
  // keylist to tell us what order to process them in.
  private ImmutableSortedMap<String, ImmutableList<SourcePath>> directoriesToMergeForRuleKey() {
    return directoriesToMerge.keySet().stream()
        .collect(
            ImmutableSortedMap.toImmutableSortedMap(
                String::compareTo,
                Path::toString,
                k -> ImmutableList.sortedCopyOf(directoriesToMerge.get(k))));
  }

  /**
   * SymlinkTree never has any compile-time deps, only runtime deps.
   *
   * <p>All rules which consume SymlinkTrees are themselves required to have dependencies anything
   * which may alter the SymlinkTree contents.
   *
   * <p>This is to avoid removing and re-creating the same symlinks every build.
   */
  @Override
  public ImmutableSortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return new ImmutableList.Builder<Step>()
        .addAll(super.getBuildSteps(context, buildableContext))
        .add(
            new SymlinkTreeMergeStep(
                category,
                getProjectFilesystem(),
                root,
                directoriesToMerge.entries().stream()
                    .collect(
                        ImmutableSetMultimap.toImmutableSetMultimap(
                            Entry::getKey,
                            entry ->
                                context
                                    .getSourcePathResolver()
                                    .getAbsolutePath(entry.getValue())))))
        .build();
  }
}
