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

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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

  private static final Comparator<RelPath> PATH_COMPARATOR =
      (a, b) ->
          ComparisonChain.start()
              .compare(a.getPath().getNameCount(), b.getPath().getNameCount())
              .compare(a.toString(), b.toString())
              .result();

  private final Map<RelPath, Node> basePathToNodeIndex;

  /**
   * Creates an InMemoryBuildFileTree from the base paths in the given BuildTargetPaths.
   *
   * @param targets BuildTargetPaths to get base paths from.
   */
  public InMemoryBuildFileTree(Iterable<BuildTarget> targets, ProjectFilesystem filesystem) {
    this(collectBasePaths(targets, filesystem));
  }

  /**
   * Returns the base paths for zero or more targets.
   *
   * @param targets targets to return base paths for
   * @return base paths for targets
   */
  private static Collection<RelPath> collectBasePaths(
      Iterable<? extends BuildTarget> targets, ProjectFilesystem filesystem) {
    return StreamSupport.stream(targets.spliterator(), false)
        .map(t -> t.getCellRelativeBasePath().getPath().toRelPath(filesystem.getFileSystem()))
        .collect(ImmutableSet.toImmutableSet());
  }

  public InMemoryBuildFileTree(Collection<RelPath> basePaths) {
    TreeSet<RelPath> sortedBasePaths = Sets.newTreeSet(PATH_COMPARATOR);
    sortedBasePaths.addAll(basePaths);

    // Initialize basePathToNodeIndex with a Node that corresponds to the empty string. This ensures
    // that findParent() will always return a non-null Node because the empty string is a prefix of
    // all base paths.
    basePathToNodeIndex = new HashMap<>();
    Node root = new Node(RelPath.get(""));
    basePathToNodeIndex.put(RelPath.get(""), root);

    // Build up basePathToNodeIndex in a breadth-first manner.
    for (RelPath basePath : sortedBasePaths) {
      if (basePath.getPath().equals(Paths.get(""))) {
        continue;
      }

      Node child = new Node(basePath);
      Node parent = findParent(child, basePathToNodeIndex);
      Objects.requireNonNull(parent).addChild(child);
      basePathToNodeIndex.put(basePath, child);
    }
  }

  @Override
  public Optional<RelPath> getBasePathOfAncestorTarget(RelPath filePath) {
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
  private static Node findParent(Node child, Map<RelPath, Node> basePathToNodeIndex) {
    RelPath current = child.basePath;
    while (current != null) {
      Node candidate = basePathToNodeIndex.get(current);
      if (candidate != null) {
        return candidate;
      }
      current = current.getParent();
    }
    return basePathToNodeIndex.get(RelPath.get(""));
  }

  /** Represents a build file in the project directory. */
  private static class Node {

    /** Build target base path. */
    private final RelPath basePath;

    /** List of child nodes: created lazily to save memory. */
    @Nullable private List<Node> children;

    Node(RelPath basePath) {
      this.basePath = basePath;
    }

    void addChild(Node node) {
      if (children == null) {
        children = new ArrayList<>();
      }
      children.add(node);
    }
  }
}
