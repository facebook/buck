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
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class PerBuildState implements AutoCloseable {

  private final AtomicLong parseProcessedBytes = new AtomicLong();

  private final CellManager cellManager;

  private final SymlinkCache symlinkCache;
  private final ProjectBuildFileParserPool projectBuildFileParserPool;
  private final RawNodeParsePipeline rawNodeParsePipeline;
  private final TargetNodeParsePipeline targetNodeParsePipeline;
  private final KnownBuildRuleTypesProvider knownBuildRuleTypesProvider;

  public enum SpeculativeParsing {
    ENABLED,
    DISABLED,
  }

  public PerBuildState(
      TypeCoercerFactory typeCoercerFactory,
      DaemonicParserState daemonicParserState,
      ConstructorArgMarshaller marshaller,
      BuckEventBus eventBus,
      ParserPythonInterpreterProvider parserPythonInterpreterProvider,
      ListeningExecutorService executorService,
      Cell rootCell,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      boolean enableProfiling,
      SpeculativeParsing speculativeParsing) {

    this.knownBuildRuleTypesProvider = knownBuildRuleTypesProvider;
    this.symlinkCache = new SymlinkCache(eventBus, daemonicParserState);
    this.cellManager = new CellManager(symlinkCache);

    TargetNodeListener<TargetNode<?, ?>> symlinkCheckers = cellManager::registerInputsUnderSymlinks;
    ParserConfig parserConfig = rootCell.getBuckConfig().getView(ParserConfig.class);
    int numParsingThreads = parserConfig.getNumParsingThreads();
    DefaultProjectBuildFileParserFactory projectBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            typeCoercerFactory,
            parserPythonInterpreterProvider,
            knownBuildRuleTypesProvider,
            enableProfiling);
    this.projectBuildFileParserPool =
        new ProjectBuildFileParserPool(
            numParsingThreads, // Max parsers to create per cell.
            projectBuildFileParserFactory,
            enableProfiling);

    this.rawNodeParsePipeline =
        new RawNodeParsePipeline(
            daemonicParserState.getRawNodeCache(),
            projectBuildFileParserPool,
            executorService,
            eventBus);
    this.targetNodeParsePipeline =
        new TargetNodeParsePipeline(
            daemonicParserState.getOrCreateNodeCache(TargetNode.class),
            DefaultParserTargetNodeFactory.createForParser(
                marshaller,
                daemonicParserState.getBuildFileTrees(),
                symlinkCheckers,
                new TargetNodeFactory(typeCoercerFactory),
                rootCell.getRuleKeyConfiguration()),
            parserConfig.getEnableParallelParsing()
                ? executorService
                : MoreExecutors.newDirectExecutorService(),
            eventBus,
            parserConfig.getEnableParallelParsing()
                && speculativeParsing == SpeculativeParsing.ENABLED,
            rawNodeParsePipeline,
            knownBuildRuleTypesProvider);

    cellManager.register(rootCell);
  }

  public TargetNode<?, ?> getTargetNode(BuildTarget target) throws BuildFileParseException {
    Cell owningCell = cellManager.getCell(target);

    return targetNodeParsePipeline.getNode(
        owningCell, knownBuildRuleTypesProvider.get(owningCell), target, parseProcessedBytes);
  }

  public ListenableFuture<TargetNode<?, ?>> getTargetNodeJob(BuildTarget target)
      throws BuildTargetException {
    Cell owningCell = cellManager.getCell(target);

    return targetNodeParsePipeline.getNodeJob(
        owningCell, knownBuildRuleTypesProvider.get(owningCell), target, parseProcessedBytes);
  }

  public ImmutableSet<TargetNode<?, ?>> getAllTargetNodes(Cell cell, Path buildFile)
      throws BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    return targetNodeParsePipeline.getAllNodes(
        cell, knownBuildRuleTypesProvider.get(cell), buildFile, parseProcessedBytes);
  }

  public ListenableFuture<ImmutableSet<TargetNode<?, ?>>> getAllTargetNodesJob(
      Cell cell, Path buildFile) throws BuildTargetException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    return targetNodeParsePipeline.getAllNodesJob(
        cell, knownBuildRuleTypesProvider.get(cell), buildFile, parseProcessedBytes);
  }

  public ImmutableSet<Map<String, Object>> getAllRawNodes(Cell cell, Path buildFile)
      throws BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    // The raw nodes are just plain JSON blobs, and so we don't need to check for symlinks
    return rawNodeParsePipeline.getAllNodes(
        cell, knownBuildRuleTypesProvider.get(cell), buildFile, parseProcessedBytes);
  }

  public long getParseProcessedBytes() {
    return parseProcessedBytes.get();
  }

  @Override
  public void close() throws BuildFileParseException {
    targetNodeParsePipeline.close();
    rawNodeParsePipeline.close();
    projectBuildFileParserPool.close();
    symlinkCache.close();
  }
}
