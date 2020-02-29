/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.DefaultCellNameResolverProvider;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.MultiPlatformTargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.impl.ThrowingPlatformResolver;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
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
import com.facebook.buck.core.select.impl.ThrowingSelectorListResolver;
import com.facebook.buck.core.select.impl.UnconfiguredSelectorListResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.detector.TargetConfigurationDetector;
import com.facebook.buck.parser.detector.TargetConfigurationDetectorFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.concurrent.CommandThreadFactory;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Can be used to create {@link PerBuildState}. */
public class PerBuildStateFactory {

  private final TypeCoercerFactory typeCoercerFactory;
  private final ConstructorArgMarshaller marshaller;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final ParserPythonInterpreterProvider parserPythonInterpreterProvider;
  private final Watchman watchman;
  private final BuckEventBus eventBus;
  private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory;
  private final TargetConfiguration hostConfiguration;

  public PerBuildStateFactory(
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller,
      KnownRuleTypesProvider knownRuleTypesProvider,
      ParserPythonInterpreterProvider parserPythonInterpreterProvider,
      Watchman watchman,
      BuckEventBus eventBus,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      TargetConfiguration hostConfiguration) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.marshaller = marshaller;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.parserPythonInterpreterProvider = parserPythonInterpreterProvider;
    this.watchman = watchman;
    this.eventBus = eventBus;
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
    this.hostConfiguration = hostConfiguration;
  }

  private PerBuildState create(
      ParsingContext parsingContext,
      DaemonicParserState daemonicParserState,
      Optional<AtomicLong> parseProcessedBytes) {

    Cells cells = new Cells(parsingContext.getCell().getCell(CanonicalCellName.rootCell()));
    ListeningExecutorService executorService = parsingContext.getExecutor();
    SymlinkCache symlinkCache = new SymlinkCache(eventBus, daemonicParserState);
    CellManager cellManager = new CellManager(cells.getRootCell(), symlinkCache);

    TargetNodeListener<TargetNode<?>> symlinkCheckers = cellManager::registerInputsUnderSymlinks;
    ParserConfig parserConfig = cells.getRootCell().getBuckConfig().getView(ParserConfig.class);

    TargetConfigurationDetector targetConfigurationDetector =
        TargetConfigurationDetectorFactory.fromBuckConfig(
            parserConfig,
            unconfiguredBuildTargetFactory,
            cells.getRootCell().getCellPathResolver(),
            cells.getRootCell().getCellNameResolver());

    int numParsingThreads = parserConfig.getNumParsingThreads();
    DefaultProjectBuildFileParserFactory projectBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            typeCoercerFactory,
            parserPythonInterpreterProvider,
            parsingContext.isProfilingEnabled(),
            parseProcessedBytes,
            knownRuleTypesProvider);
    ProjectBuildFileParserPool projectBuildFileParserPool =
        new ProjectBuildFileParserPool(
            numParsingThreads, // Max parsers to create per cell.
            projectBuildFileParserFactory,
            parsingContext.isProfilingEnabled());

    TargetNodeFactory targetNodeFactory =
        new TargetNodeFactory(typeCoercerFactory, new DefaultCellNameResolverProvider(cells));

    SelectorListFactory selectorListFactory =
        new SelectorListFactory(new SelectorFactory(unconfiguredBuildTargetFactory));

    BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline =
        new BuildFileRawNodeParsePipeline(
            new PipelineNodeCache<>(daemonicParserState.getRawNodeCache(), n -> false),
            projectBuildFileParserPool,
            executorService,
            eventBus,
            watchman);

    BuildTargetRawNodeParsePipeline buildTargetRawNodeParsePipeline =
        new BuildTargetRawNodeParsePipeline(executorService, buildFileRawNodeParsePipeline);

    PackageFileParserFactory packageFileParserFactory =
        new PackageFileParserFactory(
            typeCoercerFactory,
            parserPythonInterpreterProvider,
            knownRuleTypesProvider,
            parsingContext.isProfilingEnabled());

    PackageFileParserPool packageFileParserPool =
        new PackageFileParserPool(
            numParsingThreads, // Max parsers to create per cell.
            packageFileParserFactory);

    PackageFileParsePipeline packageFileParsePipeline =
        new PackageFileParsePipeline(
            new PipelineNodeCache<>(daemonicParserState.getPackageFileCache(), n -> false),
            packageFileParserPool,
            executorService,
            eventBus,
            watchman);

    PerBuildStateCache perBuildStateCache = new PerBuildStateCache(numParsingThreads);

    PackagePipeline packagePipeline =
        new PackagePipeline(
            executorService,
            eventBus,
            packageFileParsePipeline,
            perBuildStateCache.getPackageCache());

    ListeningExecutorService pipelineExecutorService =
        parserConfig.getEnableParallelParsing()
            ? executorService
            : MoreExecutors.newDirectExecutorService();
    boolean enableSpeculativeParsing =
        parserConfig.getEnableParallelParsing()
            && parsingContext.getSpeculativeParsing() == SpeculativeParsing.ENABLED;
    UnconfiguredTargetNodePipeline unconfiguredTargetNodePipeline =
        new UnconfiguredTargetNodePipeline(
            pipelineExecutorService,
            daemonicParserState.getOrCreateNodeCache(
                DaemonicParserState.RAW_TARGET_NODE_CACHE_TYPE),
            eventBus,
            buildFileRawNodeParsePipeline,
            buildTargetRawNodeParsePipeline,
            packagePipeline,
            new DefaultUnconfiguredTargetNodeFactory(
                knownRuleTypesProvider,
                new BuiltTargetVerifier(),
                cells,
                selectorListFactory,
                typeCoercerFactory));

    PackageBoundaryChecker packageBoundaryChecker =
        new ThrowingPackageBoundaryChecker(daemonicParserState.getBuildFileTrees());

    ParserTargetNodeFromUnconfiguredTargetNodeFactory nonResolvingRawTargetNodeToTargetNodeFactory =
        new UnconfiguredTargetNodeToTargetNodeFactory(
            typeCoercerFactory,
            knownRuleTypesProvider,
            marshaller,
            targetNodeFactory,
            packageBoundaryChecker,
            symlinkCheckers,
            new ThrowingSelectorListResolver(),
            new ThrowingPlatformResolver(),
            new MultiPlatformTargetConfigurationTransformer(new ThrowingPlatformResolver()),
            hostConfiguration,
            parsingContext.getCell().getBuckConfig(),
            Optional.empty());

    // This pipeline uses a direct executor instead of pipelineExecutorService to avoid
    // deadlocks happening when too many node are requested from targetNodeParsePipeline.
    // That pipeline does blocking calls to get nodes from nonResolvingTargetNodeParsePipeline
    // which can lead to deadlocks.
    UnconfiguredTargetNodeToTargetNodeParsePipeline nonResolvingTargetNodeParsePipeline =
        new UnconfiguredTargetNodeToTargetNodeParsePipeline(
            daemonicParserState.getOrCreateNodeCache(DaemonicParserState.TARGET_NODE_CACHE_TYPE),
            MoreExecutors.newDirectExecutorService(),
            unconfiguredTargetNodePipeline,
            targetConfigurationDetector,
            eventBus,
            "nonresolving_raw_target_node_parse_pipeline",
            enableSpeculativeParsing,
            nonResolvingRawTargetNodeToTargetNodeFactory,
            parserConfig.getRequireTargetPlatform());

    ConfigurationRuleRegistry configurationRuleRegistry =
        ConfigurationRuleRegistryFactory.createRegistry(
            (target, callerContext) ->
                nonResolvingTargetNodeParsePipeline
                    .getNode(cellManager.getCell(target.getCell()), target, callerContext)
                    .assertGetTargetNode(DependencyStack.root()));

    SelectableResolver selectableResolver =
        new ConfigurationRuleSelectableResolver(
            configurationRuleRegistry.getConfigurationRuleResolver());

    SelectorListResolver selectorListResolver;
    if (parsingContext.useUnconfiguredSelectorResolver()) {
      selectorListResolver = new UnconfiguredSelectorListResolver(selectableResolver);
    } else {
      selectorListResolver = new DefaultSelectorListResolver(selectableResolver);
    }

    UnconfiguredTargetNodeToTargetNodeFactory unconfiguredTargetNodeToTargetNodeFactory =
        new UnconfiguredTargetNodeToTargetNodeFactory(
            typeCoercerFactory,
            knownRuleTypesProvider,
            marshaller,
            targetNodeFactory,
            packageBoundaryChecker,
            symlinkCheckers,
            selectorListResolver,
            configurationRuleRegistry.getTargetPlatformResolver(),
            new MultiPlatformTargetConfigurationTransformer(
                configurationRuleRegistry.getTargetPlatformResolver()),
            hostConfiguration,
            parsingContext.getCell().getBuckConfig(),
            (parsingContext.excludeUnsupportedTargets()
                    && parsingContext.enableTargetCompatibilityChecks())
                ? Optional.of(configurationRuleRegistry)
                : Optional.empty());

    ListeningExecutorService configuredPipelineExecutor =
        MoreExecutors.listeningDecorator(
            createExecutorService(cells.getRootCell().getBuckConfig(), "configured-pipeline"));

    UnconfiguredTargetNodeToTargetNodeParsePipeline targetNodeParsePipeline =
        new UnconfiguredTargetNodeToTargetNodeParsePipeline(
            daemonicParserState.getOrCreateNodeCache(DaemonicParserState.TARGET_NODE_CACHE_TYPE),
            configuredPipelineExecutor,
            unconfiguredTargetNodePipeline,
            targetConfigurationDetector,
            eventBus,
            "configured_raw_target_node_parse_pipeline",
            enableSpeculativeParsing,
            unconfiguredTargetNodeToTargetNodeFactory,
            parserConfig.getRequireTargetPlatform()) {
          @Override
          public void close() {
            super.close();
            nonResolvingTargetNodeParsePipeline.close();
            unconfiguredTargetNodePipeline.close();
            try {
              MostExecutors.shutdown(configuredPipelineExecutor, 1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
            }
          }
        };

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

  public PerBuildState create(
      ParsingContext parsingContext, DaemonicParserState daemonicParserState) {
    return create(parsingContext, daemonicParserState, Optional.empty());
  }

  public PerBuildState create(
      ParsingContext parsingContext,
      DaemonicParserState daemonicParserState,
      AtomicLong processedBytes) {
    return create(parsingContext, daemonicParserState, Optional.of(processedBytes));
  }
}
