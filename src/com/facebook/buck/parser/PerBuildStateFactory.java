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
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.visibility.VisibilityPatternFactory;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

public class PerBuildStateFactory {
  public PerBuildState create(
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

    SymlinkCache symlinkCache = new SymlinkCache(eventBus, daemonicParserState);
    CellManager cellManager = new CellManager(symlinkCache);

    TargetNodeListener<TargetNode<?, ?>> symlinkCheckers = cellManager::registerInputsUnderSymlinks;
    ParserConfig parserConfig = rootCell.getBuckConfig().getView(ParserConfig.class);
    int numParsingThreads = parserConfig.getNumParsingThreads();
    DefaultProjectBuildFileParserFactory projectBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            typeCoercerFactory,
            parserPythonInterpreterProvider,
            knownBuildRuleTypesProvider,
            enableProfiling);
    ProjectBuildFileParserPool projectBuildFileParserPool =
        new ProjectBuildFileParserPool(
            numParsingThreads, // Max parsers to create per cell.
            projectBuildFileParserFactory,
            enableProfiling);

    RawNodeParsePipeline rawNodeParsePipeline =
        new RawNodeParsePipeline(
            daemonicParserState.getRawNodeCache(),
            projectBuildFileParserPool,
            executorService,
            eventBus);
    TargetNodeParsePipeline targetNodeParsePipeline =
        new TargetNodeParsePipeline(
            daemonicParserState.getOrCreateNodeCache(TargetNode.class),
            DefaultParserTargetNodeFactory.createForParser(
                marshaller,
                daemonicParserState.getBuildFileTrees(),
                symlinkCheckers,
                new TargetNodeFactory(typeCoercerFactory),
                new VisibilityPatternFactory(),
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

    return new PerBuildState(
        knownBuildRuleTypesProvider, cellManager, rawNodeParsePipeline, targetNodeParsePipeline);
  }
}
