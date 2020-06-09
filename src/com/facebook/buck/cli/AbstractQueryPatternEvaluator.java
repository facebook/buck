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

package com.facebook.buck.cli;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.support.cli.config.AliasConfig;
import com.facebook.buck.util.MoreMaps;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abstract class which is capable of picking out build targets and files from lists of strings. The
 * files get passed {@code convertQueryFileTargetToNodes} to be converted into the desired type and
 * build targets get passed as strings to {@code resolveBuildTargetPatterns} to be resolved
 * concurrently into the appropriate type.
 */
public abstract class AbstractQueryPatternEvaluator<T> {

  private final Cell rootCell;
  private final AbsPath projectRoot;
  private final BuckConfig buckConfig;

  private Map<String, ImmutableSet<T>> resolvedTargets = new HashMap<>();

  public AbstractQueryPatternEvaluator(Cell rootCell, BuckConfig buckConfig) {
    this.rootCell = rootCell;
    this.projectRoot = rootCell.getFilesystem().getRootPath();
    this.buckConfig = buckConfig;
  }

  public abstract ImmutableSet<T> convertQueryFileTargetToNodes(Set<QueryFileTarget> targets);

  public abstract ImmutableMap<String, ImmutableSet<T>> resolveBuildTargetPatterns(
      List<String> patterns) throws InterruptedException, BuildFileParseException;

  /** Converts a set of strings into the output type {@code T}. */
  ImmutableMap<String, ImmutableSet<T>> resolveTargetPatterns(Iterable<String> patterns)
      throws InterruptedException, BuildFileParseException, IOException {
    ImmutableMap.Builder<String, ImmutableSet<T>> resolved = ImmutableMap.builder();

    Map<String, String> unresolved = new HashMap<>();
    for (String pattern : patterns) {

      // First check if this pattern was resolved before.
      ImmutableSet<T> targets = resolvedTargets.get(pattern);
      if (targets != null) {
        resolved.put(pattern, targets);
        continue;
      }

      // Check if this is an alias.
      ImmutableSet<UnconfiguredBuildTarget> aliasTargets =
          AliasConfig.from(buckConfig).getBuildTargetsForAlias(pattern);
      if (!aliasTargets.isEmpty()) {
        for (UnconfiguredBuildTarget alias : aliasTargets) {
          unresolved.put(alias.getFullyQualifiedName(), pattern);
        }
      } else {
        // Check if the pattern corresponds to a build target or a path.
        // Note: If trying to get a path with a single ':' in it, this /will/ choose to assume a
        // build target, not a file. In general, this is okay as:
        //  1) Most of our functions that take paths are going to be build files and the like, not
        //     something with a ':' in it
        //  2) By putting a ':' in the filename, you're already dooming yourself to never work on
        //     windows. Don't do that.
        if (pattern.contains("//")
            || pattern.contains(":")
            || pattern.endsWith("/...")
            || pattern.equals("...")) {
          unresolved.put(pattern, pattern);
        } else {
          ImmutableSet<T> fileTargets = convertQueryFileTargetToNodes(resolveFilePattern(pattern));
          resolved.put(pattern, fileTargets);
          resolvedTargets.put(pattern, fileTargets);
        }
      }
    }

    // Resolve any remaining target patterns using the parser.
    ImmutableMap<String, ImmutableSet<T>> results =
        MoreMaps.transformKeys(
            resolveBuildTargetPatterns(ImmutableList.copyOf(unresolved.keySet())),
            Functions.forMap(unresolved));
    resolved.putAll(results);
    resolvedTargets.putAll(results);

    return resolved.build();
  }

  private ImmutableSet<QueryFileTarget> resolveFilePattern(String pattern) throws IOException {
    PathArguments.ReferencedFiles referencedFiles =
        PathArguments.getCanonicalFilesUnderProjectRoot(projectRoot, ImmutableList.of(pattern));

    if (!referencedFiles.absoluteNonExistingPaths.isEmpty()) {
      throw new HumanReadableException("%s references non-existing file", pattern);
    }

    return referencedFiles.relativePathsUnderProjectRoot.stream()
        .map(path -> PathSourcePath.of(rootCell.getFilesystem(), path))
        .map(QueryFileTarget::of)
        .collect(ImmutableSet.toImmutableSet());
  }
}
