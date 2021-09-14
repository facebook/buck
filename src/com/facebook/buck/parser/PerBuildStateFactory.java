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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.DefaultCellNameResolverProvider;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.ConfigurationBuildTargets;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationResolver;
import com.facebook.buck.core.model.impl.MultiPlatformTargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.impl.ConfigurationPlatformResolver;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.PathsChecker;
import com.facebook.buck.core.model.targetgraph.impl.PathsCheckerFactory;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
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
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

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

  private static PathsChecker getPathsChecker(
      ParserConfig.PathsCheckMethod pathsCheckMethod, Watchman watchman) {
    switch (pathsCheckMethod) {
      case FILESYSTEM:
        return PathsCheckerFactory.createFileSystemPathsChecker();
      case WATCHMAN:
        return PathsCheckerFactory.createWatchmanPathChecker(watchman);
      case NONE:
        return PathsCheckerFactory.createNoopPathsChecker();
      default:
        throw new IllegalStateException("Unexpected path check method: " + pathsCheckMethod);
    }
  }

  private static PackageBoundaryChecker getPackageBoundaryChecker(
      ParserConfig.PackageBoundaryCheckMethod packageBoundaryCheckMethod,
      Watchman watchman,
      LoadingCache<Cell, BuildFileTree> buildFileTree) {
    switch (packageBoundaryCheckMethod) {
      case FILESYSTEM:
        return new ThrowingPackageBoundaryChecker(buildFileTree);
      case WATCHMAN:
        return new WatchmanGlobberPackageBoundaryChecker(
            watchman, new ThrowingPackageBoundaryChecker(buildFileTree));
      default:
        throw new IllegalStateException(
            "Unexpected package boundary check method: " + packageBoundaryCheckMethod);
    }
  }

  /** Constructor. */
  public PerBuildState create(
      ParsingContext parsingContext, DaemonicParserState daemonicParserState) {

    // Here we acquired validation token.
    // At this moment we already handled watchman event, so
    // parser state should not be invalidated after this point
    // (except for invalidation by another command of course).
    DaemonicParserValidationToken validationToken = daemonicParserState.validationToken();

    Cells cells = parsingContext.getCells();
    ListeningExecutorService executorService = parsingContext.getExecutor();
    SymlinkCache symlinkCache = new SymlinkCache(eventBus, daemonicParserState);
    CellManager cellManager = new CellManager(cells, symlinkCache);

    TargetNodeListener<TargetNode<?>> symlinkCheckers = cellManager::registerInputsUnderSymlinks;
    ParserConfig parserConfig = cells.getRootCell().getBuckConfig().getView(ParserConfig.class);

    TargetConfigurationDetector targetConfigurationDetector =
        TargetConfigurationDetectorFactory.fromBuckConfig(
            parserConfig,
            unconfiguredBuildTargetFactory,
            cells.getRootCell().getCellNameResolver());

    int numParsingThreads = parserConfig.getNumParsingThreads();
    DefaultProjectBuildFileParserFactory projectBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            typeCoercerFactory, parserPythonInterpreterProvider, knownRuleTypesProvider);
    ProjectBuildFileParserPool projectBuildFileParserPool =
        new ProjectBuildFileParserPool(
            numParsingThreads, // Max parsers to create per cell.
            projectBuildFileParserFactory,
            parsingContext.isProfilingEnabled());

    TargetNodeFactory targetNodeFactory =
        new TargetNodeFactory(
            typeCoercerFactory,
            getPathsChecker(parserConfig.getPathsCheckMethod(), watchman),
            new DefaultCellNameResolverProvider(cells));

    SelectorListFactory selectorListFactory =
        new SelectorListFactory(new SelectorFactory(unconfiguredBuildTargetFactory));

    BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline =
        new BuildFileRawNodeParsePipeline(
            new PipelineNodeCache<>(
                daemonicParserState.getRawNodeCache(), validationToken, n -> false),
            projectBuildFileParserPool,
            executorService,
            eventBus,
            watchman);

    BuildTargetRawNodeParsePipeline buildTargetRawNodeParsePipeline =
        new BuildTargetRawNodeParsePipeline(executorService, buildFileRawNodeParsePipeline);

    PackageFileParserFactory packageFileParserFactory =
        new PackageFileParserFactory(
            typeCoercerFactory, parserPythonInterpreterProvider, knownRuleTypesProvider);

    PackageFileParserPool packageFileParserPool =
        new PackageFileParserPool(
            numParsingThreads, // Max parsers to create per cell.
            packageFileParserFactory);

    PackageFileParsePipeline packageFileParsePipeline =
        new PackageFileParsePipeline(
            new PipelineNodeCache<>(
                daemonicParserState.getPackageFileCache(), validationToken, n -> false),
            packageFileParserPool,
            executorService,
            eventBus,
            watchman);

    PackagePipeline packagePipeline =
        new PackagePipeline(executorService, eventBus, packageFileParsePipeline);

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
            validationToken,
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
        getPackageBoundaryChecker(
            parserConfig.getPackageBoundaryCheckMethod(),
            watchman,
            daemonicParserState.getBuildFileTrees());

    CellBoundaryChecker cellBoundaryChecker =
        parserConfig.getEnforceCellBoundary()
            ? new ThrowingCellBoundaryChecker(cells)
            : new NoopCellBoundaryChecker();

    TargetConfigurationResolver hostTargetConfigurationResolver =
        target -> {
          // First, see if the rule sets an explicit host platform to use.
          ListenableFuture<UnconfiguredTargetNode> nodeJob =
              unconfiguredTargetNodePipeline.getNodeJob(
                  cellManager.getCell(target.getCell()), target, DependencyStack.root());
          UnconfiguredTargetNode node = Futures.getUnchecked(nodeJob);
          if (node.getDefaultHostPlatform().isPresent()) {
            return RuleBasedTargetConfiguration.of(
                ConfigurationBuildTargets.convert(node.getDefaultHostPlatform().get()));
          }

          return hostConfiguration;
        };

    ParserTargetNodeFromUnconfiguredTargetNodeFactory nonResolvingRawTargetNodeToTargetNodeFactory =
        new UnconfiguredTargetNodeToTargetNodeFactory(
            cells,
            typeCoercerFactory,
            knownRuleTypesProvider,
            marshaller,
            targetNodeFactory,
            packageBoundaryChecker,
            cellBoundaryChecker,
            symlinkCheckers,
            new ThrowingSelectorListResolver(),
            new ConfigurationPlatformResolver(),
            new MultiPlatformTargetConfigurationTransformer(new ConfigurationPlatformResolver()),
            hostTargetConfigurationResolver,
            parsingContext.getCells().getBuckConfig(),
            Optional.empty());

    // This pipeline uses a direct executor instead of pipelineExecutorService to avoid
    // deadlocks happening when too many node are requested from targetNodeParsePipeline.
    // That pipeline does blocking calls to get nodes from nonResolvingTargetNodeParsePipeline
    // which can lead to deadlocks.
    UnconfiguredTargetNodeToTargetNodeParsePipeline nonResolvingTargetNodeParsePipeline =
        new UnconfiguredTargetNodeToTargetNodeParsePipeline(
            daemonicParserState.getOrCreateNodeCache(DaemonicParserState.TARGET_NODE_CACHE_TYPE),
            validationToken,
            MoreExecutors.newDirectExecutorService(),
            cells,
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
    selectorListResolver = new DefaultSelectorListResolver(selectableResolver);

    UnconfiguredTargetNodeToTargetNodeFactory unconfiguredTargetNodeToTargetNodeFactory =
        new UnconfiguredTargetNodeToTargetNodeFactory(
            cells,
            typeCoercerFactory,
            knownRuleTypesProvider,
            marshaller,
            targetNodeFactory,
            packageBoundaryChecker,
            cellBoundaryChecker,
            symlinkCheckers,
            selectorListResolver,
            configurationRuleRegistry.getTargetPlatformResolver(),
            new MultiPlatformTargetConfigurationTransformer(
                configurationRuleRegistry.getTargetPlatformResolver()),
            hostTargetConfigurationResolver,
            parsingContext.getCells().getBuckConfig(),
            (parsingContext.enableTargetCompatibilityChecks())
                ? Optional.of(configurationRuleRegistry)
                : Optional.empty());

    ListeningExecutorService configuredPipelineExecutor =
        MoreExecutors.listeningDecorator(
            createExecutorService(cells.getRootCell().getBuckConfig(), "configured-pipeline"));

    UnconfiguredTargetNodeToTargetNodeParsePipeline targetNodeParsePipeline =
        new UnconfiguredTargetNodeToTargetNodeParsePipeline(
            daemonicParserState.getOrCreateNodeCache(DaemonicParserState.TARGET_NODE_CACHE_TYPE),
            validationToken,
            configuredPipelineExecutor,
            cells,
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
        unconfiguredTargetNodePipeline,
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
