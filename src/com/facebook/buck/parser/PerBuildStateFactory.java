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
import com.facebook.buck.core.model.targetgraph.RawTargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.rules.config.KnownConfigurationRuleTypes;
import com.facebook.buck.core.rules.config.impl.ConfigurationRuleSelectableResolver;
import com.facebook.buck.core.rules.config.impl.SameThreadConfigurationRuleResolver;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesProvider;
import com.facebook.buck.core.select.SelectableResolver;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.core.select.impl.DefaultSelectorListResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.visibility.VisibilityPatternFactory;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.atomic.AtomicLong;

public class PerBuildStateFactory {

  private final TypeCoercerFactory typeCoercerFactory;
  private final ConstructorArgMarshaller marshaller;
  private final KnownBuildRuleTypesProvider knownBuildRuleTypesProvider;
  private final KnownConfigurationRuleTypes knownConfigurationRuleTypes;
  private final ParserPythonInterpreterProvider parserPythonInterpreterProvider;

  public PerBuildStateFactory(
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      KnownConfigurationRuleTypes knownConfigurationRuleTypes,
      ParserPythonInterpreterProvider parserPythonInterpreterProvider) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.marshaller = marshaller;
    this.knownBuildRuleTypesProvider = knownBuildRuleTypesProvider;
    this.knownConfigurationRuleTypes = knownConfigurationRuleTypes;
    this.parserPythonInterpreterProvider = parserPythonInterpreterProvider;
  }

  public PerBuildState create(
      DaemonicParserState daemonicParserState,
      BuckEventBus eventBus,
      ListeningExecutorService executorService,
      Cell rootCell,
      boolean enableProfiling,
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

    AtomicLong parseProcessedBytes = new AtomicLong();

    ParsePipeline<TargetNode<?>> targetNodeParsePipeline;

    if (parserConfig.getEnableConfigurableAttributes()) {
      ListeningExecutorService pipelineExecutorService =
          parserConfig.getEnableParallelParsing()
              ? executorService
              : MoreExecutors.newDirectExecutorService();
      boolean enableSpeculativeParsing =
          parserConfig.getEnableParallelParsing()
              && speculativeParsing == SpeculativeParsing.ENABLED;
      TargetNodeFactory targetNodeFactory = new TargetNodeFactory(typeCoercerFactory);
      RawTargetNodePipeline rawTargetNodePipeline =
          new RawTargetNodePipeline(
              pipelineExecutorService,
              daemonicParserState.getOrCreateNodeCache(RawTargetNode.class),
              rawNodeParsePipeline,
              eventBus,
              new DefaultRawTargetNodeFactory(
                  knownBuildRuleTypesProvider,
                  marshaller,
                  new VisibilityPatternFactory(),
                  new BuiltTargetVerifier()));

      ConfigurationRuleResolver configurationRuleResolver =
          new SameThreadConfigurationRuleResolver(
              cellManager::getCell,
              (cell, buildTarget) ->
                  rawTargetNodePipeline.getNode(cell, buildTarget, parseProcessedBytes),
              knownConfigurationRuleTypes);

      SelectableResolver selectableResolver =
          new ConfigurationRuleSelectableResolver(configurationRuleResolver);

      SelectorListResolver selectorListResolver =
          new DefaultSelectorListResolver(selectableResolver);

      PackageBoundaryChecker packageBoundaryChecker =
          new ThrowingPackageBoundaryChecker(daemonicParserState.getBuildFileTrees());

      RawTargetNodeToTargetNodeFactory rawTargetNodeToTargetNodeFactory =
          new RawTargetNodeToTargetNodeFactory(
              knownBuildRuleTypesProvider,
              marshaller,
              targetNodeFactory,
              packageBoundaryChecker,
              symlinkCheckers,
              selectorListResolver);

      targetNodeParsePipeline =
          new RawTargetNodeToTargetNodeParsePipeline(
              daemonicParserState.getOrCreateNodeCache(TargetNode.class),
              pipelineExecutorService,
              rawTargetNodePipeline,
              eventBus,
              enableSpeculativeParsing,
              rawTargetNodeToTargetNodeFactory);
    } else {
      targetNodeParsePipeline =
          new TargetNodeParsePipeline(
              daemonicParserState.getOrCreateNodeCache(TargetNode.class),
              DefaultParserTargetNodeFactory.createForParser(
                  knownBuildRuleTypesProvider,
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
              rawNodeParsePipeline);
    }

    cellManager.register(rootCell);

    return new PerBuildState(
        parseProcessedBytes, cellManager, rawNodeParsePipeline, targetNodeParsePipeline);
  }
}
