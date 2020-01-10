/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkPaths;
import com.facebook.buck.step.fs.SymlinkTreeMergeStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multiset;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.logging.Level;
import java.util.stream.Stream;

/** Creates a tree of symlinks inside of a given directory */
public class SymlinkTree extends AbstractBuildRule
    implements HasRuntimeDeps, SupportsInputBasedRuleKey {

  protected final String category;
  protected final Path root;
  @AddToRuleKey private final Symlinks links;
  protected final String type;

  private final ImmutableSortedSet<BuildRule> buildDeps;

  /**
   * Creates an instance of {@link SymlinkTree}
   *
   * @param category A name used in the symlink steps
   * @param target The target for this rule
   * @param filesystem The filesystem that the tree lives on
   * @param root The directory to create symlinks in
   * @param links A map of path within the link tree to the target of the symlikm
   */
  private SymlinkTree(
      String category,
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      Symlinks links,
      ImmutableSortedSet<BuildRule> buildDeps) {
    super(target, filesystem);
    this.category = category;

    Preconditions.checkState(
        !root.isAbsolute(), "Expected symlink tree root to be relative: %s", root);

    this.root = root;
    this.links = links;

    this.type = category + "_symlink_tree";
    this.buildDeps = buildDeps;
  }

  private static ImmutableSortedSet<BuildRule> getLinkDeps(
      SourcePathRuleFinder finder, Symlinks links) {
    ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
    links.forEachSymlinkBuildDep(finder, deps::add);
    return deps.build();
  }

  public SymlinkTree(
      String category,
      BuildTarget target,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder finder,
      Path root,
      Symlinks links) {
    this(category, target, filesystem, root, links, getLinkDeps(finder, links));
  }

  public SymlinkTree(
      String category,
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links) {
    this(category, target, filesystem, root, new SymlinkMap(links), ImmutableSortedSet.of());
  }

  /**
   * Because of cross-cell, multiple {@link SourcePath}s can resolve to the same relative path,
   * despite having distinct absolute paths. This presents a challenge for rules that require
   * gathering all of the inputs in one directory.
   *
   * @param sourcePaths set of SourcePaths to process
   * @return a map that assigns a unique relative path to each of the SourcePaths.
   */
  public static ImmutableBiMap<SourcePath, Path> resolveDuplicateRelativePaths(
      ImmutableSortedSet<SourcePath> sourcePaths, SourcePathResolverAdapter resolver) {
    // This serves a dual purpose - it keeps track of whether a particular relative path had been
    // assigned to a SourcePath and how many times a particular relative path had been seen.
    Multiset<Path> assignedPaths = HashMultiset.create();
    ImmutableBiMap.Builder<SourcePath, Path> builder = ImmutableBiMap.builder();
    List<SourcePath> conflicts = new ArrayList<>();

    for (SourcePath sourcePath : sourcePaths) {
      Path relativePath = resolver.getRelativePath(sourcePath);
      if (!assignedPaths.contains(relativePath)) {
        builder.put(sourcePath, relativePath);
        assignedPaths.add(relativePath);
      } else {
        conflicts.add(sourcePath);
      }
    }

    for (SourcePath conflict : conflicts) {
      Path relativePath = resolver.getRelativePath(conflict);
      Path parent = MorePaths.getParentOrEmpty(relativePath);
      String extension = MorePaths.getFileExtension(relativePath);
      String name = MorePaths.getNameWithoutExtension(relativePath);

      while (true) {
        StringBuilder candidateName = new StringBuilder(name);
        candidateName.append('-');
        int suffix = assignedPaths.count(relativePath);
        candidateName.append(suffix);
        if (!extension.isEmpty()) {
          candidateName.append('.');
          candidateName.append(extension);
        }
        Path candidate = parent.resolve(candidateName.toString());

        if (!assignedPaths.contains(candidate)) {
          assignedPaths.add(candidate);
          builder.put(conflict, candidate);
          break;
        } else {
          assignedPaths.add(relativePath);
        }
      }
    }

    return builder.build();
  }

  @Override
  public String getType() {
    return type;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }

  protected SymlinkPaths getResolvedSymlinks(SourcePathResolverAdapter resolver) {
    return links.resolveSymlinkPaths(resolver);
  }

  @SuppressWarnings("unused")
  protected boolean shouldDeleteExistingSymlink(ProjectFilesystem filesystem, Path path) {
    return false;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SymlinkPaths paths = getResolvedSymlinks(context.getSourcePathResolver());
    return new ImmutableList.Builder<Step>()
        .add(getVerifyStep(paths))
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), root)))
        .add(
            new SymlinkTreeMergeStep(
                category, getProjectFilesystem(), root, paths, this::shouldDeleteExistingSymlink))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), root);
  }

  @VisibleForTesting
  protected Step getVerifyStep(SymlinkPaths links) {
    return new AbstractExecutionStep("verify_symlink_tree") {
      @Override
      public StepExecutionResult execute(ExecutionContext context) throws IOException {
        Set<Path> invalidPaths = new HashSet<>();
        links.forEachSymlink(
            (dst, src) -> {
              for (Path pathPart : dst) {
                if (pathPart.toString().equals("..")) {
                  invalidPaths.add(dst);
                }
              }
            });
        if (invalidPaths.isEmpty()) {
          return StepExecutionResults.SUCCESS;
        } else {
          context
              .getBuckEventBus()
              .post(
                  ConsoleEvent.create(
                      Level.SEVERE,
                      String.format(
                          "Paths cannot contain \"..\": %s", Joiner.on(", ").join(invalidPaths))));
          return StepExecutionResults.ERROR;
        }
      }
    };
  }

  // We don't cache symlinks because:
  // 1) We don't currently support caching symlinks.
  // 2) It's almost certainly always more expensive to cache them rather than just re-create them.
  // 3) The symlinks are absolute.
  @Override
  public boolean isCacheable() {
    return false;
  }

  public Path getRoot() {
    return getProjectFilesystem().resolve(root);
  }

  public SourcePath getRootSourcePath() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), root);
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    Stream.Builder<BuildTarget> deps = Stream.builder();
    links.forEachSymlinkInput(
        s ->
            buildRuleResolver
                .filterBuildRuleInputs(s)
                .forEach(r -> deps.accept(r.getBuildTarget())));
    return deps.build();
  }
}
