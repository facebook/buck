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
import com.facebook.buck.core.model.impl.MultiPlatformTargetConfigurationTransformer;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.resources.ResourcesConfig;
import com.facebook.buck.core.rules.config.impl.ConfigurationRuleSelectableResolver;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.config.registry.impl.ConfigurationRuleRegistryFactory;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.select.SelectableResolver;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.core.select.impl.DefaultSelectorListResolver;
import com.facebook.buck.core.select.impl.SelectorFactory;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.coercer.UnconfiguredBuildTargetTypeCoercer;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.facebook.buck.util.concurrent.CommandThreadFactory;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Version of {@link PerBuildStateFactory} that supports configurable attributes. */
class PerBuildStateFactoryWithConfigurableAttributes extends PerBuildStateFactory {

  private final TypeCoercerFactory typeCoercerFactory;
  private final ConstructorArgMarshaller marshaller;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final ParserPythonInterpreterProvider parserPythonInterpreterProvider;
  private final Watchman watchman;
  private final BuckEventBus eventBus;
  private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory;

  PerBuildStateFactoryWithConfigurableAttributes(
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller,
      KnownRuleTypesProvider knownRuleTypesProvider,
      ParserPythonInterpreterProvider parserPythonInterpreterProvider,
      Watchman watchman,
      BuckEventBus eventBus,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier,
      FileHashLoader fileHashLoader,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory) {
    super(manifestServiceSupplier, fileHashLoader);
    this.typeCoercerFactory = typeCoercerFactory;
    this.marshaller = marshaller;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.parserPythonInterpreterProvider = parserPythonInterpreterProvider;
    this.watchman = watchman;
    this.eventBus = eventBus;
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
  }

  @Override
  protected PerBuildState create(
      ParsingContext parsingContext,
      DaemonicParserState daemonicParserState,
      Optional<AtomicLong> parseProcessedBytes) {

    Cell rootCell = parsingContext.getCell();
    ListeningExecutorService executorService = parsingContext.getExecutor();
    SymlinkCache symlinkCache = new SymlinkCache(eventBus, daemonicParserState);
    CellManager cellManager = new CellManager(symlinkCache);

    TargetNodeListener<TargetNode<?>> symlinkCheckers = cellManager::registerInputsUnderSymlinks;
    ParserConfig parserConfig = rootCell.getBuckConfig().getView(ParserConfig.class);
    int numParsingThreads = parserConfig.getNumParsingThreads();
    DefaultProjectBuildFileParserFactory projectBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            typeCoercerFactory,
            parserPythonInterpreterProvider,
            parsingContext.isProfilingEnabled(),
            parseProcessedBytes,
            knownRuleTypesProvider,
            manifestServiceSupplier,
            fileHashLoader);
    ProjectBuildFileParserPool projectBuildFileParserPool =
        new ProjectBuildFileParserPool(
            numParsingThreads, // Max parsers to create per cell.
            projectBuildFileParserFactory,
            parsingContext.isProfilingEnabled());

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
        parserConfig.getEnableParallelParsing()
            && parsingContext.getSpeculativeParsing() == SpeculativeParsing.ENABLED;
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
                typeCoercerFactory,
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
            daemonicParserState.getOrCreateNodeCacheForConfigurationTargets(TargetNode.class),
            MoreExecutors.newDirectExecutorService(),
            rawTargetNodePipeline,
            eventBus,
            "nonresolving_raw_target_node_parse_pipeline",
            enableSpeculativeParsing,
            nonResolvingRawTargetNodeToTargetNodeFactory);

    ConfigurationRuleRegistry configurationRuleRegistry =
        ConfigurationRuleRegistryFactory.createRegistry(
            target ->
                nonResolvingTargetNodeParsePipeline.getNode(cellManager.getCell(target), target));

    SelectableResolver selectableResolver =
        new ConfigurationRuleSelectableResolver(
            configurationRuleRegistry.getConfigurationRuleResolver());

    SelectorListResolver selectorListResolver = new DefaultSelectorListResolver(selectableResolver);

    RawTargetNodeToTargetNodeFactory rawTargetNodeToTargetNodeFactory =
        new RawTargetNodeToTargetNodeFactory(
            typeCoercerFactory,
            knownRuleTypesProvider,
            marshaller,
            targetNodeFactory,
            packageBoundaryChecker,
            symlinkCheckers,
            selectorListResolver,
            configurationRuleRegistry.getConstraintResolver(),
            configurationRuleRegistry.getTargetPlatformResolver(),
            new MultiPlatformTargetConfigurationTransformer(
                configurationRuleRegistry.getTargetPlatformResolver()));

    ListeningExecutorService configuredPipelineExecutor =
        MoreExecutors.listeningDecorator(
            createExecutorService(rootCell.getBuckConfig(), "configured-pipeline"));

    ParsePipeline<TargetNode<?>> targetNodeParsePipeline =
        new RawTargetNodeToTargetNodeParsePipeline(
            daemonicParserState.getOrCreateNodeCache(TargetNode.class),
            configuredPipelineExecutor,
            rawTargetNodePipeline,
            eventBus,
            "configured_raw_target_node_parse_pipeline",
            enableSpeculativeParsing,
            rawTargetNodeToTargetNodeFactory) {
          @Override
          public void close() {
            super.close();
            nonResolvingTargetNodeParsePipeline.close();
            rawTargetNodePipeline.close();
            try {
              MostExecutors.shutdown(configuredPipelineExecutor, 1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
            }
          }
        };

    SelectorListFactory selectorListFactory =
        new SelectorListFactory(
            new SelectorFactory(
                new UnconfiguredBuildTargetTypeCoercer(unconfiguredBuildTargetFactory)));

    cellManager.register(rootCell);

    return new PerBuildState(
        cellManager,
        buildFileRawNodeParsePipeline,
        targetNodeParsePipeline,
        parsingContext,
        selectorListResolver,
        selectorListFactory,
        configurationRuleRegistry);
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
}
