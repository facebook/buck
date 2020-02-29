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

package com.facebook.buck.cli;

import com.facebook.buck.artifact_cache.ArtifactCacheFactory;
import com.facebook.buck.command.BuildExecutorArgs;
import com.facebook.buck.core.build.engine.cache.manager.BuildInfoStoreManager;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProvider;
import com.facebook.buck.core.module.BuckModuleManager;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.support.state.BuckGlobalState;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.concurrent.ExecutorPool;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.facebook.buck.versions.InstrumentedVersionedTargetGraphCache;
import com.facebook.buck.worker.WorkerProcessPool;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import org.pf4j.PluginManager;

@BuckStyleValue
public abstract class CommandRunnerParams {

  public abstract Console getConsole();

  public abstract InputStream getStdIn();

  public abstract Cells getCells();

  public abstract Watchman getWatchman();

  public abstract InstrumentedVersionedTargetGraphCache getVersionedTargetGraphCache();

  public abstract ArtifactCacheFactory getArtifactCacheFactory();

  public abstract TypeCoercerFactory getTypeCoercerFactory();

  public abstract UnconfiguredBuildTargetViewFactory getUnconfiguredBuildTargetFactory();

  public abstract Optional<TargetConfiguration> getTargetConfiguration();

  public abstract Optional<TargetConfiguration> getHostConfiguration();

  public abstract TargetConfigurationSerializer getTargetConfigurationSerializer();

  public abstract Parser getParser();

  public abstract BuckEventBus getBuckEventBus();

  public abstract Platform getPlatform();

  public abstract ImmutableMap<String, String> getEnvironment();

  public abstract JavaPackageFinder getJavaPackageFinder();

  public abstract Clock getClock();

  public abstract VersionControlStatsGenerator getVersionControlStatsGenerator();

  public abstract Optional<ProcessManager> getProcessManager();

  public abstract Optional<WebServer> getWebServer();

  public abstract ConcurrentMap<String, WorkerProcessPool> getPersistentWorkerPools();

  public abstract BuckConfig getBuckConfig();

  public abstract StackedFileHashCache getFileHashCache();

  public abstract ImmutableMap<ExecutorPool, ListeningExecutorService> getExecutors();

  public abstract ScheduledExecutorService getScheduledExecutor();

  public abstract BuildEnvironmentDescription getBuildEnvironmentDescription();

  public abstract ActionGraphProvider getActionGraphProvider();

  public abstract KnownRuleTypesProvider getKnownRuleTypesProvider();

  public abstract BuildInfoStoreManager getBuildInfoStoreManager();

  public abstract Optional<InvocationInfo> getInvocationInfo();

  public abstract Optional<RuleKeyCacheRecycler<RuleKey>> getDefaultRuleKeyFactoryCacheRecycler();

  public abstract ProjectFilesystemFactory getProjectFilesystemFactory();

  public abstract RuleKeyConfiguration getRuleKeyConfiguration();

  public abstract ProcessExecutor getProcessExecutor();

  public abstract ExecutableFinder getExecutableFinder();

  public abstract PluginManager getPluginManager();

  public abstract BuckModuleManager getBuckModuleManager();

  public abstract CloseableMemoizedSupplier<DepsAwareExecutor<? super ComputeResult, ?>>
      getDepsAwareExecutorSupplier();

  public abstract MetadataProvider getMetadataProvider();

  /**
   * @return {@link BuckGlobalState} object which is a set of objects bearing the data reflecting
   *     filesystem state and thus shared between commands
   */
  public abstract BuckGlobalState getGlobalState();

  /**
   * @return the original absolute PWD for the client
   *     <p>NOTE: This is the path the the user was in when they invoked buck. The buck wrapper
   *     executes buck from the project root, so this is not the same as most {@code getCwd()} style
   *     functions.
   */
  public abstract Path getClientWorkingDir();

  /**
   * Create {@link BuildExecutorArgs} using this {@link CommandRunnerParams}.
   *
   * @return New instance of {@link BuildExecutorArgs}.
   */
  public BuildExecutorArgs createBuilderArgs() {
    return BuildExecutorArgs.of(
        getConsole(),
        getBuckEventBus(),
        getPlatform(),
        getClock(),
        getCells(),
        getExecutors(),
        getProjectFilesystemFactory(),
        getBuildInfoStoreManager(),
        getArtifactCacheFactory(),
        getRuleKeyConfiguration());
  }

  public CommandRunnerParams withArtifactCacheFactory(ArtifactCacheFactory artifactCacheFactory) {
    if (artifactCacheFactory == getArtifactCacheFactory()) {
      return this;
    }
    return ImmutableCommandRunnerParams.of(
        getConsole(),
        getStdIn(),
        getCells(),
        getWatchman(),
        getVersionedTargetGraphCache(),
        artifactCacheFactory,
        getTypeCoercerFactory(),
        getUnconfiguredBuildTargetFactory(),
        getTargetConfiguration(),
        getHostConfiguration(),
        getTargetConfigurationSerializer(),
        getParser(),
        getBuckEventBus(),
        getPlatform(),
        getEnvironment(),
        getJavaPackageFinder(),
        getClock(),
        getVersionControlStatsGenerator(),
        getProcessManager(),
        getWebServer(),
        getPersistentWorkerPools(),
        getBuckConfig(),
        getFileHashCache(),
        getExecutors(),
        getScheduledExecutor(),
        getBuildEnvironmentDescription(),
        getActionGraphProvider(),
        getKnownRuleTypesProvider(),
        getBuildInfoStoreManager(),
        getInvocationInfo(),
        getDefaultRuleKeyFactoryCacheRecycler(),
        getProjectFilesystemFactory(),
        getRuleKeyConfiguration(),
        getProcessExecutor(),
        getExecutableFinder(),
        getPluginManager(),
        getBuckModuleManager(),
        getDepsAwareExecutorSupplier(),
        getMetadataProvider(),
        getGlobalState(),
        getClientWorkingDir());
  }

  public CommandRunnerParams withBuckConfig(BuckConfig buckConfig) {
    if (buckConfig == getBuckConfig()) {
      return this;
    }
    return ImmutableCommandRunnerParams.of(
        getConsole(),
        getStdIn(),
        getCells(),
        getWatchman(),
        getVersionedTargetGraphCache(),
        getArtifactCacheFactory(),
        getTypeCoercerFactory(),
        getUnconfiguredBuildTargetFactory(),
        getTargetConfiguration(),
        getHostConfiguration(),
        getTargetConfigurationSerializer(),
        getParser(),
        getBuckEventBus(),
        getPlatform(),
        getEnvironment(),
        getJavaPackageFinder(),
        getClock(),
        getVersionControlStatsGenerator(),
        getProcessManager(),
        getWebServer(),
        getPersistentWorkerPools(),
        buckConfig,
        getFileHashCache(),
        getExecutors(),
        getScheduledExecutor(),
        getBuildEnvironmentDescription(),
        getActionGraphProvider(),
        getKnownRuleTypesProvider(),
        getBuildInfoStoreManager(),
        getInvocationInfo(),
        getDefaultRuleKeyFactoryCacheRecycler(),
        getProjectFilesystemFactory(),
        getRuleKeyConfiguration(),
        getProcessExecutor(),
        getExecutableFinder(),
        getPluginManager(),
        getBuckModuleManager(),
        getDepsAwareExecutorSupplier(),
        getMetadataProvider(),
        getGlobalState(),
        getClientWorkingDir());
  }
}
