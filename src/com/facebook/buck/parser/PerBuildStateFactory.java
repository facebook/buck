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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.platform.ConstraintBasedPlatform;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.targetgraph.RawTargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.rules.config.impl.ConfigurationRuleSelectableResolver;
import com.facebook.buck.core.rules.config.impl.SameThreadConfigurationRuleResolver;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.platform.PlatformRule;
import com.facebook.buck.core.rules.platform.RuleBasedConstraintResolver;
import com.facebook.buck.core.select.SelectableResolver;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.core.select.impl.DefaultSelectorListResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.visibility.VisibilityPatternFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class PerBuildStateFactory {

  private final TypeCoercerFactory typeCoercerFactory;
  private final ConstructorArgMarshaller marshaller;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final ParserPythonInterpreterProvider parserPythonInterpreterProvider;
  private final Watchman watchman;
  private final BuckEventBus eventBus;

  public PerBuildStateFactory(
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller,
      KnownRuleTypesProvider knownRuleTypesProvider,
      ParserPythonInterpreterProvider parserPythonInterpreterProvider,
      Watchman watchman,
      BuckEventBus eventBus) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.marshaller = marshaller;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.parserPythonInterpreterProvider = parserPythonInterpreterProvider;
    this.watchman = watchman;
    this.eventBus = eventBus;
  }

  public PerBuildState create(
      DaemonicParserState daemonicParserState,
      ListeningExecutorService executorService,
      Cell rootCell,
      ImmutableList<String> targetPlatforms,
      boolean enableProfiling,
      SpeculativeParsing speculativeParsing) {
    return create(
        daemonicParserState,
        executorService,
        rootCell,
        targetPlatforms,
        enableProfiling,
        Optional.empty(),
        speculativeParsing);
  }

  public PerBuildState create(
      DaemonicParserState daemonicParserState,
      ListeningExecutorService executorService,
      Cell rootCell,
      ImmutableList<String> targetPlatforms,
      boolean enableProfiling,
      AtomicLong processedBytes,
      SpeculativeParsing speculativeParsing) {
    return create(
        daemonicParserState,
        executorService,
        rootCell,
        targetPlatforms,
        enableProfiling,
        Optional.of(processedBytes),
        speculativeParsing);
  }

  private PerBuildState create(
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
            knownRuleTypesProvider);
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
            eventBus,
            watchman);

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
                  knownRuleTypesProvider,
                  marshaller,
                  new VisibilityPatternFactory(),
                  new BuiltTargetVerifier()));

      PackageBoundaryChecker packageBoundaryChecker =
          new ThrowingPackageBoundaryChecker(daemonicParserState.getBuildFileTrees());

      ParserTargetNodeFactory<RawTargetNode> nonResolvingrawTargetNodeToTargetNodeFactory =
          new NonResolvingRawTargetNodeToTargetNodeFactory(
              knownRuleTypesProvider,
              marshaller,
              targetNodeFactory,
              packageBoundaryChecker,
              symlinkCheckers);

      ParsePipeline<TargetNode<?>> nonResolvingTargetNodeParsePipeline =
          new RawTargetNodeToTargetNodeParsePipeline(
              daemonicParserState.getOrCreateNodeCache(TargetNode.class),
              pipelineExecutorService,
              rawTargetNodePipeline,
              eventBus,
              enableSpeculativeParsing,
              nonResolvingrawTargetNodeToTargetNodeFactory);

      ConfigurationRuleResolver configurationRuleResolver =
          new SameThreadConfigurationRuleResolver(
              cellManager::getCell,
              (cell, buildTarget) ->
                  nonResolvingTargetNodeParsePipeline.getNode(cell, buildTarget));

      SelectableResolver selectableResolver =
          new ConfigurationRuleSelectableResolver(configurationRuleResolver);

      SelectorListResolver selectorListResolver =
          new DefaultSelectorListResolver(selectableResolver);

      ConstraintResolver constraintResolver =
          new RuleBasedConstraintResolver(configurationRuleResolver);

      RawTargetNodeToTargetNodeFactory rawTargetNodeToTargetNodeFactory =
          new RawTargetNodeToTargetNodeFactory(
              knownRuleTypesProvider,
              marshaller,
              targetNodeFactory,
              packageBoundaryChecker,
              symlinkCheckers,
              selectorListResolver,
              constraintResolver,
              () ->
                  getTargetPlatform(
                      configurationRuleResolver, constraintResolver, rootCell, targetPlatforms));

      targetNodeParsePipeline =
          new RawTargetNodeToTargetNodeParsePipeline(
              daemonicParserState.getOrCreateNodeCache(TargetNode.class),
              pipelineExecutorService,
              rawTargetNodePipeline,
              eventBus,
              enableSpeculativeParsing,
              rawTargetNodeToTargetNodeFactory) {
            @Override
            public void close() {
              super.close();
              nonResolvingTargetNodeParsePipeline.close();
            }
          };
    } else {
      targetNodeParsePipeline =
          new TargetNodeParsePipeline(
              daemonicParserState.getOrCreateNodeCache(TargetNode.class),
              DefaultParserTargetNodeFactory.createForParser(
                  knownRuleTypesProvider,
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

    return new PerBuildState(cellManager, rawNodeParsePipeline, targetNodeParsePipeline);
  }

  private Platform getTargetPlatform(
      ConfigurationRuleResolver configurationRuleResolver,
      ConstraintResolver constraintResolver,
      Cell rootCell,
      ImmutableList<String> targetPlatforms) {
    if (targetPlatforms.isEmpty()) {
      return new ConstraintBasedPlatform(ImmutableSet.of());
    }

    String targetPlatformName = targetPlatforms.get(0);
    ConfigurationRule configurationRule =
        configurationRuleResolver.getRule(
            BuildTargetParser.INSTANCE.parse(
                targetPlatformName,
                BuildTargetPatternParser.fullyQualified(),
                rootCell.getCellPathResolver()));

    if (!(configurationRule instanceof PlatformRule)) {
      throw new HumanReadableException(
          "%s is used as a target platform, but not declared using `platform` rule",
          targetPlatformName);
    }

    PlatformRule platformRule = (PlatformRule) configurationRule;

    ImmutableSet<ConstraintValue> constraintValues =
        platformRule
            .getConstrainValues()
            .stream()
            .map(constraintResolver::getConstraintValue)
            .collect(ImmutableSet.toImmutableSet());

    return new ConstraintBasedPlatform(constraintValues);
  }
}
