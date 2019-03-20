/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.cli;

import com.facebook.buck.artifact_cache.ArtifactCacheFactory;
import com.facebook.buck.command.BuildExecutorArgs;
import com.facebook.buck.core.build.engine.cache.manager.BuildInfoStoreManager;
import com.facebook.buck.core.build.engine.config.CachingBuildEngineBuckConfig;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProvider;
import com.facebook.buck.core.module.BuckModuleManager;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import org.immutables.value.Value;
import org.pf4j.PluginManager;

@Value.Immutable(builder = false)
@BuckStyleImmutable
public abstract class AbstractCommandRunnerParams {
  @Value.Parameter
  public abstract Console getConsole();

  @Value.Parameter
  public abstract InputStream getStdIn();

  @Value.Parameter
  public abstract Cell getCell();

  @Value.Parameter
  public abstract Watchman getWatchman();

  @Value.Parameter
  public abstract InstrumentedVersionedTargetGraphCache getVersionedTargetGraphCache();

  @Value.Parameter
  public abstract ArtifactCacheFactory getArtifactCacheFactory();

  @Value.Parameter
  public abstract TypeCoercerFactory getTypeCoercerFactory();

  @Value.Parameter
  public abstract UnconfiguredBuildTargetFactory getUnconfiguredBuildTargetFactory();

  @Value.Parameter
  protected abstract Supplier<TargetConfiguration> getTargetConfigurationSupplier();

  @Value.Parameter
  public abstract TargetConfigurationSerializer getTargetConfigurationSerializer();

  @Value.Parameter
  public abstract Parser getParser();

  @Value.Parameter
  public abstract BuckEventBus getBuckEventBus();

  @Value.Parameter
  public abstract Platform getPlatform();

  @Value.Parameter
  public abstract ImmutableMap<String, String> getEnvironment();

  @Value.Parameter
  public abstract JavaPackageFinder getJavaPackageFinder();

  @Value.Parameter
  public abstract Clock getClock();

  @Value.Parameter
  public abstract VersionControlStatsGenerator getVersionControlStatsGenerator();

  @Value.Parameter
  public abstract Optional<ProcessManager> getProcessManager();

  @Value.Parameter
  public abstract Optional<WebServer> getWebServer();

  @Value.Parameter
  public abstract ConcurrentMap<String, WorkerProcessPool> getPersistentWorkerPools();

  @Value.Parameter
  public abstract BuckConfig getBuckConfig();

  @Value.Parameter
  public abstract StackedFileHashCache getFileHashCache();

  @Value.Parameter
  public abstract Map<ExecutorPool, ListeningExecutorService> getExecutors();

  @Value.Parameter
  public abstract ScheduledExecutorService getScheduledExecutor();

  @Value.Parameter
  public abstract BuildEnvironmentDescription getBuildEnvironmentDescription();

  @Value.Parameter
  public abstract ActionGraphProvider getActionGraphProvider();

  @Value.Parameter
  public abstract KnownRuleTypesProvider getKnownRuleTypesProvider();

  @Value.Parameter
  public abstract BuildInfoStoreManager getBuildInfoStoreManager();

  @Value.Parameter
  public abstract Optional<InvocationInfo> getInvocationInfo();

  @Value.Parameter
  public abstract Optional<RuleKeyCacheRecycler<RuleKey>> getDefaultRuleKeyFactoryCacheRecycler();

  @Value.Parameter
  public abstract ProjectFilesystemFactory getProjectFilesystemFactory();

  @Value.Parameter
  public abstract RuleKeyConfiguration getRuleKeyConfiguration();

  @Value.Parameter
  public abstract ProcessExecutor getProcessExecutor();

  @Value.Parameter
  public abstract ExecutableFinder getExecutableFinder();

  @Value.Parameter
  public abstract PluginManager getPluginManager();

  @Value.Parameter
  public abstract BuckModuleManager getBuckModuleManager();

  @Value.Parameter
  public abstract CloseableMemoizedSupplier<ForkJoinPool> getPoolSupplier();

  @Value.Parameter
  public abstract MetadataProvider getMetadataProvider();

  @Value.Parameter
  public abstract ThrowingCloseableMemoizedSupplier<ManifestService, IOException>
      getManifestServiceSupplier();

  /**
   * Create {@link BuildExecutorArgs} using this {@link CommandRunnerParams}.
   *
   * @return New instance of {@link BuildExecutorArgs}.
   */
  public BuildExecutorArgs createBuilderArgs() {
    Optional<ManifestService> manifestService =
        getBuckConfig()
            .getView(CachingBuildEngineBuckConfig.class)
            .getManifestServiceIfEnabled(getManifestServiceSupplier());
    return BuildExecutorArgs.builder()
        .setConsole(getConsole())
        .setBuckEventBus(getBuckEventBus())
        .setPlatform(getPlatform())
        .setClock(getClock())
        .setRootCell(getCell())
        .setExecutors(getExecutors())
        .setProjectFilesystemFactory(getProjectFilesystemFactory())
        .setBuildInfoStoreManager(getBuildInfoStoreManager())
        .setArtifactCacheFactory(getArtifactCacheFactory())
        .setRuleKeyConfiguration(getRuleKeyConfiguration())
        .setManifestService(manifestService)
        .build();
  }

  public TargetConfiguration getTargetConfiguration() {
    return getTargetConfigurationSupplier().get();
  }
}
