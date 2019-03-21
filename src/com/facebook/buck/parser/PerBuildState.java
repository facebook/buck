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
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.file.Path;

public class PerBuildState implements AutoCloseable {

  private final CellManager cellManager;
  private final BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline;
  private final ParsePipeline<TargetNode<?>> targetNodeParsePipeline;
  private final ParsingContext parsingContext;

  PerBuildState(
      CellManager cellManager,
      BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline,
      ParsePipeline<TargetNode<?>> targetNodeParsePipeline,
      ParsingContext parsingContext) {
    this.cellManager = cellManager;
    this.buildFileRawNodeParsePipeline = buildFileRawNodeParsePipeline;
    this.targetNodeParsePipeline = targetNodeParsePipeline;
    this.parsingContext = parsingContext;
  }

  TargetNode<?> getTargetNode(BuildTarget target) throws BuildFileParseException {
    Cell owningCell = cellManager.getCell(target);

    return targetNodeParsePipeline.getNode(owningCell, target);
  }

  ListenableFuture<TargetNode<?>> getTargetNodeJob(BuildTarget target) throws BuildTargetException {
    Cell owningCell = cellManager.getCell(target);

    return targetNodeParsePipeline.getNodeJob(owningCell, target);
  }

  ImmutableList<TargetNode<?>> getAllTargetNodes(
      Cell cell, Path buildFile, TargetConfiguration targetConfiguration)
      throws BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    return targetNodeParsePipeline.getAllNodes(cell, buildFile, targetConfiguration);
  }

  ListenableFuture<ImmutableList<TargetNode<?>>> getAllTargetNodesJob(
      Cell cell, Path buildFile, TargetConfiguration targetConfiguration)
      throws BuildTargetException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    return targetNodeParsePipeline.getAllNodesJob(cell, buildFile, targetConfiguration);
  }

  BuildFileManifest getBuildFileManifest(Cell cell, Path buildFile) throws BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));
    return buildFileRawNodeParsePipeline.getAllNodes(cell, buildFile);
  }

  ListenableFuture<BuildFileManifest> getBuildFileManifestJob(Cell cell, Path buildFile)
      throws BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));
    return buildFileRawNodeParsePipeline.getAllNodesJob(cell, buildFile);
  }

  ParsingContext getParsingContext() {
    return parsingContext;
  }

  @Override
  public void close() {
    targetNodeParsePipeline.close();
    buildFileRawNodeParsePipeline.close();
    cellManager.close();
  }
}
