/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.artifact_cache.SingletonArtifactCacheFactory;
import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.artifact_cache.config.DirCacheEntry;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.build.engine.cache.manager.BuildInfoStoreManager;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.name.RelativeCellName;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphCache;
import com.facebook.buck.core.rules.knowntypes.DefaultKnownBuildRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesProvider;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.module.TestBuckModuleManagerFactory;
import com.facebook.buck.parser.DefaultParser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.TargetSpecResolver;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.sandbox.TestSandboxExecutionStrategyFactory;
import com.facebook.buck.testutil.FakeExecutor;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.cache.NoOpCacheStatsTracker;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.versioncontrol.NoOpCmdLineInterface;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.facebook.buck.versions.InstrumentedVersionedTargetGraphCache;
import com.facebook.buck.versions.VersionedTargetGraphCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;
import org.pf4j.PluginManager;

/** Unit test for {@link CleanCommand}. */
public class CleanCommandTest {

  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() {
    projectFilesystem = new FakeProjectFilesystem();
  }

  // TODO(mbolin): When it is possible to inject a mock object for stderr,
  // create a test that runs `buck clean unexpectedarg` and verify that the
  // exit code is 1 and that the appropriate error message is printed.

  @Test
  public void testCleanCommandNoArguments()
      throws CmdLineException, IOException, InterruptedException {
    CleanCommand cleanCommand = createCommandFromArgs();
    CommandRunnerParams params = createCommandRunnerParams(cleanCommand, true);

    ArtifactCacheBuckConfig artifactCacheBuckConfig =
        ArtifactCacheBuckConfig.of(params.getBuckConfig());
    ImmutableSet<DirCacheEntry> dirCacheEntries =
        artifactCacheBuckConfig.getCacheEntries().getDirCacheEntries();

    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getScratchDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getGenDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getTrashDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getCacheDir());
    // Create a "local" cache directory.
    for (DirCacheEntry dirCacheEntry : dirCacheEntries) {
      projectFilesystem.mkdirs(dirCacheEntry.getCacheDir());
    }

    // Simulate `buck clean`.
    ExitCode exitCode = cleanCommand.run(params);
    assertEquals(ExitCode.SUCCESS, exitCode);

    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getScratchDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getGenDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getTrashDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getCacheDir()));
    for (DirCacheEntry dirCacheEntry : dirCacheEntries) {
      assertFalse(projectFilesystem.exists(dirCacheEntry.getCacheDir()));
    }
  }

  @Test
  public void testCleanCommandWithKeepCache()
      throws CmdLineException, IOException, InterruptedException {
    CleanCommand cleanCommand = createCommandFromArgs("--keep-cache");
    CommandRunnerParams params = createCommandRunnerParams(cleanCommand, true);

    ArtifactCacheBuckConfig artifactCacheBuckConfig =
        ArtifactCacheBuckConfig.of(params.getBuckConfig());
    ImmutableSet<DirCacheEntry> dirCacheEntries =
        artifactCacheBuckConfig.getCacheEntries().getDirCacheEntries();

    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getScratchDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getGenDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getTrashDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getCacheDir());
    // Create a "local" cache directory.
    for (DirCacheEntry dirCacheEntry : dirCacheEntries) {
      projectFilesystem.mkdirs(dirCacheEntry.getCacheDir());
    }

    // Simulate `buck clean`.
    ExitCode exitCode = cleanCommand.run(params);
    assertEquals(ExitCode.SUCCESS, exitCode);

    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getScratchDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getGenDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getTrashDir()));
    assertTrue(projectFilesystem.exists(projectFilesystem.getBuckPaths().getCacheDir()));
    for (DirCacheEntry dirCacheEntry : dirCacheEntries) {
      assertTrue(projectFilesystem.exists(dirCacheEntry.getCacheDir()));
    }
  }

  @Test
  public void testCleanCommandExcludeLocalCache()
      throws CmdLineException, IOException, InterruptedException {
    String cacheToKeep = "warmtestcache";
    CleanCommand cleanCommand =
        createCommandFromArgs("-c", "clean.excluded_dir_caches=" + cacheToKeep);
    CommandRunnerParams params = createCommandRunnerParams(cleanCommand, true);

    ArtifactCacheBuckConfig artifactCacheBuckConfig =
        ArtifactCacheBuckConfig.of(params.getBuckConfig());
    ImmutableSet<DirCacheEntry> dirCacheEntries =
        artifactCacheBuckConfig.getCacheEntries().getDirCacheEntries();

    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getScratchDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getGenDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getTrashDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getCacheDir());

    // Create the local caches.
    for (DirCacheEntry dirCacheEntry : dirCacheEntries) {
      projectFilesystem.mkdirs(dirCacheEntry.getCacheDir());
    }

    // Simulate `buck clean`.
    ExitCode exitCode = cleanCommand.run(params);
    assertEquals(ExitCode.SUCCESS, exitCode);

    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getScratchDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getGenDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getTrashDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getCacheDir()));
    for (DirCacheEntry dirCacheEntry : dirCacheEntries) {
      if (dirCacheEntry.getName().get().equals(cacheToKeep)) {
        assertTrue(projectFilesystem.exists(dirCacheEntry.getCacheDir()));
      } else {
        assertFalse(projectFilesystem.exists(dirCacheEntry.getCacheDir()));
      }
    }
  }

  @Test
  public void testCleanCommandWithDryRun()
      throws CmdLineException, IOException, InterruptedException {
    CleanCommand cleanCommand = createCommandFromArgs("--dry-run");
    CommandRunnerParams params = createCommandRunnerParams(cleanCommand, true);

    ArtifactCacheBuckConfig artifactCacheBuckConfig =
        ArtifactCacheBuckConfig.of(params.getBuckConfig());
    ImmutableSet<DirCacheEntry> dirCacheEntries =
        artifactCacheBuckConfig.getCacheEntries().getDirCacheEntries();

    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getScratchDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getGenDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getTrashDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getCacheDir());
    // Create a "local" cache directory.
    for (DirCacheEntry dirCacheEntry : dirCacheEntries) {
      projectFilesystem.mkdirs(dirCacheEntry.getCacheDir());
    }

    // Simulate `buck clean`.
    ExitCode exitCode = cleanCommand.run(params);
    assertEquals(ExitCode.SUCCESS, exitCode);

    assertTrue(projectFilesystem.exists(projectFilesystem.getBuckPaths().getScratchDir()));
    assertTrue(projectFilesystem.exists(projectFilesystem.getBuckPaths().getGenDir()));
    assertTrue(projectFilesystem.exists(projectFilesystem.getBuckPaths().getTrashDir()));
    assertTrue(projectFilesystem.exists(projectFilesystem.getBuckPaths().getCacheDir()));
    for (DirCacheEntry dirCacheEntry : dirCacheEntries) {
      assertTrue(projectFilesystem.exists(dirCacheEntry.getCacheDir()));
    }
  }

  @Test
  public void testCleanCommandWithAdditionalPaths()
      throws CmdLineException, IOException, InterruptedException {
    Path additionalPath = projectFilesystem.getPath("foo");
    CleanCommand cleanCommand =
        createCommandFromArgs("-c", "clean.additional_paths=" + additionalPath);
    CommandRunnerParams params = createCommandRunnerParams(cleanCommand, false);

    // Set up mocks.
    projectFilesystem.mkdirs(additionalPath);
    assertTrue(projectFilesystem.exists(additionalPath));

    // Simulate `buck clean --project`.
    ExitCode exitCode = cleanCommand.run(params);
    assertEquals(ExitCode.SUCCESS, exitCode);

    assertFalse(projectFilesystem.exists(additionalPath));
  }

  private CleanCommand createCommandFromArgs(String... args) throws CmdLineException {
    CleanCommand command = new CleanCommand();
    new AdditionalOptionsCmdLineParser(command).parseArgument(args);
    return command;
  }

  private CommandRunnerParams createCommandRunnerParams(
      AbstractCommand command, boolean enableCacheSection)
      throws InterruptedException, IOException {
    FakeBuckConfig.Builder buckConfigBuilder = FakeBuckConfig.builder();

    if (enableCacheSection) {
      ImmutableMap.Builder<String, ImmutableMap<String, String>> mergeConfigBuilder =
          ImmutableMap.builder();
      mergeConfigBuilder.putAll(
          command.getConfigOverrides().getForCell(RelativeCellName.ROOT_CELL_NAME).getValues());
      mergeConfigBuilder.put(
          "cache", ImmutableMap.of("dir_cache_names", "testcache, warmtestcache"));
      mergeConfigBuilder.put(
          "cache#testcache", ImmutableMap.of("dir", "~/dir-cache", "dir_mode", "readonly"));
      mergeConfigBuilder.put(
          "cache#warmtestcache",
          ImmutableMap.of("dir", "~/warm-dir-cache", "dir_mode", "readonly"));
      buckConfigBuilder.setSections(mergeConfigBuilder.build());
    } else {
      buckConfigBuilder.setSections(
          command.getConfigOverrides().getForCell(RelativeCellName.ROOT_CELL_NAME));
    }
    BuckConfig buckConfig = buckConfigBuilder.build();
    Cell cell =
        new TestCellBuilder().setFilesystem(projectFilesystem).setBuckConfig(buckConfig).build();
    return createCommandRunnerParams(buckConfig, cell);
  }

  private CommandRunnerParams createCommandRunnerParams(BuckConfig buckConfig, Cell cell) {
    ProcessExecutor processExecutor = new FakeProcessExecutor();

    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    KnownBuildRuleTypesProvider knownBuildRuleTypesProvider =
        KnownBuildRuleTypesProvider.of(
            DefaultKnownBuildRuleTypesFactory.of(
                processExecutor, pluginManager, new TestSandboxExecutionStrategyFactory()));
    ExecutableFinder executableFinder = new ExecutableFinder();

    return CommandRunnerParams.of(
        new TestConsole(),
        new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)),
        cell,
        new InstrumentedVersionedTargetGraphCache(
            new VersionedTargetGraphCache(), new NoOpCacheStatsTracker()),
        new SingletonArtifactCacheFactory(new NoopArtifactCache()),
        typeCoercerFactory,
        new DefaultParser(
            buckConfig.getView(ParserConfig.class),
            typeCoercerFactory,
            new ConstructorArgMarshaller(typeCoercerFactory),
            knownBuildRuleTypesProvider,
            executableFinder,
            new TargetSpecResolver()),
        BuckEventBusForTests.newInstance(),
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()),
        new FakeJavaPackageFinder(),
        new DefaultClock(),
        new VersionControlStatsGenerator(new NoOpCmdLineInterface(), Optional.empty()),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        buckConfig,
        new StackedFileHashCache(ImmutableList.of()),
        ImmutableMap.of(),
        new FakeExecutor(),
        CommandRunnerParamsForTesting.BUILD_ENVIRONMENT_DESCRIPTION,
        new ActionGraphCache(buckConfig.getMaxActionGraphCacheEntries()),
        knownBuildRuleTypesProvider,
        new BuildInfoStoreManager(),
        Optional.empty(),
        Optional.empty(),
        new DefaultProjectFilesystemFactory(),
        TestRuleKeyConfigurationFactory.create(),
        processExecutor,
        executableFinder,
        pluginManager,
        TestBuckModuleManagerFactory.create(pluginManager),
        Main.getForkJoinPoolSupplier(buckConfig));
  }
}
