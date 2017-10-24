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
package com.facebook.buck.distributed;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.android.AndroidPlatformTargetSupplier;
import com.facebook.buck.android.DefaultAndroidDirectoryResolver;
import com.facebook.buck.command.Build;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.resources.ResourcesConfig;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildEngineResult;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.CachingBuildEngineBuckConfig;
import com.facebook.buck.rules.CachingBuildEngineDelegate;
import com.facebook.buck.rules.MetadataChecker;
import com.facebook.buck.rules.keys.DefaultRuleKeyCache;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** * Used by Stampede to build a given set of targets on the local machine. */
public class LocalBuilderImpl implements LocalBuilder {
  private static final boolean KEEP_GOING = true;

  private final BuckConfig distBuildConfig;
  private final CachingBuildEngineBuckConfig engineConfig;
  private final DistBuildExecutorArgs args;
  private final CachingBuildEngineDelegate cachingBuildEngineDelegate;
  private final ActionGraphAndResolver actionGraphAndResolver;

  private final CachingBuildEngine cachingBuildEngine;
  private final ExecutionContext executionContext;
  private final Build build;

  private volatile boolean isShutdown = false;

  public LocalBuilderImpl(
      DistBuildExecutorArgs args,
      CachingBuildEngineDelegate cachingBuildEngineDelegate,
      ActionGraphAndResolver actionGraphAndResolver) {
    this.args = args;
    this.cachingBuildEngineDelegate = cachingBuildEngineDelegate;
    this.actionGraphAndResolver = actionGraphAndResolver;
    this.distBuildConfig = args.getRemoteRootCellConfig();
    this.engineConfig = distBuildConfig.getView(CachingBuildEngineBuckConfig.class);
    this.cachingBuildEngine = createCachingBuildEngine();
    this.executionContext = createExecutionContext();
    this.build = createBuild();
  }

  @Override
  public int buildLocallyAndReturnExitCode(Iterable<String> targetToBuildStrings)
      throws IOException, InterruptedException {
    Preconditions.checkArgument(!isShutdown);
    return build.executeAndPrintFailuresToEventBus(
        DistBuildUtil.fullyQualifiedNameToBuildTarget(
            args.getState().getRootCell().getCellPathResolver(), targetToBuildStrings),
        args.getBuckEventBus(),
        args.getConsole(),
        Optional.empty());
  }

  @Override
  public List<BuildEngineResult> initializeBuild(Iterable<String> targetsToBuild)
      throws IOException {
    Preconditions.checkArgument(!isShutdown);
    return build.initializeBuild(getRulesToBuild(targetsToBuild));
  }

  @Override
  public int waitForBuildToFinish(
      Iterable<String> targetsToBuild, List<BuildEngineResult> resultFutures) {
    Preconditions.checkArgument(!isShutdown);
    return build.waitForBuildToFinishAndPrintFailuresToEventBus(
        getRulesToBuild(targetsToBuild),
        resultFutures,
        args.getBuckEventBus(),
        args.getConsole(),
        Optional.empty());
  }

  @Override
  public synchronized void shutdown() throws IOException {
    if (isShutdown) {
      return;
    }

    isShutdown = true;

    build.close();
    executionContext.close();
    cachingBuildEngine.close();
  }

  private ImmutableList<BuildRule> getRulesToBuild(Iterable<String> targetsToBuild) {
    return build.getRulesToBuild(
        DistBuildUtil.fullyQualifiedNameToBuildTarget(
            args.getState().getRootCell().getCellPathResolver(), targetsToBuild));
  }

  private static Supplier<AndroidPlatformTarget> getAndroidPlatformTargetSupplier(
      DistBuildExecutorArgs args) {
    AndroidBuckConfig androidConfig =
        new AndroidBuckConfig(args.getRemoteRootCellConfig(), args.getPlatform());

    AndroidDirectoryResolver dirResolver =
        new DefaultAndroidDirectoryResolver(
            args.getRootCell().getFilesystem().getRootPath().getFileSystem(),
            args.getRemoteRootCellConfig().getEnvironment(),
            androidConfig);
    return new AndroidPlatformTargetSupplier(dirResolver, androidConfig);
  }

  private CachingBuildEngine createCachingBuildEngine() {
    return new CachingBuildEngine(
        Preconditions.checkNotNull(cachingBuildEngineDelegate),
        args.getExecutorService(),
        new DefaultStepRunner(),
        engineConfig.getBuildEngineMode(),
        engineConfig.getBuildMetadataStorage(),
        engineConfig.getBuildDepFiles(),
        engineConfig.getBuildMaxDepFileCacheEntries(),
        engineConfig.getBuildArtifactCacheSizeLimit(),
        Preconditions.checkNotNull(actionGraphAndResolver).getResolver(),
        args.getBuildInfoStoreManager(),
        engineConfig.getResourceAwareSchedulingInfo(),
        engineConfig.getConsoleLogBuildRuleFailuresInline(),
        RuleKeyFactories.of(
            distBuildConfig.getKeySeed(),
            cachingBuildEngineDelegate.getFileHashCache(),
            actionGraphAndResolver.getResolver(),
            engineConfig.getBuildInputRuleKeyFileSizeLimit(),
            new DefaultRuleKeyCache<>()),
        distBuildConfig.getFileHashCacheMode());
  }

  private ExecutionContext createExecutionContext() {
    try {
      MetadataChecker.checkAndCleanIfNeeded(args.getRootCell());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    final DefaultProcessExecutor processExecutor = new DefaultProcessExecutor(args.getConsole());
    final ConcurrencyLimit concurrencyLimit =
        distBuildConfig.getView(ResourcesConfig.class).getConcurrencyLimit();
    return ExecutionContext.builder()
        .setConsole(args.getConsole())
        .setAndroidPlatformTargetSupplier(getAndroidPlatformTargetSupplier(args))
        .setTargetDevice(Optional.empty())
        .setDefaultTestTimeoutMillis(1000)
        .setCodeCoverageEnabled(false)
        .setInclNoLocationClassesEnabled(false)
        .setDebugEnabled(false)
        .setRuleKeyDiagnosticsMode(distBuildConfig.getRuleKeyDiagnosticsMode())
        .setShouldReportAbsolutePaths(false)
        .setBuckEventBus(args.getBuckEventBus())
        .setPlatform(args.getPlatform())
        .setJavaPackageFinder(
            distBuildConfig.getView(JavaBuckConfig.class).createDefaultJavaPackageFinder())
        .setConcurrencyLimit(concurrencyLimit)
        .setPersistentWorkerPools(Optional.empty())
        .setExecutors(args.getExecutors())
        .setCellPathResolver(args.getRootCell().getCellPathResolver())
        .setBuildCellRootPath(args.getRootCell().getRoot())
        .setProcessExecutor(processExecutor)
        .setEnvironment(distBuildConfig.getEnvironment())
        .setProjectFilesystemFactory(args.getProjectFilesystemFactory())
        .build();
  }

  private Build createBuild() {
    return new Build(
        Preconditions.checkNotNull(actionGraphAndResolver).getResolver(),
        args.getRootCell(),
        cachingBuildEngine,
        args.getArtifactCache(),
        distBuildConfig.getView(JavaBuckConfig.class).createDefaultJavaPackageFinder(),
        args.getClock(),
        executionContext,
        KEEP_GOING);
  }
}
