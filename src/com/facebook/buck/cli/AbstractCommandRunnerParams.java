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

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.artifact_cache.ArtifactCacheFactory;
import com.facebook.buck.command.BuildExecutorArgs;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.BuildInfoStoreManager;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.KnownBuildRuleTypesProvider;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.RuleKeyConfiguration;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.facebook.buck.versions.VersionedTargetGraphCache;
import com.facebook.buck.worker.WorkerProcessPool;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import org.immutables.value.Value;

@Value.Immutable(copy = true)
@BuckStyleImmutable
public abstract class AbstractCommandRunnerParams {
  public abstract Console getConsole();

  public abstract InputStream getStdIn();

  public abstract Cell getCell();

  public abstract VersionedTargetGraphCache getVersionedTargetGraphCache();

  public abstract ArtifactCacheFactory getArtifactCacheFactory();

  public abstract TypeCoercerFactory getTypeCoercerFactory();

  public abstract Parser getParser();

  public abstract BuckEventBus getBuckEventBus();

  public abstract Supplier<AndroidPlatformTarget> getAndroidPlatformTargetSupplier();

  public abstract Platform getPlatform();

  public abstract ImmutableMap<String, String> getEnvironment();

  public abstract JavaPackageFinder getJavaPackageFinder();

  public abstract Clock getClock();

  public abstract VersionControlStatsGenerator getVersionControlStatsGenerator();

  public abstract Optional<ProcessManager> getProcessManager();

  public abstract Optional<WebServer> getWebServer();

  public abstract Optional<ConcurrentMap<String, WorkerProcessPool>> getPersistentWorkerPools();

  public abstract BuckConfig getBuckConfig();

  public abstract StackedFileHashCache getFileHashCache();

  public abstract Map<ExecutorPool, ListeningExecutorService> getExecutors();

  public abstract ScheduledExecutorService getScheduledExecutor();

  public abstract BuildEnvironmentDescription getBuildEnvironmentDescription();

  public abstract ActionGraphCache getActionGraphCache();

  public abstract KnownBuildRuleTypesProvider getKnownBuildRuleTypesProvider();

  public abstract BuildInfoStoreManager getBuildInfoStoreManager();

  public abstract Optional<InvocationInfo> getInvocationInfo();

  public abstract Optional<RuleKeyCacheRecycler<RuleKey>> getDefaultRuleKeyFactoryCacheRecycler();

  public abstract ProjectFilesystemFactory getProjectFilesystemFactory();

  public abstract RuleKeyConfiguration getRuleKeyConfiguration();

  public abstract ProcessExecutor getProcessExecutor();

  /**
   * Create {@link BuildExecutorArgs} using this {@link CommandRunnerParams}.
   *
   * @return New instance of {@link BuildExecutorArgs}.
   */
  public BuildExecutorArgs createBuilderArgs() {
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
        .build();
  }
}
