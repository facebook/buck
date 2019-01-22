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
package com.facebook.buck.remoteexecution.util;

import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Directory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.DirectoryNode;
import com.facebook.buck.remoteexecution.interfaces.Protocol.FileNode;
import com.facebook.buck.remoteexecution.interfaces.Protocol.SymlinkNode;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * MerkleTreeNodeCache is used to create and merge merkle trees for action inputs. The nodes are
 * interned.
 *
 * <p>It also provides methods to get the {@link Protocol} encoded merkle tree data structures
 * (these values are cached once computed for a node).
 */
public class MerkleTreeNodeCache {
  private final Interner<MerkleTreeNode> nodeInterner = Interners.newStrongInterner();

  private final ConcurrentHashMap<Reference<MerkleTreeNode>, NodeData> directoryCache =
      new ConcurrentHashMap<>();

  private final Protocol protocol;

  public MerkleTreeNodeCache(Protocol protocol) {
    this.protocol = protocol;
  }

  /** Creates the full tree of nodes for the provided files/symlinks and returns the root node. */
  public MerkleTreeNode createNode(Map<Path, FileNode> files, Map<Path, SymlinkNode> symlinks) {
    TreeNodeBuilder rootBuilder = new TreeNodeBuilder();
    files.forEach(
        (pathFragment, fileNode) -> {
          Preconditions.checkState(
              pathFragment.getFileName().toString().equals(fileNode.getName()),
              "FileNode has unexpected name %s for path %s.",
              fileNode.getName(),
              pathFragment);
          Preconditions.checkState(
              !pathFragment.isAbsolute(), "Expected relative path. Got %s.", pathFragment);
          rootBuilder.addFile(pathFragment, fileNode);
        });
    symlinks.forEach(
        (pathFragment, target) -> {
          Preconditions.checkState(
              pathFragment.getFileName().toString().equals(target.getName()),
              "SymlinkNode has unexpected name %s for path %s.",
              target.getName(),
              pathFragment);
          Preconditions.checkState(
              !pathFragment.isAbsolute(), "Expected relative path. Got %s.", pathFragment);
          rootBuilder.addSymlink(pathFragment, target);
        });
    return rootBuilder.build(nodeInterner);
  }

  /**
   * This will merge multiple merkle trees into one. It's quite efficient for
   * non/slightly-overlapping trees.
   */
  public MerkleTreeNode mergeNodes(Collection<MerkleTreeNode> nodes) {
    if (nodes.size() == 1) {
      return nodes.iterator().next();
    }

    Iterator<MerkleTreeNode> iterator = nodes.iterator();
    TreeNodeBuilder root = new TreeNodeBuilder(iterator.next());
    while (iterator.hasNext()) {
      root.merge(iterator.next());
    }
    return root.build(nodeInterner);
  }

  /**
   * Iterate over all the encoded data for the tree rooted at the provided node. This is useful for
   * collecting all the data that will be needed to reconstruct the merkle tree.
   */
  public void forAllData(MerkleTreeNode rootNode, Consumer<NodeData> dataConsumer) {
    rootNode.forAllNodes(n -> dataConsumer.accept(getData(n)));
  }

  /** Gets the {@link Protocol} encoded data for the provided tree. */
  public NodeData getData(MerkleTreeNode node) {
    Reference<MerkleTreeNode> nodeRef = new Reference<>(node);
    NodeData nodeData = directoryCache.get(nodeRef);
    if (nodeData != null) {
      return nodeData;
    }

    Map<String, NodeData> childrenData = new TreeMap<>();
    node.children.forEach((k, v) -> childrenData.put(k, getData(v)));
    return directoryCache.computeIfAbsent(
        nodeRef,
        ignored -> {
          List<DirectoryNode> childNodes = new ArrayList<>();
          childrenData.forEach((k, v) -> childNodes.add(protocol.newDirectoryNode(k, v.digest)));
          Directory directory =
              protocol.newDirectory(childNodes, node.files.values(), node.symlinks.values());
          return new NodeData(directory, protocol.computeDigest(directory));
        });
  }

  /** Represents a node in the merkle tree of files and symlinks. */
  public static class MerkleTreeNode {
    private final int hashCode;

    private final ImmutableSortedMap<String, MerkleTreeNode> children;
    private final ImmutableSortedMap<String, FileNode> files;
    private final ImmutableSortedMap<String, SymlinkNode> symlinks;

    MerkleTreeNode(
        ImmutableSortedMap<String, MerkleTreeNode> children,
        ImmutableSortedMap<String, FileNode> files,
        ImmutableSortedMap<String, SymlinkNode> symlinks) {
      this.children = children;
      this.files = files;
      this.symlinks = symlinks;

      this.hashCode = Objects.hash(children, files, symlinks);
    }

    /**
     * Iterate over the files in the tree rooted at this node. The consumer will be called with the
     * fall path resolved against root.
     */
    public void forAllFiles(Path root, BiConsumer<Path, FileNode> nodeConsumer) {
      for (FileNode value : files.values()) {
        nodeConsumer.accept(root.resolve(value.getName()), value);
      }
      for (Entry<String, MerkleTreeNode> child : children.entrySet()) {
        child.getValue().forAllFiles(root.resolve(child.getKey()), nodeConsumer);
      }
    }

    /** Iterate over the nodes in the tree rooted at this node. */
    public void forAllNodes(Consumer<MerkleTreeNode> nodeConsumer) {
      nodeConsumer.accept(this);
      for (Entry<String, MerkleTreeNode> child : children.entrySet()) {
        child.getValue().forAllNodes(nodeConsumer);
      }
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }

      if (!(obj instanceof MerkleTreeNode)) {
        return false;
      }

      MerkleTreeNode other = (MerkleTreeNode) obj;

      return Objects.equals(children, other.children)
          && Objects.equals(files, other.files)
          && Objects.equals(symlinks, other.symlinks);
    }
  }

  private static class TreeNodeBuilder {
    private final Map<String, Either<MerkleTreeNode, TreeNodeBuilder>> childrenBuilder =
        new HashMap<>();
    private final Map<String, FileNode> filesBuilder = new HashMap<>();
    private final Map<String, SymlinkNode> symlinksBuilder = new HashMap<>();

    public TreeNodeBuilder() {}

    public TreeNodeBuilder(MerkleTreeNode from) {
      merge(from);
    }

    private void addFile(Path pathFragment, FileNode fileNode) {
      Verify.verify(pathFragment.getNameCount() > 0);
      getMutableParentDirectory(pathFragment)
          .addFileImpl(pathFragment.getFileName().toString(), fileNode);
    }

    private void addFileImpl(String name, FileNode fileNode) {
      checkName(name);
      Verify.verify(!childrenBuilder.containsKey(name));
      Verify.verify(!symlinksBuilder.containsKey(name));
      FileNode previous = filesBuilder.putIfAbsent(name, fileNode);
      Verify.verify(previous == null || previous.equals(fileNode));
    }

    private void addSymlink(Path pathFragment, SymlinkNode target) {
      Verify.verify(pathFragment.getNameCount() > 0);
      getMutableParentDirectory(pathFragment)
          .addSymlinkImpl(pathFragment.getFileName().toString(), target);
    }

    private void addSymlinkImpl(String name, SymlinkNode target) {
      checkName(name);
      Verify.verify(!childrenBuilder.containsKey(name));
      Verify.verify(!filesBuilder.containsKey(name));
      SymlinkNode previous = symlinksBuilder.putIfAbsent(name, target);
      Verify.verify(previous == null || previous.equals(target));
    }

    private TreeNodeBuilder getMutableParentDirectory(Path pathFragment) {
      int segments = pathFragment.getNameCount();
      if (segments == 1) {
        return this;
      }
      return getMutableDirectory(pathFragment.getName(0).toString())
          .getMutableParentDirectory(pathFragment.subpath(1, segments));
    }

    private void merge(MerkleTreeNode node) {
      node.files.forEach(this::addFileImpl);
      node.symlinks.forEach(this::addSymlinkImpl);

      node.children.forEach(
          (k, v) -> {
            Either<MerkleTreeNode, TreeNodeBuilder> existingChild = childrenBuilder.get(k);
            if (existingChild == null) {
              Verify.verify(!symlinksBuilder.containsKey(k));
              Verify.verify(!filesBuilder.containsKey(k));
              childrenBuilder.put(k, Either.ofLeft(v));
              return;
            }

            if (existingChild.isRight()) {
              existingChild.getRight().merge(v);
              return;
            }

            // Reference equality okay, these are interned.
            if (v == existingChild.getLeft()) {
              return;
            }

            getMutableDirectory(k).merge(v);
          });
    }

    // TODO(cjhopman): Should this only make a child mutable if that child doesn't contain the item
    // we are about to add?
    private TreeNodeBuilder getMutableDirectory(String name) {
      checkName(name);
      Verify.verify(!symlinksBuilder.containsKey(name));
      Verify.verify(!filesBuilder.containsKey(name));
      return childrenBuilder
          .compute(
              name,
              (ignored, value) ->
                  Either.ofRight(
                      value == null
                          ? new TreeNodeBuilder()
                          : value.transform(TreeNodeBuilder::new, right -> right)))
          .getRight();
    }

    private void checkName(String name) {
      Verify.verify(!name.equals("."));
      Verify.verify(!name.equals(".."));
    }

    public MerkleTreeNode build(Interner<MerkleTreeNode> nodeInterner) {
      ImmutableSortedMap.Builder<String, MerkleTreeNode> children =
          ImmutableSortedMap.naturalOrder();
      for (Entry<String, Either<MerkleTreeNode, TreeNodeBuilder>> entry :
          childrenBuilder.entrySet()) {
        children.put(
            entry.getKey(),
            entry.getValue().transform(left -> left, builder -> builder.build(nodeInterner)));
      }

      return nodeInterner.intern(
          new MerkleTreeNode(
              children.build(),
              ImmutableSortedMap.copyOf(filesBuilder),
              ImmutableSortedMap.copyOf(symlinksBuilder)));
    }
  }

  /** NodeData is the {@link Protocol} encoded data for a node. */
  public static class NodeData {
    private final Directory directory;
    private final Digest digest;

    NodeData(Directory directory, Digest digest) {
      this.directory = directory;
      this.digest = digest;
    }

    public Digest getDigest() {
      return digest;
    }

    public Directory getDirectory() {
      return directory;
    }
  }

  /** Used to get reference equality for interned objects. */
  private static class Reference<T> {
    private final T object;

    Reference(T object) {
      this.object = object;
    }

    @Override
    public int hashCode() {
      return object.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Reference)) {
        return false;
      }
      return this.object == ((Reference<?>) obj).object;
    }
  }
}
