/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.command;

import com.facebook.buck.artifact_cache.ArtifactCacheFactory;
import com.facebook.buck.core.build.distributed.synchronization.RemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.engine.BuildEngineResult;
import com.facebook.buck.core.build.engine.cache.manager.BuildInfoStoreManager;
import com.facebook.buck.core.build.engine.config.CachingBuildEngineBuckConfig;
import com.facebook.buck.core.build.engine.delegate.CachingBuildEngineDelegate;
import com.facebook.buck.core.build.engine.impl.CachingBuildEngine;
import com.facebook.buck.core.build.engine.impl.MetadataChecker;
import com.facebook.buck.core.build.engine.type.BuildType;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.resources.ResourcesConfig;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.config.RuleKeyConfig;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.modern.builders.ModernBuildRuleBuilderFactory;
import com.facebook.buck.rules.modern.config.ModernBuildRuleConfig;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.immutables.value.Value;

/** Used to build a given set of targets on the local machine. */
public class LocalBuildExecutor implements BuildExecutor {
  private final ActionGraphAndBuilder actionGraphAndBuilder;
  private final WeightedListeningExecutorService executorService;
  private final CachingBuildEngineDelegate cachingBuildEngineDelegate;
  private final BuildExecutorArgs args;
  private final RuleKeyCacheScope<RuleKey> ruleKeyCacheScope;
  private final RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter;
  private final Optional<BuildType> buildEngineMode;
  private final Optional<ThriftRuleKeyLogger> ruleKeyLogger;

  private final CachingBuildEngine cachingBuildEngine;
  private final Build build;

  private volatile boolean isShutdown = false;

  public LocalBuildExecutor(
      BuildExecutorArgs args,
      ExecutionContext executionContext,
      ActionGraphAndBuilder actionGraphAndBuilder,
      CachingBuildEngineDelegate cachingBuildEngineDelegate,
      WeightedListeningExecutorService executorService,
      boolean keepGoing,
      boolean useDistributedBuildCache,
      boolean isDownloadHeavyBuild,
      RuleKeyCacheScope<RuleKey> ruleKeyRuleKeyCacheScope,
      Optional<BuildType> buildEngineMode,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger,
      RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter) {
    this.actionGraphAndBuilder = actionGraphAndBuilder;
    this.executorService = executorService;
    this.args = args;
    this.cachingBuildEngineDelegate = cachingBuildEngineDelegate;
    this.buildEngineMode = buildEngineMode;
    this.ruleKeyLogger = ruleKeyLogger;
    this.ruleKeyCacheScope = ruleKeyRuleKeyCacheScope;
    this.remoteBuildRuleCompletionWaiter = remoteBuildRuleCompletionWaiter;

    // Init resources.
    this.cachingBuildEngine = createCachingBuildEngine();
    this.build =
        new Build(
            actionGraphAndBuilder.getActionGraphBuilder(),
            args.getRootCell(),
            cachingBuildEngine,
            args.getArtifactCacheFactory()
                .newInstance(useDistributedBuildCache, isDownloadHeavyBuild),
            args.getBuckConfig().getView(JavaBuckConfig.class).createDefaultJavaPackageFinder(),
            args.getClock(),
            executionContext,
            keepGoing);
  }

  @Override
  public ExitCode buildLocallyAndReturnExitCode(
      Iterable<String> targetsToBuild, Optional<Path> pathToBuildReport) {
    return buildTargets(
        FluentIterable.from(targetsToBuild)
            .transform(
                targetName ->
                    BuildTargetParser.fullyQualifiedNameToBuildTarget(
                        args.getRootCell().getCellPathResolver(), targetName)),
        pathToBuildReport);
  }

  @Override
  public ExitCode buildTargets(
      Iterable<BuildTarget> targetsToBuild, Optional<Path> pathToBuildReport) {
    Preconditions.checkArgument(!isShutdown);
    try {
      return build.executeAndPrintFailuresToEventBus(
          targetsToBuild, args.getBuckEventBus(), args.getConsole(), pathToBuildReport);
    } catch (BuildTargetParseException e) {
      throw new HumanReadableException(
          e.getMessage()
              + "\n"
              + "Please check whether one of the targets passed as parameter has an empty or invalid name.");
    }
  }

  @Override
  public List<BuildEngineResult> initializeBuild(Iterable<String> targetsToBuild)
      throws IOException {
    Preconditions.checkArgument(!isShutdown);
    return build.initializeBuild(getRulesToBuild(targetsToBuild));
  }

  @Override
  public ExitCode waitForBuildToFinish(
      Iterable<String> targetsToBuild,
      List<BuildEngineResult> resultFutures,
      Optional<Path> pathToBuildReport) {
    Preconditions.checkArgument(!isShutdown);
    return build.waitForBuildToFinishAndPrintFailuresToEventBus(
        getRulesToBuild(targetsToBuild),
        resultFutures,
        args.getBuckEventBus(),
        args.getConsole(),
        pathToBuildReport);
  }

  @Override
  public CachingBuildEngine getCachingBuildEngine() {
    return cachingBuildEngine;
  }

  @Override
  public synchronized void shutdown() {
    if (isShutdown) {
      return;
    }

    isShutdown = true;

    // Destroy resources.
    build.close();
    cachingBuildEngine.close();
  }

  private ImmutableList<BuildRule> getRulesToBuild(Iterable<String> targetsToBuild) {
    return build.getRulesToBuild(
        Iterables.transform(
            targetsToBuild,
            targetName ->
                BuildTargetParser.fullyQualifiedNameToBuildTarget(
                    args.getRootCell().getCellPathResolver(), targetName)));
  }

  private CachingBuildEngine createCachingBuildEngine() {
    try {
      MetadataChecker.checkAndCleanIfNeeded(args.getRootCell());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    CachingBuildEngineBuckConfig engineConfig =
        args.getBuckConfig().getView(CachingBuildEngineBuckConfig.class);
    SourcePathRuleFinder sourcePathRuleFinder =
        new SourcePathRuleFinder(actionGraphAndBuilder.getActionGraphBuilder());

    return new CachingBuildEngine(
        cachingBuildEngineDelegate,
        ModernBuildRuleBuilderFactory.getBuildStrategy(
            args.getBuckConfig().getView(ModernBuildRuleConfig.class),
            args.getBuckConfig().getView(RemoteExecutionConfig.class),
            actionGraphAndBuilder.getActionGraphBuilder(),
            args.getRootCell(),
            args.getRootCell().getCellPathResolver(),
            cachingBuildEngineDelegate.getFileHashCache(),
            args.getBuckEventBus(),
            args.getConsole(),
            Objects.requireNonNull(args.getExecutors().get(ExecutorPool.REMOTE))),
        executorService,
        new DefaultStepRunner(),
        buildEngineMode.orElse(engineConfig.getBuildEngineMode()),
        engineConfig.getBuildMetadataStorage(),
        engineConfig.getBuildDepFiles(),
        engineConfig.getBuildMaxDepFileCacheEntries(),
        engineConfig.getBuildArtifactCacheSizeLimit(),
        actionGraphAndBuilder.getActionGraphBuilder(),
        sourcePathRuleFinder,
        DefaultSourcePathResolver.from(sourcePathRuleFinder),
        args.getBuildInfoStoreManager(),
        engineConfig.getResourceAwareSchedulingInfo(),
        engineConfig.getConsoleLogBuildRuleFailuresInline(),
        RuleKeyFactories.of(
            args.getRuleKeyConfiguration(),
            cachingBuildEngineDelegate.getFileHashCache(),
            actionGraphAndBuilder.getActionGraphBuilder(),
            args.getBuckConfig().getBuildInputRuleKeyFileSizeLimit(),
            ruleKeyCacheScope.getCache(),
            ruleKeyLogger),
        remoteBuildRuleCompletionWaiter);
  }

  public Build getBuild() {
    return build;
  }

  /**
   * Create {@link ExecutionContext} using {@link BuildExecutorArgs}.
   *
   * @param args - an instance {@link BuildExecutorArgs}.
   */
  public static ExecutionContext createExecutionContext(BuildExecutorArgs args) {
    // TODO(shivanker): Fix this for stampede to be able to build android.
    ConcurrencyLimit concurrencyLimit =
        args.getBuckConfig().getView(ResourcesConfig.class).getConcurrencyLimit();
    DefaultProcessExecutor processExecutor = new DefaultProcessExecutor(args.getConsole());

    return ExecutionContext.builder()
        .setConsole(args.getConsole())
        .setTargetDevice(Optional.empty())
        .setDefaultTestTimeoutMillis(1000)
        .setCodeCoverageEnabled(false)
        .setInclNoLocationClassesEnabled(false)
        .setDebugEnabled(false)
        .setRuleKeyDiagnosticsMode(
            args.getBuckConfig().getView(RuleKeyConfig.class).getRuleKeyDiagnosticsMode())
        .setShouldReportAbsolutePaths(false)
        .setBuckEventBus(args.getBuckEventBus())
        .setPlatform(args.getPlatform())
        .setJavaPackageFinder(
            args.getBuckConfig().getView(JavaBuckConfig.class).createDefaultJavaPackageFinder())
        .setConcurrencyLimit(concurrencyLimit)
        .setPersistentWorkerPools(Optional.empty())
        .setExecutors(args.getExecutors())
        .setCellPathResolver(args.getRootCell().getCellPathResolver())
        .setBuildCellRootPath(args.getRootCell().getRoot())
        .setProcessExecutor(processExecutor)
        .setEnvironment(args.getBuckConfig().getEnvironment())
        .setProjectFilesystemFactory(args.getProjectFilesystemFactory())
        .build();
  }
}

/** Common arguments for running a build. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractBuildExecutorArgs {
  public abstract Console getConsole();

  public abstract BuckEventBus getBuckEventBus();

  public abstract Platform getPlatform();

  public abstract Clock getClock();

  public abstract Cell getRootCell();

  public abstract ImmutableMap<ExecutorPool, ListeningExecutorService> getExecutors();

  public abstract ProjectFilesystemFactory getProjectFilesystemFactory();

  public abstract BuildInfoStoreManager getBuildInfoStoreManager();

  public abstract ArtifactCacheFactory getArtifactCacheFactory();

  public abstract RuleKeyConfiguration getRuleKeyConfiguration();

  public BuckConfig getBuckConfig() {
    return getRootCell().getBuckConfig();
  }

  public int getBuildThreadCount() {
    return getBuckConfig().getView(ResourcesConfig.class).getConcurrencyLimit().threadLimit;
  }
}
