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

import com.facebook.buck.android.AndroidPlatformTargetSupplier;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.resources.ResourcesConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildEngineResult;
import com.facebook.buck.rules.BuildInfoStoreManager;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.CachingBuildEngine.BuildMode;
import com.facebook.buck.rules.CachingBuildEngineBuckConfig;
import com.facebook.buck.rules.CachingBuildEngineDelegate;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.MetadataChecker;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.keys.DefaultRuleKeyCache;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.immutables.value.Value;

/** Used to build a given set of targets on the local machine. */
public class LocalBuilder implements Builder {
  private final ActionGraphAndResolver actionGraphAndResolver;
  private final WeightedListeningExecutorService executorService;
  private final CachingBuildEngineDelegate cachingBuildEngineDelegate;
  private final BuilderArgs args;
  private final Optional<RuleKeyCacheScope<RuleKey>> ruleKeyCacheScope;
  private final Optional<CachingBuildEngine.BuildMode> buildEngineMode;
  private final Optional<ThriftRuleKeyLogger> ruleKeyLogger;

  private final CachingBuildEngine cachingBuildEngine;
  private final Build build;

  private volatile boolean isShutdown = false;

  public LocalBuilder(
      BuilderArgs args,
      ExecutionContext executionContext,
      ActionGraphAndResolver actionGraphAndResolver,
      CachingBuildEngineDelegate cachingBuildEngineDelegate,
      ArtifactCache artifactCache,
      WeightedListeningExecutorService executorService,
      boolean keepGoing,
      Optional<RuleKeyCacheScope<RuleKey>> ruleKeyRuleKeyCacheScope,
      Optional<BuildMode> buildEngineMode,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    this.actionGraphAndResolver = actionGraphAndResolver;
    this.executorService = executorService;
    this.args = args;
    this.cachingBuildEngineDelegate = cachingBuildEngineDelegate;
    this.buildEngineMode = buildEngineMode;
    this.ruleKeyLogger = ruleKeyLogger;
    this.ruleKeyCacheScope = ruleKeyRuleKeyCacheScope;

    // Init resources.
    this.cachingBuildEngine = createCachingBuildEngine();
    this.build =
        new Build(
            actionGraphAndResolver.getResolver(),
            args.getRootCell(),
            cachingBuildEngine,
            artifactCache,
            args.getBuckConfig().getView(JavaBuckConfig.class).createDefaultJavaPackageFinder(),
            args.getClock(),
            executionContext,
            keepGoing);
  }

  @Override
  public int buildLocallyAndReturnExitCode(
      Iterable<String> targetToBuildStrings, Optional<Path> pathToBuildReport)
      throws IOException, InterruptedException {
    Preconditions.checkArgument(!isShutdown);
    return build.executeAndPrintFailuresToEventBus(
        Iterables.transform(
            targetToBuildStrings,
            targetName ->
                BuildTargetParser.fullyQualifiedNameToBuildTarget(
                    args.getRootCell().getCellPathResolver(), targetName)),
        args.getBuckEventBus(),
        args.getConsole(),
        pathToBuildReport);
  }

  @Override
  public List<BuildEngineResult> initializeBuild(Iterable<String> targetsToBuild)
      throws IOException {
    Preconditions.checkArgument(!isShutdown);
    return build.initializeBuild(getRulesToBuild(targetsToBuild));
  }

  @Override
  public int waitForBuildToFinish(
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
  public synchronized void shutdown() throws IOException {
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

    return new CachingBuildEngine(
        cachingBuildEngineDelegate,
        executorService,
        new DefaultStepRunner(),
        buildEngineMode.orElse(engineConfig.getBuildEngineMode()),
        engineConfig.getBuildMetadataStorage(),
        engineConfig.getBuildDepFiles(),
        engineConfig.getBuildMaxDepFileCacheEntries(),
        engineConfig.getBuildArtifactCacheSizeLimit(),
        actionGraphAndResolver.getResolver(),
        args.getBuildInfoStoreManager(),
        engineConfig.getResourceAwareSchedulingInfo(),
        engineConfig.getConsoleLogBuildRuleFailuresInline(),
        RuleKeyFactories.of(
            args.getBuckConfig().getKeySeed(),
            cachingBuildEngineDelegate.getFileHashCache(),
            actionGraphAndResolver.getResolver(),
            engineConfig.getBuildInputRuleKeyFileSizeLimit(),
            ruleKeyCacheScope.map(RuleKeyCacheScope::getCache).orElse(new DefaultRuleKeyCache<>()),
            ruleKeyLogger),
        args.getBuckConfig().getFileHashCacheMode());
  }

  public Build getBuild() {
    return build;
  }

  /**
   * Create {@link ExecutionContext} using {@link BuilderArgs}.
   *
   * @param args - an instance {@link BuilderArgs}.
   */
  public static ExecutionContext createExecutionContext(BuilderArgs args) {
    // TODO(shivanker): Fix this for stampede to be able to build android.
    final ConcurrencyLimit concurrencyLimit =
        args.getBuckConfig().getView(ResourcesConfig.class).getConcurrencyLimit();
    final AndroidPlatformTargetSupplier androidSupplier =
        AndroidPlatformTargetSupplier.create(
            args.getRootCell().getFilesystem(),
            args.getRootCell().getBuckConfig(),
            args.getPlatform());
    final DefaultProcessExecutor processExecutor = new DefaultProcessExecutor(args.getConsole());

    return ExecutionContext.builder()
        .setConsole(args.getConsole())
        .setAndroidPlatformTargetSupplier(androidSupplier)
        .setTargetDevice(Optional.empty())
        .setDefaultTestTimeoutMillis(1000)
        .setCodeCoverageEnabled(false)
        .setInclNoLocationClassesEnabled(false)
        .setDebugEnabled(false)
        .setRuleKeyDiagnosticsMode(args.getBuckConfig().getRuleKeyDiagnosticsMode())
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
abstract class AbstractBuilderArgs {
  public abstract Console getConsole();

  public abstract BuckEventBus getBuckEventBus();

  public abstract Platform getPlatform();

  public abstract Clock getClock();

  public abstract Cell getRootCell();

  public abstract ImmutableMap<ExecutorPool, ListeningExecutorService> getExecutors();

  public abstract ProjectFilesystemFactory getProjectFilesystemFactory();

  public abstract BuildInfoStoreManager getBuildInfoStoreManager();

  public BuckConfig getBuckConfig() {
    return getRootCell().getBuckConfig();
  }
}
