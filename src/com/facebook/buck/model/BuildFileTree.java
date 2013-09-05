/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.util.BuckConstant.BUILD_RULES_FILE_NAME;

import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;

/**
 * A tree of build files that reflects their tree structure in the filesystem. This makes it
 * possible to see which build files are "under" other build files.
 */
public class BuildFileTree {

  private static final Splitter PATH_SPLITTER = Splitter.on('/');
  private static final Joiner PATH_JOINER = Joiner.on('/');

  /**
   * Comparator that compares paths by number of path components and then length.
   */
  private static final Comparator<String> PATH_COMPARATOR = new Comparator<String>() {
    @Override
    public int compare(String a, String b) {
      return ComparisonChain.start()
          .compare(Iterables.size(PATH_SPLITTER.split(a)), Iterables.size(PATH_SPLITTER.split(b)))
          .compare(a.length(), b.length())
          .compare(a, b)
          .result();
    }
  };

  private final Map<String, Node> basePathToNodeIndex;

  public BuildFileTree(Collection<BuildTarget> targets) {
    this(collectBasePaths(targets));
  }

  private static Iterable<String> collectBasePaths(Collection<BuildTarget> targets) {
    Preconditions.checkNotNull(targets);

    return Iterables.transform(targets, new Function<BuildTarget, String>() {
      @Override public String apply(BuildTarget buildTarget) {
        return buildTarget.getBasePath();
      }
    });
  }

  public BuildFileTree(Iterable<String> basePaths) {
    TreeSet<String> allBasePaths = Sets.newTreeSet(PATH_COMPARATOR);
    for (String basePath : basePaths) {
      allBasePaths.add(basePath);
    }

    // Initialize basePathToNodeIndex with a Node that corresponds to the empty string. This ensures
    // that findParent() will always return a non-null Node because the empty string is a prefix of
    // all base paths.
    basePathToNodeIndex = Maps.newHashMap();
    Node root = new Node("");
    basePathToNodeIndex.put("", root);

    // Build up basePathToNodeIndex in a breadth-first manner.
    for (String basePath : allBasePaths) {
      if ("".equals(basePath)) {
        continue;
      }

      Node child = new Node(basePath);
      Node parent = findParent(child, basePathToNodeIndex);
      parent.addChild(child);
      basePathToNodeIndex.put(basePath, child);
    }
  }

  public static BuildFileTree constructBuildFileTree(ProjectFilesystem filesystem)
      throws IOException {
    File root = filesystem.getProjectRoot();

    final Set<String> targets = Sets.newHashSet();

    DirectoryTraversal traversal = new DirectoryTraversal(root, filesystem.getIgnorePaths()) {
      @Override public void visit(File file, String relativePath) {
        if (!BUILD_RULES_FILE_NAME.equals(file.getName())) {
          return;
        }

        int index = Math.max(0, relativePath.lastIndexOf("/"));
        String baseName = relativePath.substring(0, index);
        targets.add(baseName);
      }
    };
    traversal.traverse();

    return new BuildFileTree(targets);
  }

  public String getBasePathOfAncestorTarget(String filePath) {
    Node node = new Node(filePath);
    Node parent = findParent(node, basePathToNodeIndex);
    if (parent != null) {
      return parent.basePath;
    } else {
      return "";
    }
  }

  /**
   * @return Iterable of relative paths to the BuildTarget's directory that contain their own build
   *     files. No element in the Iterable is a prefix of any other element in the Iterable.
   */
  public Iterable<String> getChildPaths(BuildTarget buildTarget) {
    String basePath = buildTarget.getBasePath();
    return getChildPaths(basePath);
  }

  @VisibleForTesting
  Iterable<String> getChildPaths(String basePath) {
    Node node = basePathToNodeIndex.get(basePath);
    if (node.children == null) {
      return ImmutableList.of();
    } else {
      int basePathLength = basePath.length();
      final int lengthOfPrefixToStrip = basePathLength == 0 ? basePathLength : basePathLength + 1;
      return Iterables.transform(node.children, new Function<Node, String>() {
        @Override
        public String apply(Node child) {
          return child.basePath.substring(lengthOfPrefixToStrip);
        }
      });
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
  private static Node findParent(Node child, Map<String, Node> basePathToNodeIndex) {
    ImmutableList<String> parts = ImmutableList.copyOf(child.basePath.split("/"));
    for (int numParts = parts.size() - 1; numParts > 0; numParts--) {
      List<String> partsCandidate = parts.subList(0, numParts);
      String candidateBasePath = PATH_JOINER.join(partsCandidate);
      Node candidate = basePathToNodeIndex.get(candidateBasePath);
      if (candidate != null) {
        return candidate;
      }
    }

    return basePathToNodeIndex.get("");
  }

  /** Represents a build file in the project directory. */
  private static class Node {

    /** Result of {@link BuildTarget#getBasePath()}. */
    private final String basePath;

    /** List of child nodes: created lazily to save memory. */
    @Nullable
    private List<Node> children;

    Node(String basePath) {
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
