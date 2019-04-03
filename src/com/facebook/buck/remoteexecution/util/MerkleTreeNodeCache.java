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
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * MerkleTreeNodeCache is used to create and merge merkle trees for action inputs. The nodes are
 * interned.
 *
 * <p>It also provides methods to get the {@link Protocol} encoded merkle tree data structures
 * (these values are cached once computed for a node).
 */
public class MerkleTreeNodeCache {
  private final Interner<MerkleTreeNode> nodeInterner = Interners.newWeakInterner();

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
          checkName(fileNode.getName());
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
          checkName(target.getName());
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
    return node.getData(protocol);
  }

  /** Represents a node in the merkle tree of files and symlinks. */
  public static class MerkleTreeNode {
    @Nullable private volatile NodeData data;

    private final int hashCode;

    @Nullable private final Path path;
    private final ImmutableSortedMap<Path, MerkleTreeNode> children;
    private final ImmutableSortedMap<Path, FileNode> files;
    private final ImmutableSortedMap<Path, SymlinkNode> symlinks;

    MerkleTreeNode(
        @Nullable Path path,
        ImmutableSortedMap<Path, MerkleTreeNode> children,
        ImmutableSortedMap<Path, FileNode> files,
        ImmutableSortedMap<Path, SymlinkNode> symlinks) {
      this.path = path;
      this.children = children;
      this.files = files;
      this.symlinks = symlinks;

      this.hashCode = Objects.hash(path, children, files, symlinks);
    }

    /**
     * Iterate over the files in the tree rooted at this node. The consumer will be called with the
     * fall path resolved against root.
     */
    public void forAllFiles(BiConsumer<Path, FileNode> nodeConsumer) {
      files.forEach(nodeConsumer::accept);
      children.forEach((key, value) -> value.forAllFiles(nodeConsumer));
    }

    /** Iterate over the nodes in the tree rooted at this node. */
    public void forAllNodes(Consumer<MerkleTreeNode> nodeConsumer) {
      nodeConsumer.accept(this);
      children.values().forEach(child -> child.forAllNodes(nodeConsumer));
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

      return Objects.equals(path, other.path)
          && Objects.equals(children, other.children)
          && Objects.equals(files, other.files)
          && Objects.equals(symlinks, other.symlinks);
    }

    private NodeData getData(Protocol protocol) {
      if (data != null) {
        return data;
      }

      // It's unlikely, but possible that multiple threads get here... that's okay they'll all
      // compute the same thing.
      List<DirectoryNode> childNodes = new ArrayList<>();
      long totalInputsSize = 0;
      for (Map.Entry<Path, MerkleTreeNode> entry : children.entrySet()) {
        MerkleTreeNode child = entry.getValue();
        NodeData childData = child.getData(protocol);
        totalInputsSize += childData.totalInputsSize;
        childNodes.add(
            protocol.newDirectoryNode(entry.getKey().getFileName().toString(), childData.digest));
      }
      for (FileNode value : files.values()) {
        totalInputsSize += value.getDigest().getSize();
      }
      Directory directory = protocol.newDirectory(childNodes, files.values(), symlinks.values());
      NodeData nodeData =
          new NodeData(directory, protocol.computeDigest(directory), totalInputsSize);
      this.data = nodeData;
      return nodeData;
    }
  }

  private static class TreeNodeBuilder {
    @Nullable private final Path path;
    private final Map<Path, Either<MerkleTreeNode, TreeNodeBuilder>> childrenBuilder =
        new HashMap<>();
    private final Map<Path, FileNode> filesBuilder = new HashMap<>();
    private final Map<Path, SymlinkNode> symlinksBuilder = new HashMap<>();

    public TreeNodeBuilder() {
      this((Path) null);
    }

    public TreeNodeBuilder(Path path) {
      this.path = path;
      if (path != null) checkName(path.getFileName().toString());
    }

    public TreeNodeBuilder(MerkleTreeNode from) {
      this(from.path);
      merge(from);
    }

    private void addFile(Path pathFragment, FileNode fileNode) {
      Verify.verify(pathFragment.getNameCount() > 0);
      getMutableParentDirectory(pathFragment).addFileImpl(pathFragment, fileNode);
    }

    private void addFileImpl(Path name, FileNode fileNode) {
      checkName(fileNode.getName());
      Verify.verify(!childrenBuilder.containsKey(name));
      Verify.verify(!symlinksBuilder.containsKey(name));
      FileNode previous = filesBuilder.putIfAbsent(name, fileNode);
      Verify.verify(previous == null || previous.equals(fileNode));
    }

    private void addSymlink(Path pathFragment, SymlinkNode target) {
      Verify.verify(pathFragment.getNameCount() > 0);
      getMutableParentDirectory(pathFragment).addSymlinkImpl(pathFragment, target);
    }

    private void addSymlinkImpl(Path path, SymlinkNode target) {
      checkName(target.getName());
      Verify.verify(!childrenBuilder.containsKey(path));
      Verify.verify(!filesBuilder.containsKey(path));
      SymlinkNode previous = symlinksBuilder.putIfAbsent(path, target);
      Verify.verify(previous == null || previous.equals(target));
    }

    private TreeNodeBuilder getMutableParentDirectory(Path pathFragment) {
      int segments = pathFragment.getNameCount();
      if (segments == getPathSegments() + 1) {
        return this;
      }
      return getMutableDirectory(pathFragment).getMutableParentDirectory(pathFragment);
    }

    private int getPathSegments() {
      return path == null ? 0 : path.getNameCount();
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
    private TreeNodeBuilder getMutableDirectory(Path dir) {
      Preconditions.checkArgument(dir.getNameCount() > getPathSegments());
      Path subPath = dir.subpath(0, getPathSegments() + 1);

      Verify.verify(!symlinksBuilder.containsKey(subPath));
      Verify.verify(!filesBuilder.containsKey(subPath));
      return childrenBuilder
          .compute(
              subPath,
              (ignored, value) ->
                  Either.ofRight(
                      value == null
                          ? new TreeNodeBuilder(subPath)
                          : value.transform(TreeNodeBuilder::new, right -> right)))
          .getRight();
    }

    public MerkleTreeNode build(Interner<MerkleTreeNode> nodeInterner) {
      ImmutableSortedMap.Builder<Path, MerkleTreeNode> children = ImmutableSortedMap.naturalOrder();
      childrenBuilder.forEach(
          (key, value) ->
              children.put(
                  key, value.transform(left -> left, builder -> builder.build(nodeInterner))));

      return nodeInterner.intern(
          new MerkleTreeNode(
              this.path,
              children.build(),
              ImmutableSortedMap.copyOf(filesBuilder),
              ImmutableSortedMap.copyOf(symlinksBuilder)));
    }
  }

  private static void checkName(String name) {
    Verify.verify(!name.equals("."));
    Verify.verify(!name.equals(".."));
  }

  /** NodeData is the {@link Protocol} encoded data for a node. */
  public static class NodeData {
    private final Directory directory;
    private final Digest digest;
    private final long totalInputsSize;

    NodeData(Directory directory, Digest digest, long totalInputsSize) {
      this.directory = directory;
      this.digest = digest;
      this.totalInputsSize = totalInputsSize;
    }

    public Digest getDigest() {
      return digest;
    }

    public Directory getDirectory() {
      return directory;
    }

    public long getTotalSize() {
      return totalInputsSize;
    }
  }
}
