/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesProvider;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class PerBuildState implements AutoCloseable {

  private final AtomicLong parseProcessedBytes = new AtomicLong();

  private final KnownBuildRuleTypesProvider knownBuildRuleTypesProvider;
  private final CellManager cellManager;
  private final RawNodeParsePipeline rawNodeParsePipeline;
  private final TargetNodeParsePipeline targetNodeParsePipeline;

  PerBuildState(
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      CellManager cellManager,
      RawNodeParsePipeline rawNodeParsePipeline,
      TargetNodeParsePipeline targetNodeParsePipeline) {
    this.knownBuildRuleTypesProvider = knownBuildRuleTypesProvider;
    this.cellManager = cellManager;
    this.rawNodeParsePipeline = rawNodeParsePipeline;
    this.targetNodeParsePipeline = targetNodeParsePipeline;
  }

  TargetNode<?, ?> getTargetNode(BuildTarget target) throws BuildFileParseException {
    Cell owningCell = cellManager.getCell(target);

    return targetNodeParsePipeline.getNode(
        owningCell, knownBuildRuleTypesProvider.get(owningCell), target, parseProcessedBytes);
  }

  ListenableFuture<TargetNode<?, ?>> getTargetNodeJob(BuildTarget target)
      throws BuildTargetException {
    Cell owningCell = cellManager.getCell(target);

    return targetNodeParsePipeline.getNodeJob(
        owningCell, knownBuildRuleTypesProvider.get(owningCell), target, parseProcessedBytes);
  }

  ImmutableSet<TargetNode<?, ?>> getAllTargetNodes(Cell cell, Path buildFile)
      throws BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    return targetNodeParsePipeline.getAllNodes(
        cell, knownBuildRuleTypesProvider.get(cell), buildFile, parseProcessedBytes);
  }

  ListenableFuture<ImmutableSet<TargetNode<?, ?>>> getAllTargetNodesJob(Cell cell, Path buildFile)
      throws BuildTargetException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    return targetNodeParsePipeline.getAllNodesJob(
        cell, knownBuildRuleTypesProvider.get(cell), buildFile, parseProcessedBytes);
  }

  ImmutableSet<Map<String, Object>> getAllRawNodes(Cell cell, Path buildFile)
      throws BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    // The raw nodes are just plain JSON blobs, and so we don't need to check for symlinks
    return rawNodeParsePipeline.getAllNodes(
        cell, knownBuildRuleTypesProvider.get(cell), buildFile, parseProcessedBytes);
  }

  long getParseProcessedBytes() {
    return parseProcessedBytes.get();
  }

  @Override
  public void close() {
    targetNodeParsePipeline.close();
    rawNodeParsePipeline.close();
    cellManager.close();
  }
}
