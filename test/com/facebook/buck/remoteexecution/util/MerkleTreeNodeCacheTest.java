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

package com.facebook.buck.remoteexecution.util;

import static com.google.common.collect.Iterables.getOnlyElement;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Directory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.DirectoryNode;
import com.facebook.buck.remoteexecution.interfaces.Protocol.FileNode;
import com.facebook.buck.remoteexecution.interfaces.Protocol.SymlinkNode;
import com.facebook.buck.remoteexecution.util.MerkleTreeNodeCache.MerkleTreeNode;
import com.facebook.buck.remoteexecution.util.MerkleTreeNodeCache.NodeData;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class MerkleTreeNodeCacheTest {
  @Rule public TemporaryPaths tmpRoot = new TemporaryPaths();
  @Rule public ExpectedException expected = ExpectedException.none();
  private GrpcProtocol protocol = new GrpcProtocol();

  @Test
  public void testSimple() {
    MerkleTreeNodeCache nodeCache = new MerkleTreeNodeCache(protocol);
    MerkleTreeNode node =
        nodeCache.createNode(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of());

    // There shouldn't be any files.
    node.forAllFiles((path, n) -> fail());

    NodeData data = nodeCache.getData(node);
    assertTrue(data.getDirectory().getDirectoriesList().isEmpty());
    assertTrue(data.getDirectory().getFilesList().isEmpty());
    assertTrue(data.getDirectory().getSymlinksList().isEmpty());

    assertSame(node, nodeCache.createNode(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()));
  }

  @Test
  public void testCreatedTreeIsCorrect() {
    MerkleTreeNodeCache nodeCache = new MerkleTreeNodeCache(protocol);
    /* Creates directory structure like:
    cat/file.1 hash1
    cat/file.2 hash2
    cat/file.3 hash3

    dog/file.4 hash4
    dog/symlink.1 -> first/cat/file.1
    dog/food/file.5 hash5
    dog/food/file.6 hash6

    pig/file.4 hash4
    pig/symlink.1 -> first/cat/file.1
    pig/food/file.5 hash5
    pig/food/file.6 hash6

    symlink.2 -> something/file.4

    duck/duck1/duck2/duck3

    Note that dog/ and pig/ have the same contents
     */

    Path catDir = Paths.get("cat");
    Path dogDir = Paths.get("dog");
    Path pigDir = Paths.get("pig");
    Path duckDir = Paths.get("duck");
    Path duckEmptyNestedDir = duckDir.resolve("duck1/duck2/duck3");

    Path dogFoodDir = dogDir.resolve("food");
    Path pigFoodDir = pigDir.resolve("food");

    // Use suppliers so that we get a different instances of everything all the time (to ensure that
    // the node cache behavior doesn't depend on reference equality).
    Digest hash1 = protocol.computeDigest("hash1".getBytes(Charsets.UTF_8));
    Digest hash2 = protocol.computeDigest("hash2".getBytes(Charsets.UTF_8));
    Digest hash3 = protocol.computeDigest("hash3".getBytes(Charsets.UTF_8));
    Digest hash4 = protocol.computeDigest("hash4".getBytes(Charsets.UTF_8));
    Digest hash5 = protocol.computeDigest("hash5".getBytes(Charsets.UTF_8));
    Digest hash6 = protocol.computeDigest("hash6".getBytes(Charsets.UTF_8));
    Digest emptyDirectoryDigest =
        protocol.computeDigest(
            protocol.newDirectory(ImmutableList.of(), ImmutableList.of(), ImmutableList.of()));

    Supplier<ImmutableMap<Path, FileNode>> filesSupplier =
        () ->
            ImmutableMap.<Path, FileNode>builder()
                // cat dir
                .put(catDir.resolve("file.1"), protocol.newFileNode(hash1, "file.1", false))
                .put(catDir.resolve("file.2"), protocol.newFileNode(hash2, "file.2", false))
                .put(catDir.resolve("file.3"), protocol.newFileNode(hash3, "file.3", false))
                // dog dir
                .put(dogDir.resolve("file.4"), protocol.newFileNode(hash4, "file.4", false))
                .put(dogFoodDir.resolve("file.5"), protocol.newFileNode(hash5, "file.5", false))
                .put(dogFoodDir.resolve("file.6"), protocol.newFileNode(hash6, "file.6", false))
                // pig dir
                .put(pigDir.resolve("file.4"), protocol.newFileNode(hash4, "file.4", false))
                .put(pigFoodDir.resolve("file.5"), protocol.newFileNode(hash5, "file.5", false))
                .put(pigFoodDir.resolve("file.6"), protocol.newFileNode(hash6, "file.6", false))
                .build();
    Supplier<ImmutableMap<Path, SymlinkNode>> symlinksSupplier =
        () ->
            ImmutableMap.<Path, SymlinkNode>builder()
                .put(
                    dogDir.resolve("symlink.1"),
                    protocol.newSymlinkNode("symlink.1", catDir.resolve("file.1")))
                .put(
                    pigDir.resolve("symlink.1"),
                    protocol.newSymlinkNode("symlink.1", catDir.resolve("file.1")))
                .put(
                    Paths.get("symlink.2"),
                    protocol.newSymlinkNode("symlink.2", pigDir.resolve("file.4")))
                .build();

    Supplier<ImmutableMap<Path, DirectoryNode>> emptyDirectoriesSupplier =
        () ->
            ImmutableMap.<Path, DirectoryNode>builder()
                .put(
                    duckEmptyNestedDir,
                    protocol.newDirectoryNode(
                        duckEmptyNestedDir.getFileName().toString(), emptyDirectoryDigest))
                .build();

    MerkleTreeNode node =
        nodeCache.createNode(
            filesSupplier.get(), symlinksSupplier.get(), emptyDirectoriesSupplier.get());
    assertSame(
        node,
        nodeCache.createNode(
            filesSupplier.get(), symlinksSupplier.get(), emptyDirectoriesSupplier.get()));

    // There shouldn't be any files.
    Set<Path> filePaths = new HashSet<>();
    node.forAllFiles((path, n) -> filePaths.add(path));

    Set<Path> expectedPaths = new HashSet<>();
    filesSupplier.get().keySet().forEach(p -> expectedPaths.add(p));
    assertEquals(expectedPaths, filePaths);

    Map<Digest, NodeData> dataMap = new HashMap<>();
    nodeCache.forAllData(node, data -> dataMap.put(data.getDigest(), data));

    NodeData rootData = dataMap.get(nodeCache.getData(node).getDigest());
    Directory rootDirectory = rootData.getDirectory();

    assertTrue(rootDirectory.getFilesList().isEmpty());
    MoreAsserts.assertIterablesEquals(
        rootDirectory.getSymlinksList(),
        ImmutableList.of(symlinksSupplier.get().get(Paths.get("symlink.2"))));

    Collection<DirectoryNode> rootDirectories = rootDirectory.getDirectoriesList();
    assertEquals(4, rootDirectories.size());

    DirectoryNode catDirNode = getDirNodeEqualsName(rootDirectories, "cat");
    NodeData catData = dataMap.get(catDirNode.getDigest());
    Directory cat = catData.getDirectory();

    DirectoryNode dogDirNode = getDirNodeEqualsName(rootDirectories, "dog");
    NodeData dogData = dataMap.get(dogDirNode.getDigest());
    Directory dog = dogData.getDirectory();

    assertTrue(cat.getSymlinksList().isEmpty());
    assertTrue(cat.getDirectoriesList().isEmpty());
    MoreAsserts.assertIterablesEquals(
        cat.getFilesList(),
        Maps.filterKeys(filesSupplier.get(), other -> catDir.equals(other.getParent())).values());

    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(symlinksSupplier.get().get(dogDir.resolve("symlink.1"))),
        dog.getSymlinksList());
    assertEquals(1, dog.getDirectoriesList().size());
    MoreAsserts.assertIterablesEquals(
        dog.getFilesList(),
        Maps.filterKeys(filesSupplier.get(), other -> dogDir.equals(other.getParent())).values());

    DirectoryNode pigDirNode = getDirNodeEqualsName(rootDirectories, "pig");
    assertEquals(pigDirNode.getDigest(), dogDirNode.getDigest());

    DirectoryNode duckDirNode = getDirNodeEqualsName(rootDirectories, "duck");
    NodeData duckData = dataMap.get(duckDirNode.getDigest());
    DirectoryNode duck1Dir = getOnlyElement(duckData.getDirectory().getDirectoriesList());
    NodeData duck1Data = dataMap.get(duck1Dir.getDigest());
    DirectoryNode duck2Dir = getOnlyElement(duck1Data.getDirectory().getDirectoriesList());
    NodeData duck2Data = dataMap.get(duck2Dir.getDigest());
    DirectoryNode duck3Dir = getOnlyElement(duck2Data.getDirectory().getDirectoriesList());
    NodeData duck3Data = dataMap.get(duck3Dir.getDigest());
    assertNull(duck3Data);

    assertEquals(45, rootData.getTotalSize());
    assertEquals(15, catData.getTotalSize());
    assertEquals(15, dogData.getTotalSize());
    assertEquals(0, duckData.getTotalSize());
  }

  private DirectoryNode getDirNodeEqualsName(
      Collection<DirectoryNode> rootDirectories, String dirName) {
    return rootDirectories.stream()
        .filter(directoryNode -> directoryNode.getName().equals(dirName))
        .findFirst()
        .get();
  }

  @Test
  public void testMerge() {
    Path catDir = Paths.get("cat");
    Path dogDir = Paths.get("dog");

    Path dogFoodDir = dogDir.resolve("food");

    // Use supplier so that we get a different Digest instance all the time (to ensure that the node
    // cache behavior doesn't depend on reference equality).
    Supplier<Digest> hash1 = () -> protocol.computeDigest("hash1".getBytes(Charsets.UTF_8));
    Supplier<Digest> hash2 = () -> protocol.computeDigest("hash2".getBytes(Charsets.UTF_8));
    Supplier<Digest> hash3 = () -> protocol.computeDigest("hash3".getBytes(Charsets.UTF_8));
    Supplier<Digest> hash4 = () -> protocol.computeDigest("hash4".getBytes(Charsets.UTF_8));
    Supplier<Digest> hash5 = () -> protocol.computeDigest("hash5".getBytes(Charsets.UTF_8));
    Supplier<Digest> hash6 = () -> protocol.computeDigest("hash6".getBytes(Charsets.UTF_8));

    ImmutableMap<Path, FileNode> firstFiles =
        ImmutableMap.<Path, FileNode>builder()
            .put(catDir.resolve("file.1"), protocol.newFileNode(hash1.get(), "file.1", false))
            .put(catDir.resolve("file.2"), protocol.newFileNode(hash2.get(), "file.2", false))
            .put(dogFoodDir.resolve("file.6"), protocol.newFileNode(hash6.get(), "file.6", false))
            .build();

    ImmutableMap<Path, FileNode> secondFiles =
        ImmutableMap.<Path, FileNode>builder()
            .put(catDir.resolve("file.3"), protocol.newFileNode(hash3.get(), "file.3", false))
            .put(dogDir.resolve("file.4"), protocol.newFileNode(hash4.get(), "file.4", false))
            .put(dogFoodDir.resolve("file.5"), protocol.newFileNode(hash5.get(), "file.5", false))
            .build();

    ImmutableMap<Path, SymlinkNode> firstSymlinks =
        ImmutableMap.<Path, SymlinkNode>builder()
            .put(
                dogDir.resolve("symlink.1"),
                protocol.newSymlinkNode("symlink.1", catDir.resolve("file.1")))
            .put(
                catDir.resolve("symlink.1"),
                protocol.newSymlinkNode("symlink.1", catDir.resolve("file.1")))
            .build();

    ImmutableMap<Path, SymlinkNode> secondSymlinks =
        ImmutableMap.<Path, SymlinkNode>builder()
            .put(
                dogDir.resolve("symlink.2"),
                protocol.newSymlinkNode("symlink.2", catDir.resolve("file.1")))
            .put(
                catDir.resolve("symlink.2"),
                protocol.newSymlinkNode("symlink.2", catDir.resolve("file.1")))
            .build();

    MerkleTreeNodeCache nodeCache = new MerkleTreeNodeCache(protocol);
    MerkleTreeNode firstNode = nodeCache.createNode(firstFiles, firstSymlinks, ImmutableMap.of());
    MerkleTreeNode secondNode =
        nodeCache.createNode(secondFiles, secondSymlinks, ImmutableMap.of());
    MerkleTreeNode combinedNode =
        nodeCache.createNode(
            ImmutableMap.<Path, FileNode>builder().putAll(firstFiles).putAll(secondFiles).build(),
            ImmutableMap.<Path, SymlinkNode>builder()
                .putAll(firstSymlinks)
                .putAll(secondSymlinks)
                .build(),
            ImmutableMap.of());

    assertSame(combinedNode, nodeCache.mergeNodes(ImmutableList.of(firstNode, secondNode)));

    assertEquals(30, nodeCache.getData(combinedNode).getTotalSize());
  }

  @Test
  public void badFileNodeNameIsRejected() {
    expected.expect(IllegalStateException.class);
    new MerkleTreeNodeCache(protocol)
        .createNode(
            ImmutableMap.of(
                Paths.get("file.name"),
                protocol.newFileNode(
                    protocol.computeDigest(new byte[] {}), "different.name", false)),
            ImmutableMap.of(),
            ImmutableMap.of());
  }

  @Test
  public void badSymlinkNodeNameIsRejected() {
    expected.expect(IllegalStateException.class);
    new MerkleTreeNodeCache(protocol)
        .createNode(
            ImmutableMap.of(),
            ImmutableMap.of(
                Paths.get("file.name"), protocol.newSymlinkNode("other.name", Paths.get("target"))),
            ImmutableMap.of());
  }
}
