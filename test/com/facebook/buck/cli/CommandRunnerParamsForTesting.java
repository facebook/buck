/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.artifact_cache.SingletonArtifactCacheFactory;
import com.facebook.buck.core.build.engine.cache.manager.BuildInfoStoreManager;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProviderBuilder;
import com.facebook.buck.core.module.TestBuckModuleManagerFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.parser.TestParserFactory;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.testutil.FakeExecutor;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.facebook.buck.util.cache.NoOpCacheStatsTracker;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.versioncontrol.NoOpCmdLineInterface;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.facebook.buck.versions.InstrumentedVersionedTargetGraphCache;
import com.facebook.buck.versions.VersionedTargetGraphCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.annotation.Nullable;
import org.pf4j.PluginManager;

public class CommandRunnerParamsForTesting {

  public static final BuildEnvironmentDescription BUILD_ENVIRONMENT_DESCRIPTION =
      BuildEnvironmentDescription.builder()
          .setUser("test")
          .setHostname("test")
          .setOs("test")
          .setAvailableCores(1)
          .setSystemMemory(1024L)
          .setBuckDirty(Optional.of(false))
          .setBuckCommit("test")
          .setJavaVersion("test")
          .setJsonProtocolVersion(1)
          .build();

  /** Utility class: do not instantiate. */
  private CommandRunnerParamsForTesting() {}

  private static ThrowingCloseableMemoizedSupplier<ManifestService, IOException>
      getManifestSupplier() {
    return ThrowingCloseableMemoizedSupplier.of(() -> null, ManifestService::close);
  }

  public static CommandRunnerParams createCommandRunnerParamsForTesting(
      Console console,
      Cell cell,
      ArtifactCache artifactCache,
      BuckEventBus eventBus,
      BuckConfig config,
      Platform platform,
      ImmutableMap<String, String> environment,
      JavaPackageFinder javaPackageFinder,
      Optional<WebServer> webServer) {
    ProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());
    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(pluginManager);

    return CommandRunnerParams.of(
        console,
        new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)),
        cell,
        WatchmanFactory.NULL_WATCHMAN,
        new InstrumentedVersionedTargetGraphCache(
            new VersionedTargetGraphCache(), new NoOpCacheStatsTracker()),
        new SingletonArtifactCacheFactory(artifactCache),
        typeCoercerFactory,
        TestParserFactory.create(cell.getBuckConfig(), knownRuleTypesProvider),
        eventBus,
        platform,
        environment,
        javaPackageFinder,
        new DefaultClock(),
        new VersionControlStatsGenerator(new NoOpCmdLineInterface(), Optional.empty()),
        Optional.empty(),
        webServer,
        Optional.empty(),
        config,
        new StackedFileHashCache(ImmutableList.of()),
        ImmutableMap.of(ExecutorPool.PROJECT, MoreExecutors.newDirectExecutorService()),
        new FakeExecutor(),
        BUILD_ENVIRONMENT_DESCRIPTION,
        new ActionGraphProviderBuilder()
            .withMaxEntries(config.getMaxActionGraphCacheEntries())
            .withPoolSupplier(Main.getForkJoinPoolSupplier(config))
            .build(),
        knownRuleTypesProvider,
        new BuildInfoStoreManager(),
        Optional.empty(),
        Optional.empty(),
        new DefaultProjectFilesystemFactory(),
        TestRuleKeyConfigurationFactory.create(),
        processExecutor,
        new ExecutableFinder(),
        pluginManager,
        TestBuckModuleManagerFactory.create(pluginManager),
        Main.getForkJoinPoolSupplier(config),
        Optional.empty(),
        getManifestSupplier());
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private ArtifactCache artifactCache = new NoopArtifactCache();
    private Console console = new TestConsole();
    private BuckConfig config = FakeBuckConfig.builder().build();
    private BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    private Platform platform = Platform.detect();
    private ImmutableMap<String, String> environment = EnvVariablesProvider.getSystemEnv();
    private JavaPackageFinder javaPackageFinder = new FakeJavaPackageFinder();
    private Optional<WebServer> webServer = Optional.empty();
    @Nullable private ToolchainProvider toolchainProvider = null;

    public CommandRunnerParams build() {
      TestCellBuilder cellBuilder = new TestCellBuilder();
      if (toolchainProvider != null) {
        cellBuilder.setToolchainProvider(toolchainProvider);
      }

      return createCommandRunnerParamsForTesting(
          console,
          cellBuilder.build(),
          artifactCache,
          eventBus,
          config,
          platform,
          environment,
          javaPackageFinder,
          webServer);
    }

    public Builder setConsole(Console console) {
      this.console = console;
      return this;
    }

    public Builder setWebserver(Optional<WebServer> webServer) {
      this.webServer = webServer;
      return this;
    }

    public Builder setArtifactCache(ArtifactCache cache) {
      this.artifactCache = cache;
      return this;
    }

    public Builder setToolchainProvider(ToolchainProvider toolchainProvider) {
      this.toolchainProvider = toolchainProvider;
      return this;
    }
  }
}
