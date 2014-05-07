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

package com.facebook.buck.model;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.annotation.Nullable;

/**
 * A tree of build files that reflects their tree structure in the filesystem. This makes it
 * possible to see which build files are "under" other build files.
 */
public class InMemoryBuildFileTree extends BuildFileTree {

  private static final Comparator<Path> PATH_COMPARATOR = new Comparator<Path>() {
    @Override
    public int compare(Path a, Path b) {
      return ComparisonChain.start()
          .compare(a.getNameCount(), b.getNameCount())
          .compare(a.toString(), b.toString())
          .result();
    }
  };

  private final Map<Path, Node> basePathToNodeIndex;

  /**
   * Creates an InMemoryBuildFileTree from the base paths in the given BuildTargets.
   *
   * @param targets BuildTargets to get base paths from.
   */
  public InMemoryBuildFileTree(Iterable<BuildTarget> targets) {
    this(collectBasePaths(targets));
  }

  public InMemoryBuildFileTree(Collection<Path> basePaths) {
    TreeSet<Path> sortedBasePaths = Sets.newTreeSet(PATH_COMPARATOR);
    sortedBasePaths.addAll(basePaths);

    // Initialize basePathToNodeIndex with a Node that corresponds to the empty string. This ensures
    // that findParent() will always return a non-null Node because the empty string is a prefix of
    // all base paths.
    basePathToNodeIndex = Maps.newHashMap();
    Node root = new Node(Paths.get(""));
    basePathToNodeIndex.put(Paths.get(""), root);

    // Build up basePathToNodeIndex in a breadth-first manner.
    for (Path basePath : sortedBasePaths) {
      if (basePath.equals(Paths.get(""))) {
        continue;
      }

      Node child = new Node(basePath);
      Node parent = findParent(child, basePathToNodeIndex);
      parent.addChild(child);
      basePathToNodeIndex.put(basePath, child);
    }
  }

  @Override
  public Path getBasePathOfAncestorTarget(Path filePath) {
    Node node = new Node(filePath);
    Node parent = findParent(node, basePathToNodeIndex);
    if (parent != null) {
      return parent.basePath;
    } else {
      return Paths.get("");
    }
  }

  /**
   * @return Iterable of relative paths to the BuildTarget's directory that contain their own build
   *     files. No element in the Iterable is a prefix of any other element in the Iterable.
   */
  @Override
  public Collection<Path> getChildPaths(BuildTarget buildTarget) {
    return getChildPaths(buildTarget.getBasePath());
  }

  @VisibleForTesting
  Collection<Path> getChildPaths(String basePath) {
    final Path path = Paths.get(basePath);
    Node node = basePathToNodeIndex.get(path);
    if (node.children == null) {
      return ImmutableList.of();
    } else {
      return FluentIterable.from(node.children).transform(
          new Function<Node, Path>() {
            @Override
            public Path apply(Node child) {
              return path.relativize(child.basePath);
            }
          }).toList();
    }
  }

  /**
   * Finds the parent Node of the specified child Node.
   * @param child whose parent is sought in {@code basePathToNodeIndex}.
   * @param basePathToNodeIndex Map that must contain a Node with a basePath that is a prefix of
   *     {@code child}'s basePath.
   * @return the Node in {@code basePathToNodeIndex} with the longest basePath that is a prefix of
   *     {@code child}'s basePath.
   */
  private static Node findParent(Node child, Map<Path, Node> basePathToNodeIndex) {
    Path current = child.basePath;
    while (current != null) {
      Node candidate = basePathToNodeIndex.get(current);
      if (candidate != null) {
        return candidate;
      }
      current = current.getParent();
    }
    return basePathToNodeIndex.get(Paths.get(""));
  }

  /** Represents a build file in the project directory. */
  private static class Node {

    /** Result of {@link BuildTarget#getBasePath()}. */
    private final Path basePath;

    /** List of child nodes: created lazily to save memory. */
    @Nullable
    private List<Node> children;

    Node(Path basePath) {
      this.basePath = basePath;
    }

    void addChild(Node node) {
      if (children == null) {
        children = Lists.newArrayList();
      }
      children.add(node);
    }
  }
}
