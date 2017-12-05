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
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.plugin.BuckPluginManagerFactory;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.BuildInfoStoreManager;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultKnownBuildRuleTypesFactory;
import com.facebook.buck.rules.KnownBuildRuleTypesProvider;
import com.facebook.buck.rules.RelativeCellName;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.TestRuleKeyConfigurationFactory;
import com.facebook.buck.sandbox.TestSandboxExecutionStrategyFactory;
import com.facebook.buck.testutil.FakeExecutor;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.versioncontrol.NoOpCmdLineInterface;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.facebook.buck.versions.VersionedTargetGraphCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;
import org.pf4j.PluginManager;

/** Unit test for {@link CleanCommand}. */
public class CleanCommandTest extends EasyMockSupport {

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
    CommandRunnerParams params = createCommandRunnerParams(cleanCommand);

    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getScratchDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getGenDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getTrashDir());

    // Simulate `buck clean`.
    ExitCode exitCode = cleanCommand.run(params);
    assertEquals(ExitCode.SUCCESS, exitCode);

    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getScratchDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getGenDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getTrashDir()));
  }

  @Test
  public void testCleanCommandWithAdditionalPaths()
      throws CmdLineException, IOException, InterruptedException {
    Path additionalPath = projectFilesystem.getPath("foo");
    CleanCommand cleanCommand =
        createCommandFromArgs("-c", "clean.additional_paths=" + additionalPath);
    CommandRunnerParams params = createCommandRunnerParams(cleanCommand);

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

  private CommandRunnerParams createCommandRunnerParams(AbstractCommand command)
      throws InterruptedException, IOException {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(command.getConfigOverrides().getForCell(RelativeCellName.ROOT_CELL_NAME))
            .build();
    Cell cell =
        new TestCellBuilder().setFilesystem(projectFilesystem).setBuckConfig(buckConfig).build();
    return createCommandRunnerParams(buckConfig, cell);
  }

  private CommandRunnerParams createCommandRunnerParams(BuckConfig buckConfig, Cell cell)
      throws InterruptedException, IOException {
    ProcessExecutor processExecutor = new FakeProcessExecutor();

    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();

    return CommandRunnerParams.of(
        new TestConsole(),
        new ByteArrayInputStream("".getBytes("UTF-8")),
        cell,
        new VersionedTargetGraphCache(),
        new SingletonArtifactCacheFactory(new NoopArtifactCache()),
        createMock(TypeCoercerFactory.class),
        createMock(Parser.class),
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
        KnownBuildRuleTypesProvider.of(
            DefaultKnownBuildRuleTypesFactory.of(
                processExecutor, pluginManager, new TestSandboxExecutionStrategyFactory())),
        new BuildInfoStoreManager(),
        Optional.empty(),
        Optional.empty(),
        new DefaultProjectFilesystemFactory(),
        TestRuleKeyConfigurationFactory.create(),
        processExecutor,
        new ExecutableFinder(),
        pluginManager);
  }
}
