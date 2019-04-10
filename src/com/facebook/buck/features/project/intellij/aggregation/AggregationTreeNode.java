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

import com.facebook.buck.features.project.intellij.model.IjModuleType;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A node in {@link AggregationTree}.
 *
 * <p>May point to a module or null if not module is present.
 */
class AggregationTreeNode {

  private final Map<Path, AggregationTreeNode> children = new HashMap<>();

  private AggregationModule module;

  private Path moduleBasePath;

  public AggregationTreeNode(AggregationModule module) {
    this.module = module;
  }

  public AggregationTreeNode(AggregationModule module, Path moduleBasePath) {
    this.module = module;
    this.moduleBasePath = moduleBasePath;
  }

  public AggregationTreeNode(Path moduleBasePath) {
    this.moduleBasePath = moduleBasePath;
  }

  public AggregationModule getModule() {
    return module;
  }

  public void setModule(AggregationModule module) {
    this.module = module;
  }

  public Path getModuleBasePath() {
    return module == null ? moduleBasePath : module.getModuleBasePath();
  }

  public void setModuleBasePath(Path moduleBasePath) {
    this.moduleBasePath = moduleBasePath;
  }

  public AggregationTreeNode getChild(Path pathComponent) {
    return children.get(pathComponent);
  }

  public ImmutableCollection<AggregationTreeNode> getChildren() {
    return ImmutableList.copyOf(children.values());
  }

  public ImmutableCollection<Path> getChildrenPaths() {
    return ImmutableList.copyOf(children.keySet());
  }

  public void addChild(Path newNodePathComponents, AggregationModule module) {
    Objects.requireNonNull(module);
    addChild(newNodePathComponents, module, module.getModuleBasePath());
  }

  /**
   * Adding a new child doing all necessary adjustments to the structure of the nodes to keep the
   * tree valid.
   */
  public void addChild(Path newNodePathComponents, AggregationModule module, Path moduleBasePath) {
    Path firstPathComponent = newNodePathComponents.getName(0);
    for (Path childPathComponents : children.keySet()) {
      if (childPathComponents.startsWith(firstPathComponent)) {
        if (newNodePathComponents.equals(childPathComponents)) {
          replaceCurrentModule(childPathComponents, module, moduleBasePath);
        } else if (newNodePathComponents.startsWith(childPathComponents)) {
          putNewNodeAfterChild(newNodePathComponents, childPathComponents, module, moduleBasePath);
        } else if (childPathComponents.startsWith(newNodePathComponents)) {
          putNewNodeBeforeChild(newNodePathComponents, childPathComponents, module, moduleBasePath);
        } else {
          addModuleWithCommonSubpath(
              newNodePathComponents, childPathComponents, module, moduleBasePath);
        }
        return;
      }
    }

    children.put(newNodePathComponents, new AggregationTreeNode(module, moduleBasePath));
  }

  private void replaceCurrentModule(
      Path childPathComponents, AggregationModule module, Path moduleBasePath) {
    AggregationTreeNode child = children.get(childPathComponents);
    child.setModule(module);
    child.setModuleBasePath(moduleBasePath);
  }

  /**
   * Covers the following situation:
   *
   * <p>new node location: a/b/c/d/e
   *
   * <p>existing node location: a/b/c
   *
   * <p>This should result in: a/b/c (existing) -> d/e (new)
   */
  private void putNewNodeAfterChild(
      Path newNodePath, Path childPath, AggregationModule module, Path moduleBasePath) {
    AggregationTreeNode existingChild = children.get(childPath);
    existingChild.addChild(childPath.relativize(newNodePath), module, moduleBasePath);
  }

  /**
   * Covers the following situation:
   *
   * <p>new node location: a/b/c
   *
   * <p>existing node location: a/b/c/d/e
   *
   * <p>This should result in: a/b/c (new) -> d/e (existing)
   */
  private void putNewNodeBeforeChild(
      Path newNodePath, Path childPath, AggregationModule module, Path moduleBasePath) {
    AggregationTreeNode newChild = new AggregationTreeNode(module, moduleBasePath);
    AggregationTreeNode existingChild = children.get(childPath);

    newChild.children.put(newNodePath.relativize(childPath), existingChild);
    children.put(newNodePath, newChild);
    children.remove(childPath);
  }

  /**
   * Covers the following situation:
   *
   * <p>new node location: a/b/c/d/e
   *
   * <p>existing node location: a/b/c/f/g
   *
   * <p>This should result in: a/b/c (artificial) -> d/e (new), f/g (existing)
   */
  private void addModuleWithCommonSubpath(
      Path newNodePathComponents,
      Path childPathComponents,
      AggregationModule module,
      Path moduleBasePath) {
    Path commonPath = findCommonPath(newNodePathComponents, childPathComponents);
    AggregationTreeNode oldChild = children.remove(childPathComponents);
    AggregationTreeNode dummyNode =
        new AggregationTreeNode(getModuleBasePath().resolve(commonPath));
    children.put(commonPath, dummyNode);
    dummyNode.children.put(commonPath.relativize(childPathComponents), oldChild);
    dummyNode.children.put(
        commonPath.relativize(newNodePathComponents),
        new AggregationTreeNode(module, moduleBasePath));
  }

  private static Path findCommonPath(Path path1, Path path2) {
    int nameIndex = 1;
    while (nameIndex <= path1.getNameCount()
        && nameIndex <= path2.getNameCount()
        && path1.subpath(0, nameIndex).equals(path2.subpath(0, nameIndex))) {
      nameIndex++;
    }
    return path1.subpath(0, nameIndex - 1);
  }

  /** Collects nodes located at a path with the provided length. */
  public Collection<AggregationTreeNode> collectNodes(int minimumPathDepth) {
    ImmutableList.Builder<AggregationTreeNode> result = ImmutableList.builder();
    collectNodes(minimumPathDepth, result);
    return result.build();
  }

  private void collectNodes(int depth, ImmutableList.Builder<AggregationTreeNode> result) {
    if (depth == 0) {
      result.add(this);
      return;
    }

    for (Map.Entry<Path, AggregationTreeNode> child : children.entrySet()) {
      Path childPath = child.getKey();
      Preconditions.checkArgument(childPath.getNameCount() <= depth);
      if (childPath.getNameCount() < depth) {
        child.getValue().collectNodes(depth - childPath.getNameCount(), result);
      } else {
        result.add(child.getValue());
      }
    }
  }

  /** Removes a child and promotes its children to the current node. */
  public void removeChild(Path childPath) {
    AggregationTreeNode childNode = getChild(childPath);

    Map<Path, AggregationTreeNode> nodesToKeep = new HashMap<>();
    for (Map.Entry<Path, AggregationTreeNode> grandChild : childNode.children.entrySet()) {
      nodesToKeep.put(childPath.resolve(grandChild.getKey()), grandChild.getValue());
    }
    children.remove(childPath);

    nodesToKeep.entrySet().forEach(n -> children.put(n.getKey(), n.getValue()));
  }

  public ImmutableSet<Path> getChildrenPathsByModuleType(IjModuleType moduleType) {
    return children.entrySet().stream()
        .filter(
            e ->
                e.getValue().getModule() != null
                    && moduleType.equals(e.getValue().getModule().getModuleType()))
        .map(Map.Entry::getKey)
        .collect(ImmutableSet.toImmutableSet());
  }

  public ImmutableSet<Path> getChildrenPathsByModuleTypeOrTag(
      IjModuleType moduleType, String aggregationTag) {
    return children.entrySet().stream()
        .filter(e -> e.getValue().getModule() != null)
        .filter(
            e -> {
              String childAggregationTag = e.getValue().getModule().getAggregationTag();
              return Objects.equals(aggregationTag, childAggregationTag)
                  || moduleType.equals(e.getValue().getModule().getModuleType());
            })
        .map(Map.Entry::getKey)
        .collect(ImmutableSet.toImmutableSet());
  }
}
