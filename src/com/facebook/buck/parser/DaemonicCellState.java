/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.parser;

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.concurrent.AutoCloseableLock;
import com.facebook.buck.util.concurrent.AutoCloseableReadWriteUpdateLock;
import com.google.common.base.Optional;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import javax.annotation.concurrent.GuardedBy;

class DaemonicCellState {

  private static final Logger LOG = Logger.get(DaemonicCellState.class);

  private final Path cellRoot;
  private Cell cell;

  @GuardedBy("nodesAndTargetsLock")
  private final SetMultimap<Path, Path> buildFileDependents;
  @GuardedBy("nodesAndTargetsLock")
  private final SetMultimap<UnflavoredBuildTarget, BuildTarget> targetsCornucopia;
  @GuardedBy("nodesAndTargetsLock")
  private final Map<Path, ImmutableMap<String, ImmutableMap<String, Optional<String>>>>
    buildFileConfigs;
  @GuardedBy("nodesAndTargetsLock")
  private final ConcurrentMapCache<Path, ImmutableList<Map<String, Object>>> allRawNodes;
  @GuardedBy("nodesAndTargetsLock")
  private final ConcurrentMapCache<BuildTarget, TargetNode<?>> allTargetNodes;

  private final AutoCloseableReadWriteUpdateLock nodesAndTargetsLock;

  DaemonicCellState(Cell cell, int parsingThreads) {
    this.cell = cell;
    this.cellRoot = cell.getRoot();
    this.buildFileDependents = HashMultimap.<Path, Path>create();
    this.targetsCornucopia = HashMultimap.<UnflavoredBuildTarget, BuildTarget>create();
    this.buildFileConfigs =
      new HashMap<Path, ImmutableMap<String, ImmutableMap<String, Optional<String>>>>();
    this.allRawNodes =
      new ConcurrentMapCache<Path, ImmutableList<Map<String, Object>>>(parsingThreads);
    this.allTargetNodes = new ConcurrentMapCache<BuildTarget, TargetNode<?>>(parsingThreads);
    this.nodesAndTargetsLock = new AutoCloseableReadWriteUpdateLock();
  }

  // TODO(mzlee): Only needed for invalidateBasedOn which does not have access to cell metadata
  Cell getCell() {
    return cell;
  }

  Path getCellRoot() {
    return cellRoot;
  }

  Optional<TargetNode<?>> lookupTargetNode(BuildTarget target) {
    try (AutoCloseableLock readLock = nodesAndTargetsLock.readLock()) {
      return Optional.<TargetNode<?>>fromNullable(allTargetNodes.getIfPresent(target));
    }
  }

  Optional<ImmutableList<Map<String, Object>>> lookupRawNodes(Path buildFile) {
    try (AutoCloseableLock readLock = nodesAndTargetsLock.readLock()) {
      return Optional.fromNullable(allRawNodes.getIfPresent(buildFile));
    }
  }

  TargetNode<?> putTargetNodeIfNotPresent(
      final BuildTarget target,
      TargetNode<?> targetNode) {
    try (AutoCloseableLock writeLock = nodesAndTargetsLock.writeLock()) {
      TargetNode<?> updatedNode = allTargetNodes.get(target, targetNode);
      if (updatedNode.equals(targetNode)) {
        targetsCornucopia.put(target.getUnflavoredBuildTarget(), target);
      }
      return updatedNode;
    }
  }

  ImmutableList<Map<String, Object>> putRawNodesIfNotPresentAndStripMetaEntries(
      final Path buildFile,
      final ImmutableList<Map<String, Object>> withoutMetaIncludes,
      final ImmutableSet<Path> dependentsOfEveryNode,
      ImmutableMap<String, ImmutableMap<String, Optional<String>>> configs) {
    try (AutoCloseableLock writeLock = nodesAndTargetsLock.writeLock()) {
      ImmutableList<Map<String, Object>> updated =
          allRawNodes.get(buildFile, withoutMetaIncludes);
      buildFileConfigs.put(buildFile, configs);
      if (updated == withoutMetaIncludes) {
        // We now know all the nodes. They all implicitly depend on everything in
        // the "dependentsOfEveryNode" set.
        for (Path dependent : dependentsOfEveryNode) {
          buildFileDependents.put(dependent, buildFile);
        }
      }
      return updated;
    }
  }

  int invalidatePath(Path path) {
    try (AutoCloseableLock writeLock = nodesAndTargetsLock.writeLock()) {
      int invalidatedRawNodes = 0;
      List<Map<String, Object>> rawNodes = allRawNodes.getIfPresent(path);
      if (rawNodes != null) {
        // Increment the counter
        invalidatedRawNodes = rawNodes.size();
        for (Map<String, Object> rawNode : rawNodes) {
          UnflavoredBuildTarget target =
              ParsePipeline.parseBuildTargetFromRawRule(cell.getRoot(), rawNode, path);
          LOG.debug("Invalidating target for path %s: %s", path, target);
          allTargetNodes.invalidateAll(targetsCornucopia.get(target));
          targetsCornucopia.removeAll(target);
        }
        allRawNodes.invalidate(path);
      }

      // We may have been given a file that other build files depend on. Iteratively remove those.
      Iterable<Path> dependents = buildFileDependents.get(path);
      LOG.debug("Invalidating dependents for path %s: %s", path, dependents);
      for (Path dependent : dependents) {
        if (dependent.equals(path)) {
          continue;
        }
        invalidatedRawNodes += invalidatePath(dependent);
      }
      buildFileDependents.removeAll(path);
      buildFileConfigs.remove(path);

      return invalidatedRawNodes;
    }
  }

  void invalidateIfBuckConfigHasChanged(Cell cell, Path buildFile) {
    try (AutoCloseableLock writeLock = nodesAndTargetsLock.writeLock()) {
      // TODO(mzlee): Check whether usedConfigs includes the buildFileName
      ImmutableMap<String, ImmutableMap<String, Optional<String>>> usedConfigs =
          buildFileConfigs.get(buildFile);
      if (usedConfigs == null) {
        // TODO(mzlee): Figure out when/how we can safely update this
        this.cell = cell;
        return;
      }
      for (Map.Entry<String, ImmutableMap<String, Optional<String>>> keyEnt :
             usedConfigs.entrySet()) {
        for (Map.Entry<String, Optional<String>> valueEnt : keyEnt.getValue().entrySet()) {
          Optional<String> value =
              cell.getBuckConfig().getValue(keyEnt.getKey(), valueEnt.getKey());
          if (!value.equals(valueEnt.getValue())) {
            invalidatePath(buildFile);
            this.cell = cell;
            return;
          }
        }
      }
    }
  }

}
