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

package com.facebook.buck.core.model.impl;

import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;

/**
 * A tree of build files that reflects their tree structure in the filesystem. This makes it
 * possible to see which build files are "under" other build files.
 */
public class InMemoryBuildFileTree implements BuildFileTree {

  private static final Comparator<ForwardRelPath> PATH_COMPARATOR =
      (a, b) ->
          ComparisonChain.start()
              .compare(a.getNameCount(), b.getNameCount())
              .compare(a.toString(), b.toString())
              .result();

  private final Map<ForwardRelPath, Node> basePathToNodeIndex;

  /**
   * Creates an InMemoryBuildFileTree from the base paths in the given BuildTargetPaths.
   *
   * @param targets BuildTargetPaths to get base paths from.
   */
  public static InMemoryBuildFileTree fromTargets(Iterable<BuildTarget> targets) {
    return new InMemoryBuildFileTree(collectBasePaths(targets));
  }

  /**
   * Returns the base paths for zero or more targets.
   *
   * @param targets targets to return base paths for
   * @return base paths for targets
   */
  private static Collection<ForwardRelPath> collectBasePaths(
      Iterable<? extends BuildTarget> targets) {
    return StreamSupport.stream(targets.spliterator(), false)
        .map(t -> t.getCellRelativeBasePath().getPath())
        .collect(ImmutableSet.toImmutableSet());
  }

  public InMemoryBuildFileTree(Collection<ForwardRelPath> basePaths) {
    TreeSet<ForwardRelPath> sortedBasePaths = Sets.newTreeSet(PATH_COMPARATOR);
    sortedBasePaths.addAll(basePaths);

    // Initialize basePathToNodeIndex with a Node that corresponds to the empty string. This ensures
    // that findParent() will always return a non-null Node because the empty string is a prefix of
    // all base paths.
    basePathToNodeIndex = new HashMap<>();
    Node root = new Node(ForwardRelPath.of(""));
    basePathToNodeIndex.put(ForwardRelPath.of(""), root);

    // Build up basePathToNodeIndex in a breadth-first manner.
    for (ForwardRelPath basePath : sortedBasePaths) {
      if (basePath.equals(ForwardRelPath.of(""))) {
        continue;
      }

      Node child = new Node(basePath);
      Node parent = findParent(child, basePathToNodeIndex);
      Objects.requireNonNull(parent);
      basePathToNodeIndex.put(basePath, child);
    }
  }

  @Override
  public Optional<ForwardRelPath> getBasePathOfAncestorTarget(ForwardRelPath filePath) {
    Node node = new Node(filePath);
    Node parent = findParent(node, basePathToNodeIndex);
    if (parent != null) {
      return Optional.of(parent.basePath);
    } else {
      return Optional.empty();
    }
  }

  /**
   * Finds the parent Node of the specified child Node.
   *
   * @param child whose parent is sought in {@code basePathToNodeIndex}.
   * @param basePathToNodeIndex Map that must contain a Node with a basePath that is a prefix of
   *     {@code child}'s basePath.
   * @return the Node in {@code basePathToNodeIndex} with the longest basePath that is a prefix of
   *     {@code child}'s basePath.
   */
  @Nullable
  private static Node findParent(Node child, Map<ForwardRelPath, Node> basePathToNodeIndex) {
    ForwardRelPath current = child.basePath;
    while (current != null) {
      Node candidate = basePathToNodeIndex.get(current);
      if (candidate != null) {
        return candidate;
      }
      current = current.getParentButEmptyForSingleSegment();
    }
    return null;
  }

  /** Represents a build file in the project directory. */
  private static class Node {

    /** Build target base path. */
    private final ForwardRelPath basePath;

    Node(ForwardRelPath basePath) {
      this.basePath = basePath;
    }
  }
}
