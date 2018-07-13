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
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.GraphTraversable;
import com.facebook.buck.log.Logger;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A tree that is responsible for managing and aggregating modules.
 *
 * <p>The tree accepts an arbitrary number of modules each of which is located at a specific
 * location.
 *
 * <p>The tree can aggregate the modules by merging modules with the same aggregation tag into one
 * module. The aggregation happens at some specific level.
 *
 * <p>This data structure is based on a <a href="https://en.wikipedia.org/wiki/Trie">prefix
 * tree</a>.
 */
public class AggregationTree implements GraphTraversable<AggregationTreeNode> {

  private static final Logger LOG = Logger.get(AggregationTree.class);

  private final IjProjectConfig projectConfig;
  private final AggregationTreeNode rootNode;

  public AggregationTree(IjProjectConfig projectConfig, AggregationModule module) {
    this.projectConfig = projectConfig;
    this.rootNode = new AggregationTreeNode(module);
  }

  public void addModule(Path moduleBasePath, AggregationModule module) {
    rootNode.addChild(moduleBasePath, module);
  }

  @Override
  public Iterator<AggregationTreeNode> findChildren(AggregationTreeNode node) {
    return node.getChildren().iterator();
  }

  public void aggregateModules(int minimumPathDepth) {
    LOG.verbose("Aggregating modules with depth %s", minimumPathDepth);

    createStartingNodes(rootNode, minimumPathDepth);

    try {
      Iterable<AggregationTreeNode> nodesToTraverse =
          new AcyclicDepthFirstPostOrderTraversal<>(this)
              .traverse(collectStartingNodes(minimumPathDepth));
      for (AggregationTreeNode node : nodesToTraverse) {
        aggregateModules(node);
      }
    } catch (AcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new IllegalStateException("Cycle detected despite using a tree", e);
    }
  }

  /**
   * Make sure aggregation nodes are present at the starting locations of aggregation.
   *
   * <p>Creates artificial nodes if some nodes are not present. These nodes contain only module
   * paths.
   */
  private void createStartingNodes(AggregationTreeNode node, int depth) {
    if (depth <= 0) {
      return;
    }

    for (Path childPath : node.getChildrenPaths()) {
      if (childPath.getNameCount() > depth) {
        createStartingNode(node, depth, childPath);
      } else if (childPath.getNameCount() < depth) {
        createStartingNodes(node.getChild(childPath), depth - childPath.getNameCount());
      }
    }
  }

  private void createStartingNode(AggregationTreeNode node, int depth, Path childPath) {
    Path newChildPath = childPath.subpath(0, depth);
    node.addChild(newChildPath, null, node.getModuleBasePath().resolve(newChildPath));
  }

  /**
   * Analyzes node's children and returns the best aggregation tag (the one with the maximum number
   * of modules).
   *
   * <p>The following modules are excluded from this logic: - modules with UNKNOWN type because it
   * can be aggregated into all other modules, - modules with types that explicitly prohibit
   * aggregation.
   */
  private @Nullable String findBestAggregationTag(AggregationTreeNode node) {
    Map<String, Integer> typeCount = new HashMap<>();

    node.getChildren()
        .forEach(
            n -> {
              AggregationModule module = n.getModule();
              if (module == null) {
                return;
              }
              if (IjModuleType.UNKNOWN_MODULE.equals(module.getModuleType())
                  || !module.getModuleType().canBeAggregated(projectConfig)) {
                return;
              }
              String aggregationTag = module.getAggregationTag();
              Integer count = typeCount.get(aggregationTag);
              typeCount.put(aggregationTag, count == null ? 1 : count + 1);
            });

    if (typeCount.isEmpty()) {
      return null;
    }

    return Collections.max(typeCount.entrySet(), Comparator.comparingInt(Map.Entry::getValue))
        .getKey();
  }

  private Collection<AggregationTreeNode> collectStartingNodes(int minimumPathDepth) {
    return rootNode.collectNodes(minimumPathDepth);
  }

  private void aggregateModules(AggregationTreeNode parentNode) {
    if (parentNode.getChildren().isEmpty()) {
      return;
    }

    AggregationModule nodeModule = parentNode.getModule();

    if (nodeModule != null && !nodeModule.getModuleType().canBeAggregated(projectConfig)) {
      return;
    }

    Path moduleBasePath = parentNode.getModuleBasePath();

    LOG.verbose("Aggregating module at %s: %s", moduleBasePath, nodeModule);

    String aggregationTag;
    IjModuleType rootModuleType;
    if (nodeModule == null) {
      aggregationTag = findBestAggregationTag(parentNode);
      rootModuleType = null;
    } else {
      aggregationTag = nodeModule.getAggregationTag();
      rootModuleType = nodeModule.getModuleType();
    }

    ImmutableSet<Path> modulePathsToAggregate;
    if (aggregationTag == null) {
      modulePathsToAggregate = parentNode.getChildrenPathsByModuleType(IjModuleType.UNKNOWN_MODULE);
      if (modulePathsToAggregate.isEmpty()) {
        return;
      }
      rootModuleType = IjModuleType.UNKNOWN_MODULE;
    } else {
      modulePathsToAggregate =
          parentNode.getChildrenPathsByModuleTypeOrTag(IjModuleType.UNKNOWN_MODULE, aggregationTag);

      if (rootModuleType == null) {
        rootModuleType =
            parentNode
                .getChild(modulePathsToAggregate.iterator().next())
                .getModule()
                .getModuleType();
      }
    }

    Map<Path, AggregationModule> modulesToAggregate =
        collectModulesToAggregate(rootModuleType, parentNode, modulePathsToAggregate);

    modulesToAggregate.keySet().forEach(parentNode::removeChild);

    if (nodeModule == null) {
      parentNode.setModule(
          ModuleAggregator.aggregate(
              moduleBasePath,
              rootModuleType,
              aggregationTag == null
                  ? modulesToAggregate.values().iterator().next().getAggregationTag()
                  : aggregationTag,
              modulesToAggregate.values()));
    } else {
      parentNode.setModule(ModuleAggregator.aggregate(nodeModule, modulesToAggregate.values()));
    }
    LOG.verbose("Module after aggregation: %s", parentNode.getModule());
  }

  private ImmutableMap<Path, AggregationModule> collectModulesToAggregate(
      IjModuleType rootModuleType,
      AggregationTreeNode parentNode,
      ImmutableSet<Path> modulePathsToAggregate) {
    int aggregationLimit = rootModuleType.getAggregationLimit(projectConfig);
    ImmutableMap<Path, AggregationModule> modules =
        modulePathsToAggregate
            .stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    path -> path, path -> parentNode.getChild(path).getModule()));
    if (aggregationLimit == Integer.MAX_VALUE) {
      return modules;
    }

    ImmutableMap.Builder<Path, AggregationModule> filteredModules = ImmutableMap.builder();
    int count = 0;
    if (parentNode.getModule() != null) {
      count += parentNode.getModule().getTargets().size();
    }
    for (Map.Entry<Path, AggregationModule> pathWithModule : modules.entrySet()) {
      int childTargetsSize = pathWithModule.getValue().getTargets().size();
      if (count + childTargetsSize > aggregationLimit) {
        continue;
      }
      filteredModules.put(pathWithModule.getKey(), pathWithModule.getValue());
      count += childTargetsSize;
      if (count == aggregationLimit) {
        break;
      }
    }
    return filteredModules.build();
  }

  public Collection<AggregationModule> getModules() {
    try {
      List<AggregationModule> result = new ArrayList<>();
      new AcyclicDepthFirstPostOrderTraversal<>(this)
          .traverse(Collections.singleton(rootNode))
          .forEach(
              node -> {
                if (node.getModule() != null) {
                  result.add(node.getModule());
                }
              });
      return result;
    } catch (AcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new IllegalStateException("Cycle detected despite using a tree", e);
    }
  }
}
