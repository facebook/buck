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
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.platform.ConstraintBasedPlatform;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.targetgraph.RawTargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.core.resources.ResourcesConfig;
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
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.concurrent.CommandThreadFactory;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Version of {@link PerBuildStateFactory} that supports configurable attributes. */
class PerBuildStateFactoryWithConfigurableAttributes extends PerBuildStateFactory {

  private final TypeCoercerFactory typeCoercerFactory;
  private final ConstructorArgMarshaller marshaller;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final ParserPythonInterpreterProvider parserPythonInterpreterProvider;
  private final Watchman watchman;
  private final BuckEventBus eventBus;
  private final UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory;

  PerBuildStateFactoryWithConfigurableAttributes(
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller,
      KnownRuleTypesProvider knownRuleTypesProvider,
      ParserPythonInterpreterProvider parserPythonInterpreterProvider,
      Watchman watchman,
      BuckEventBus eventBus,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier,
      FileHashCache fileHashCache,
      UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory) {
    super(manifestServiceSupplier, fileHashCache);
    this.typeCoercerFactory = typeCoercerFactory;
    this.marshaller = marshaller;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.parserPythonInterpreterProvider = parserPythonInterpreterProvider;
    this.watchman = watchman;
    this.eventBus = eventBus;
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
  }

  @Override
  protected PerBuildStateWithConfigurableAttributes create(
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

    ListeningExecutorService pipelineExecutorService =
        parserConfig.getEnableParallelParsing()
            ? executorService
            : MoreExecutors.newDirectExecutorService();
    boolean enableSpeculativeParsing =
        parserConfig.getEnableParallelParsing() && speculativeParsing == SpeculativeParsing.ENABLED;
    RawTargetNodePipeline rawTargetNodePipeline =
        new RawTargetNodePipeline(
            pipelineExecutorService,
            daemonicParserState.getOrCreateNodeCache(RawTargetNode.class),
            eventBus,
            buildFileRawNodeParsePipeline,
            buildTargetRawNodeParsePipeline,
            new DefaultRawTargetNodeFactory(knownRuleTypesProvider, new BuiltTargetVerifier()));

    PackageBoundaryChecker packageBoundaryChecker =
        new ThrowingPackageBoundaryChecker(daemonicParserState.getBuildFileTrees());

    ParserTargetNodeFactory<RawTargetNode> nonResolvingRawTargetNodeToTargetNodeFactory =
        new NonResolvingRawTargetNodeToTargetNodeFactory(
            DefaultParserTargetNodeFactory.createForParser(
                knownRuleTypesProvider,
                marshaller,
                daemonicParserState.getBuildFileTrees(),
                symlinkCheckers,
                targetNodeFactory));

    // This pipeline uses a direct executor instead of pipelineExecutorService to avoid
    // deadlocks happening when too many node are requested from targetNodeParsePipeline.
    // That pipeline does blocking calls to get nodes from nonResolvingTargetNodeParsePipeline
    // which can lead to deadlocks.
    ParsePipeline<TargetNode<?>> nonResolvingTargetNodeParsePipeline =
        new RawTargetNodeToTargetNodeParsePipeline(
            daemonicParserState.getOrCreateNodeCache(TargetNode.class),
            MoreExecutors.newDirectExecutorService(),
            rawTargetNodePipeline,
            eventBus,
            enableSpeculativeParsing,
            nonResolvingRawTargetNodeToTargetNodeFactory);

    ConfigurationRuleResolver configurationRuleResolver =
        new SameThreadConfigurationRuleResolver(
            cellManager::getCell, nonResolvingTargetNodeParsePipeline::getNode);

    SelectableResolver selectableResolver =
        new ConfigurationRuleSelectableResolver(configurationRuleResolver);

    SelectorListResolver selectorListResolver = new DefaultSelectorListResolver(selectableResolver);

    ConstraintResolver constraintResolver =
        new RuleBasedConstraintResolver(configurationRuleResolver);

    Supplier<Platform> targetPlatform =
        Suppliers.memoize(
            () ->
                getTargetPlatform(
                    configurationRuleResolver, constraintResolver, rootCell, targetPlatforms));

    RawTargetNodeToTargetNodeFactory rawTargetNodeToTargetNodeFactory =
        new RawTargetNodeToTargetNodeFactory(
            knownRuleTypesProvider,
            marshaller,
            targetNodeFactory,
            packageBoundaryChecker,
            symlinkCheckers,
            selectorListResolver,
            constraintResolver,
            targetPlatform);

    ListeningExecutorService configuredPipeline =
        MoreExecutors.listeningDecorator(
            createExecutorService(rootCell.getBuckConfig(), "configured-pipeline"));

    ParsePipeline<TargetNode<?>> targetNodeParsePipeline =
        new RawTargetNodeToTargetNodeParsePipeline(
            daemonicParserState.getOrCreateNodeCache(TargetNode.class),
            configuredPipeline,
            rawTargetNodePipeline,
            eventBus,
            enableSpeculativeParsing,
            rawTargetNodeToTargetNodeFactory) {
          @Override
          public void close() {
            super.close();
            nonResolvingTargetNodeParsePipeline.close();
            try {
              MostExecutors.shutdown(configuredPipeline, 1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
            }
          }
        };

    cellManager.register(rootCell);

    return new PerBuildStateWithConfigurableAttributes(
        cellManager,
        buildFileRawNodeParsePipeline,
        targetNodeParsePipeline,
        constraintResolver,
        selectorListResolver,
        targetPlatform);
  }

  @SuppressWarnings("PMD.AvoidThreadGroup")
  private static ExecutorService createExecutorService(BuckConfig buckConfig, String name) {
    ConcurrencyLimit concurrencyLimit =
        buckConfig.getView(ResourcesConfig.class).getConcurrencyLimit();

    return MostExecutors.newMultiThreadExecutor(
        new ThreadFactoryBuilder()
            .setNameFormat(name + "-%d")
            .setThreadFactory(
                new CommandThreadFactory(
                    r -> new Thread(new ThreadGroup(name), r),
                    GlobalStateManager.singleton().getThreadToCommandRegister()))
            .build(),
        concurrencyLimit.managedThreadCount);
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
            unconfiguredBuildTargetFactory
                .create(rootCell.getCellPathResolver(), targetPlatformName)
                .configure(EmptyTargetConfiguration.INSTANCE));

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
