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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.util.MoreMaps;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multiset;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class SymlinkTree
    extends AbstractBuildRule
    implements HasPostBuildSteps, SupportsInputBasedRuleKey, RuleKeyAppendable {

  private final Path root;
  private final ImmutableSortedMap<Path, SourcePath> links;

  public SymlinkTree(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path root,
      final ImmutableMap<Path, SourcePath> links) {
    super(params, resolver);

    Preconditions.checkState(
        root.isAbsolute(),
        "Expected symlink tree root to be absolute: %s",
        root);

    this.root = root;
    this.links = ImmutableSortedMap.copyOf(links);
  }

  public static SymlinkTree from(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path root,
      ImmutableMap<String, SourcePath> links) {
    return new SymlinkTree(
        params,
        resolver,
        root,
        MoreMaps.transformKeys(links, MorePaths.toPathFn(root.getFileSystem())));
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("links", getLinksForRuleKey());
  }

  /**
   * Because of cross-cell, multiple {@link SourcePath}s can resolve to the same relative path,
   * despite having distinct absolute paths. This presents a challenge for rules that require
   * gathering all of the inputs in one directory.
   *
   * @param sourcePaths set of SourcePaths to process
   * @param resolver resolver
   * @return a map that assigns a unique relative path to each of the SourcePaths.
   */
  public static ImmutableBiMap<SourcePath, Path> resolveDuplicateRelativePaths(
      ImmutableSortedSet<SourcePath> sourcePaths,
      SourcePathResolver resolver) {
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
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  // Put the link map into the rule key, as if it changes at all, we need to
  // re-run it.
  private ImmutableSortedMap<String, NonHashableSourcePathContainer> getLinksForRuleKey() {
    ImmutableSortedMap.Builder<String, NonHashableSourcePathContainer> linksForRuleKeyBuilder =
        ImmutableSortedMap.naturalOrder();
    for (Map.Entry<Path, SourcePath> entry : links.entrySet()) {
      linksForRuleKeyBuilder.put(
          entry.getKey().toString(),
          new NonHashableSourcePathContainer(entry.getValue()));
    }
    return linksForRuleKeyBuilder.build();
  }

  // Since we produce a directory tree of symlinks, rather than a single file, return
  // null here.
  @Override
  public Path getPathToOutput() {
    return root;
  }

  @VisibleForTesting
  protected Step getVerifiyStep() {
    return new AbstractExecutionStep("verify_symlink_tree") {
        @Override
        public StepExecutionResult execute(ExecutionContext context) throws IOException {
          for (ImmutableMap.Entry<Path, SourcePath> entry : getLinks().entrySet()) {
            for (Path pathPart : entry.getKey()) {
              if (pathPart.toString().equals("..")) {
                context.getBuckEventBus().post(
                    ConsoleEvent.create(
                        Level.SEVERE,
                        String.format(
                            "Path '%s' should not contain '%s'.",
                            entry.getKey(),
                            pathPart)));
                return StepExecutionResult.ERROR;
              }
            }
          }
          return StepExecutionResult.SUCCESS;
        }
      };
  }

  // We generate the symlinks using post-build steps to avoid the cache because:
  // 1) We don't currently support caching symlinks.
  // 2) It's almost certainly always more expensive to cache them rather than just re-create them.
  // 3) The symlinks are absolute.
  @Override
  public ImmutableList<Step> getPostBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of(
        getVerifiyStep(),
        new MakeCleanDirectoryStep(getProjectFilesystem(), root),
        new SymlinkTreeStep(getProjectFilesystem(), root, getResolver().getMappedPaths(links)));
  }

  public Path getRoot() {
    return root;
  }

  public ImmutableMap<Path, SourcePath> getLinks() {
    return links;
  }

}
