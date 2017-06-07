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
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.BuildInfoStoreManager;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.KnownBuildRuleTypesFactory;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.shell.WorkerProcessPool;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.facebook.buck.versions.VersionedTargetGraphCache;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import org.immutables.value.Value;

@Value.Immutable()
@BuckStyleImmutable
public interface AbstractCommandRunnerParams {
  Console getConsole();

  InputStream getStdIn();

  Cell getCell();

  VersionedTargetGraphCache getVersionedTargetGraphCache();

  ArtifactCacheFactory getArtifactCacheFactory();

  TypeCoercerFactory getTypeCoercerFactory();

  Parser getParser();

  BuckEventBus getBuckEventBus();

  Supplier<AndroidPlatformTarget> getAndroidPlatformTargetSupplier();

  Platform getPlatform();

  ImmutableMap<String, String> getEnvironment();

  JavaPackageFinder getJavaPackageFinder();

  Clock getClock();

  VersionControlStatsGenerator getVersionControlStatsGenerator();

  Optional<ProcessManager> getProcessManager();

  Optional<WebServer> getWebServer();

  Optional<ConcurrentMap<String, WorkerProcessPool>> getPersistentWorkerPools();

  BuckConfig getBuckConfig();

  StackedFileHashCache getFileHashCache();

  Map<ExecutorPool, ListeningExecutorService> getExecutors();

  BuildEnvironmentDescription getBuildEnvironmentDescription();

  ActionGraphCache getActionGraphCache();

  KnownBuildRuleTypesFactory getKnownBuildRuleTypesFactory();

  BuildInfoStoreManager getBuildInfoStoreManager();

  Optional<InvocationInfo> getInvocationInfo();

  Optional<RuleKeyCacheRecycler<RuleKey>> getDefaultRuleKeyFactoryCacheRecycler();
}
