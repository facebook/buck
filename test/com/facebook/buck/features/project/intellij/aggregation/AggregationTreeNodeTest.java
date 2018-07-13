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

package com.facebook.buck.features.project.intellij.aggregation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.facebook.buck.features.project.intellij.model.IjModuleType;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class AggregationTreeNodeTest {

  public static AggregationModule createModule(Path moduleBasePath, IjModuleType moduleType) {
    return createModule(moduleBasePath, moduleType, "");
  }

  public static AggregationModule createModule(
      Path moduleBasePath, IjModuleType moduleType, String aggregationTag) {
    return AggregationModule.builder()
        .setModuleType(moduleType)
        .setModuleBasePath(moduleBasePath)
        .setAggregationTag(aggregationTag)
        .build();
  }

  private static void addNode(
      AggregationTreeNode node, Path path, IjModuleType moduleType, String aggregationTag) {
    node.addChild(path, createModule(path, moduleType, aggregationTag), path);
  }

  public static void addNode(AggregationTreeNode node, Path path, IjModuleType moduleType) {
    addNode(node, path, moduleType, "");
  }

  public static void addNode(AggregationTreeNode node, Path path) {
    addNode(node, path, IjModuleType.UNKNOWN_MODULE);
  }

  @Test
  public void testPutNewNodeAfterChild() {
    AggregationTreeNode node = new AggregationTreeNode(Paths.get(""));

    Path existingNodePath = Paths.get("a/b/c");
    addNode(node, existingNodePath);
    Path newNodePath = Paths.get("a/b/c/d/e/f");
    addNode(node, newNodePath);

    assertEquals(ImmutableSet.of(existingNodePath), ImmutableSet.copyOf(node.getChildrenPaths()));
    AggregationTreeNode child = node.getChild(existingNodePath);
    Path grandChildPath = Paths.get("d/e/f");
    assertEquals(ImmutableSet.of(grandChildPath), ImmutableSet.copyOf(child.getChildrenPaths()));
    assertEquals(newNodePath, child.getChild(grandChildPath).getModuleBasePath());
  }

  @Test
  public void testPutNewNodeBeforeChild() {
    AggregationTreeNode node = new AggregationTreeNode(Paths.get(""));

    Path existingNodePath = Paths.get("a/b/c/d/e/f");
    addNode(node, existingNodePath);
    Path newNodePath = Paths.get("a/b/c");
    addNode(node, newNodePath);

    assertEquals(ImmutableSet.of(newNodePath), ImmutableSet.copyOf(node.getChildrenPaths()));
    AggregationTreeNode child = node.getChild(newNodePath);
    Path grandChildPath = Paths.get("d/e/f");
    assertEquals(ImmutableSet.of(grandChildPath), ImmutableSet.copyOf(child.getChildrenPaths()));
    assertEquals(existingNodePath, child.getChild(grandChildPath).getModuleBasePath());
  }

  @Test
  public void testReplaceCurrentModule() {
    AggregationTreeNode node = new AggregationTreeNode(Paths.get(""));

    Path childPath = Paths.get("a/b/c");
    addNode(node, childPath);
    Path newModuleBasePath = Paths.get("a/b/c/d/e");
    node.addChild(childPath, null, newModuleBasePath);

    assertEquals(newModuleBasePath, node.getChild(childPath).getModuleBasePath());
  }

  @Test
  public void testPutNewNodeWithCommonPath() {
    AggregationTreeNode node = new AggregationTreeNode(Paths.get(""));

    Path existingNodePath = Paths.get("a/b/c/d/e/f");
    addNode(node, existingNodePath);
    Path newNodePath = Paths.get("a/b/c/g/h/j");
    addNode(node, newNodePath);

    Path commonPath = Paths.get("a/b/c");
    assertEquals(ImmutableSet.of(commonPath), ImmutableSet.copyOf(node.getChildrenPaths()));
    assertNull(node.getChild(commonPath).getModule());
    assertEquals(commonPath, node.getChild(commonPath).getModuleBasePath());

    AggregationTreeNode child = node.getChild(commonPath);
    Path oldGrandChildPath = Paths.get("d/e/f");
    Path newGrandChildPath = Paths.get("g/h/j");
    assertEquals(
        ImmutableSet.of(oldGrandChildPath, newGrandChildPath),
        ImmutableSet.copyOf(child.getChildrenPaths()));
    assertEquals(existingNodePath, child.getChild(oldGrandChildPath).getModuleBasePath());
    assertEquals(newNodePath, child.getChild(newGrandChildPath).getModuleBasePath());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testCollectNodesFailsWithLongChild() {
    AggregationTreeNode node = new AggregationTreeNode(Paths.get(""));

    Path existingNodePath = Paths.get("a/b/c/d/e/f");
    addNode(node, existingNodePath);

    node.collectNodes(2);
  }

  @Test
  public void testCollectNodesReturnsValidNodes() {
    AggregationTreeNode node = new AggregationTreeNode(Paths.get(""));

    addNode(node, Paths.get("a/b/c"));
    addNode(node, Paths.get("a/b/d"));
    addNode(node, Paths.get("a/b/e"));
    addNode(node, Paths.get("a/c/d"));
    addNode(node, Paths.get("a/c/e"));
    addNode(node, Paths.get("a/d/c"));
    addNode(node, Paths.get("a/d/e"));
    addNode(node, Paths.get("a/e/c"));
    addNode(node, Paths.get("a/e/d"));

    assertEquals(
        ImmutableSet.of(Paths.get("a/b"), Paths.get("a/c"), Paths.get("a/d"), Paths.get("a/e")),
        node.collectNodes(2)
            .stream()
            .map(AggregationTreeNode::getModuleBasePath)
            .collect(ImmutableSet.toImmutableSet()));
  }

  @Test
  public void testRemovePromotesChildren() {
    AggregationTreeNode node = new AggregationTreeNode(Paths.get(""));

    addNode(node, Paths.get("a/b/c/d"));
    addNode(node, Paths.get("a/b/c/e"));
    addNode(node, Paths.get("a/b/c/f"));

    node.removeChild(Paths.get("a/b/c"));

    assertEquals(
        ImmutableSet.of(Paths.get("a/b/c/d"), Paths.get("a/b/c/e"), Paths.get("a/b/c/f")),
        ImmutableSet.copyOf(node.getChildrenPaths()));
  }

  @Test
  public void testGetChildrenPathsByModuleType() {
    AggregationTreeNode node = new AggregationTreeNode(Paths.get(""));

    addNode(node, Paths.get("a/b/c"), IjModuleType.JAVA_MODULE);
    addNode(node, Paths.get("a/b/d"), IjModuleType.UNKNOWN_MODULE);
    addNode(node, Paths.get("a/b/e"), IjModuleType.UNKNOWN_MODULE);

    assertEquals(
        ImmutableSet.of(Paths.get("d"), Paths.get("e")),
        node.getChild(Paths.get("a/b")).getChildrenPathsByModuleType(IjModuleType.UNKNOWN_MODULE));
  }

  @Test
  public void testGetChildrenPathsByModuleTypeAndTag() {
    AggregationTreeNode node = new AggregationTreeNode(Paths.get(""));

    addNode(node, Paths.get("a/b/c"), IjModuleType.JAVA_MODULE, "tag1");
    addNode(node, Paths.get("a/b/d"), IjModuleType.ANDROID_MODULE, "tag2");
    addNode(node, Paths.get("a/b/e"), IjModuleType.UNKNOWN_MODULE, "tag3");

    assertEquals(
        ImmutableSet.of(Paths.get("c"), Paths.get("e")),
        node.getChild(Paths.get("a/b"))
            .getChildrenPathsByModuleTypeOrTag(IjModuleType.UNKNOWN_MODULE, "tag1"));
  }
}
