/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.autosparse;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * A trie mapping paths to ManifestInfo objects; paths are split into string path segments for
 * storage efficiency. This Trie also tracks 'direct' parents; nodes that directly contain leaf are
 * direct parents.
 */
public class ManifestTrie {
  private int size = 0;
  private TrieNode root;

  public ManifestTrie() {
    this.root = new TrieNode();
  }

  /**
   * Add or replace a manifest entry
   *
   * @param path filesystem path
   * @param info ManifestInfo object to insert
   */
  public void add(Path path, ManifestInfo info) {
    if (root.add(path.iterator(), info)) {
      size += 1;
    }
  }

  public void remove(Path path) {
    if (root.remove(path.iterator())) {
      size -= 1;
    }
  }

  public int size() {
    return size;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  public boolean containsManifest(Path path) {
    return get(path) != null;
  }

  /**
   * @param path directory to test for
   * @return true if the path exists as a directory
   */
  public boolean containsDirectory(Path path) {
    TrieNode node = root.get(path.iterator());
    return node != null && !node.isLeaf();
  }

  /**
   * Check if the path is a directory (not a leaf) and contains at least one leaf node
   *
   * @param path directory to test for
   * @return true if the path exists as a directory and contains at least one leaf node
   */
  public boolean containsLeafNodes(Path path) {
    TrieNode node = root.get(path.iterator());
    return node != null && !node.isLeaf() && node.containsLeaves;
  }

  /**
   * Check if the trie contains the path (either as file or directory)
   *
   * @param path to test for
   * @return true if the path exists in the trie
   */
  public boolean contains(Path path) {
    TrieNode node = root.get(path.iterator());
    return node != null;
  }

  @Nullable
  public ManifestInfo get(Path path) {
    TrieNode node = root.get(path.iterator());
    if (node == null || !node.isLeaf()) {
      return null;
    }
    return node.manifest;
  }

  /**
   * Trie node, either mapping segments to further children, **or** pointing to a manifest, at which
   * point this is a leaf node representing a file.
   */
  private class TrieNode {
    private final Map<String, TrieNode> children;
    @Nullable private ManifestInfo manifest = null;
    private boolean containsLeaves = false;

    private TrieNode() {
      children = new TreeMap<String, TrieNode>();
    }

    private boolean isLeaf() {
      return manifest != null;
    }

    private boolean add(Iterator<Path> segments, ManifestInfo newManifest) {
      boolean added;
      if (!segments.hasNext()) {
        // Leaf node
        added = manifest == null && children.isEmpty();
        manifest = newManifest;
        children.clear();
      } else {
        String segment = segments.next().toString().intern();
        boolean lastSegment = !segments.hasNext();
        TrieNode child = children.get(segment);
        if (child != null) {
          added = child.add(segments, newManifest);
        } else {
          TrieNode newNode = new TrieNode();
          newNode.add(segments, newManifest);
          children.put(segment, newNode);
          added = true;
        }
        if (lastSegment) {
          containsLeaves = true;
        }
      }
      return added;
    }

    private boolean remove(Iterator<Path> segments) {
      String segment = segments.next().toString().intern();
      TrieNode child = children.get(segment);
      if (child == null) {
        // No next node in the trie, path doesn't exist
        return false;
      }
      if (!segments.hasNext()) {
        // last entry in the path, remove if a leaf node
        if (child.isLeaf()) {
          children.remove(segment);
          // maintain the containsLeaves property
          containsLeaves = children.values().stream().anyMatch(n -> n.isLeaf());
          return true;
        } else {
          return false;
        }
      } else {
        boolean removed = child.remove(segments);
        // clean up empty child nodes
        if (!child.isLeaf() && child.children.isEmpty()) {
          children.remove(segment);
        }
        return removed;
      }
    }

    @Nullable
    private TrieNode get(Iterator<Path> segments) {
      String segment = segments.next().toString().intern();
      TrieNode child = children.get(segment);
      if (!segments.hasNext()) {
        return child;
      }
      return child == null ? null : child.get(segments);
    }
  }
}
