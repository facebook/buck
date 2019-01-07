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
package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This factory creates {@link PerBuildStateFactory} that uses simple pipeline that doesn't support
 * configurable attributes.
 */
class LegacyPerBuildStateFactory extends PerBuildStateFactory {
  private final TypeCoercerFactory typeCoercerFactory;
  private final ConstructorArgMarshaller marshaller;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final ParserPythonInterpreterProvider parserPythonInterpreterProvider;
  private final Watchman watchman;
  private final BuckEventBus eventBus;

  LegacyPerBuildStateFactory(
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller,
      KnownRuleTypesProvider knownRuleTypesProvider,
      ParserPythonInterpreterProvider parserPythonInterpreterProvider,
      Watchman watchman,
      BuckEventBus eventBus,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier,
      FileHashCache fileHashCache) {
    super(manifestServiceSupplier, fileHashCache);
    this.typeCoercerFactory = typeCoercerFactory;
    this.marshaller = marshaller;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.parserPythonInterpreterProvider = parserPythonInterpreterProvider;
    this.watchman = watchman;
    this.eventBus = eventBus;
  }

  @Override
  protected PerBuildState create(
      DaemonicParserState daemonicParserState,
      ListeningExecutorService executorService,
      Cell rootCell,
      ImmutableList<String> targetPlatforms,
      boolean enableProfiling,
      Optional<AtomicLong> parseProcessedBytes,
      SpeculativeParsing speculativeParsing) {

    SymlinkCache symlinkCache = new SymlinkCache(eventBus, daemonicParserState);
    CellManager cellManager = new CellManager(symlinkCache);

    TargetNodeListener<TargetNode<?>> symlinkCheckers = cellManager::registerInputsUnderSymlinks;
    ParserConfig parserConfig = rootCell.getBuckConfig().getView(ParserConfig.class);
    int numParsingThreads = parserConfig.getNumParsingThreads();
    DefaultProjectBuildFileParserFactory projectBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            typeCoercerFactory,
            parserPythonInterpreterProvider,
            enableProfiling,
            parseProcessedBytes,
            knownRuleTypesProvider,
            manifestServiceSupplier,
            fileHashCache);
    ProjectBuildFileParserPool projectBuildFileParserPool =
        new ProjectBuildFileParserPool(
            numParsingThreads, // Max parsers to create per cell.
            projectBuildFileParserFactory,
            enableProfiling);

    TargetNodeFactory targetNodeFactory = new TargetNodeFactory(typeCoercerFactory);

    BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline =
        new BuildFileRawNodeParsePipeline(
            new PipelineNodeCache<>(daemonicParserState.getRawNodeCache()),
            projectBuildFileParserPool,
            executorService,
            eventBus,
            watchman);

    BuildTargetRawNodeParsePipeline buildTargetRawNodeParsePipeline =
        new BuildTargetRawNodeParsePipeline(executorService, buildFileRawNodeParsePipeline);

    ParsePipeline<TargetNode<?>> targetNodeParsePipeline =
        new TargetNodeParsePipeline(
            daemonicParserState.getOrCreateNodeCache(TargetNode.class),
            DefaultParserTargetNodeFactory.createForParser(
                knownRuleTypesProvider,
                marshaller,
                daemonicParserState.getBuildFileTrees(),
                symlinkCheckers,
                targetNodeFactory,
                rootCell.getRuleKeyConfiguration()),
            parserConfig.getEnableParallelParsing()
                ? executorService
                : MoreExecutors.newDirectExecutorService(),
            eventBus,
            parserConfig.getEnableParallelParsing()
                && speculativeParsing == SpeculativeParsing.ENABLED,
            buildFileRawNodeParsePipeline,
            buildTargetRawNodeParsePipeline);

    cellManager.register(rootCell);

    return new PerBuildState(cellManager, buildFileRawNodeParsePipeline, targetNodeParsePipeline);
  }
}
