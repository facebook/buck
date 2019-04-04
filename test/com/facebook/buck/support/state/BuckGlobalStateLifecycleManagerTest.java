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

package com.facebook.buck.support.state;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.model.impl.JsonTargetConfigurationSerializer;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.watchman.FakeWatchmanClient;
import com.facebook.buck.io.watchman.FakeWatchmanFactory;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.support.bgtasks.TestBackgroundTaskManager;
import com.facebook.buck.support.state.BuckGlobalStateLifecycleManager.LifecycleStatus;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.FakeClock;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Map.Entry;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.pf4j.PluginManager;

public class BuckGlobalStateLifecycleManagerTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;
  private BuckGlobalStateLifecycleManager buckGlobalStateLifecycleManager;
  private KnownRuleTypesProvider knownRuleTypesProvider;
  private BuckConfig buckConfig;
  private Clock clock;
  private PluginManager pluginManager;
  private WatchmanClient watchmanClient;
  private Watchman watchman;
  private UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory;
  private TargetConfigurationSerializer targetConfigurationSerializer;
  private Supplier<BackgroundTaskManager> backgroundTaskManagerFactory;

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    buckConfig = FakeBuckConfig.builder().build();
    buckGlobalStateLifecycleManager = new BuckGlobalStateLifecycleManager();
    pluginManager = BuckPluginManagerFactory.createPluginManager();
    knownRuleTypesProvider = TestKnownRuleTypesProvider.create(pluginManager);
    clock = FakeClock.doNotCare();
    watchmanClient = new FakeWatchmanClient(0, ImmutableMap.of());
    watchman =
        FakeWatchmanFactory.createWatchman(
            watchmanClient, filesystem.getRootPath(), filesystem.getPath(""), "watch");
    unconfiguredBuildTargetFactory = new ParsingUnconfiguredBuildTargetFactory();
    CellPathResolver cellPathResolver = TestCellPathResolver.get(filesystem);
    targetConfigurationSerializer =
        new JsonTargetConfigurationSerializer(
            buildTarget -> unconfiguredBuildTargetFactory.create(cellPathResolver, buildTarget));
    backgroundTaskManagerFactory = TestBackgroundTaskManager::new;
  }

  @Test
  public void whenBuckConfigChangesParserInvalidated() {
    buckGlobalStateLifecycleManager.resetBuckGlobalState();
    Pair<BuckGlobalState, LifecycleStatus> buckStateResult1 =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "somesection", ImmutableMap.of("somename", "somevalue")))
                        .build())
                .setFilesystem(filesystem)
                .build(),
            knownRuleTypesProvider,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer,
            backgroundTaskManagerFactory);

    Pair<BuckGlobalState, LifecycleStatus> buckStateResult2 =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "somesection", ImmutableMap.of("somename", "somevalue")))
                        .build())
                .setFilesystem(filesystem)
                .build(),
            knownRuleTypesProvider,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer,
            backgroundTaskManagerFactory);

    Pair<BuckGlobalState, LifecycleStatus> buckStateResult3 =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "somesection", ImmutableMap.of("somename", "someothervalue")))
                        .build())
                .setFilesystem(filesystem)
                .build(),
            knownRuleTypesProvider,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer,
            backgroundTaskManagerFactory);

    assertEquals(
        "Daemon should not be replaced when config equal.",
        buckStateResult1.getFirst(),
        buckStateResult2.getFirst());

    assertNotEquals(
        "Daemon should be replaced when config not equal.",
        buckStateResult1.getFirst(),
        buckStateResult3.getFirst());

    assertEquals(LifecycleStatus.NEW, buckStateResult1.getSecond());
    assertEquals(LifecycleStatus.REUSED, buckStateResult2.getSecond());
    assertEquals(LifecycleStatus.INVALIDATED_BUCK_CONFIG_CHANGED, buckStateResult3.getSecond());
  }

  @Test
  public void whenAndroidNdkVersionChangesParserInvalidated() {

    BuckConfig buckConfig1 =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("ndk", ImmutableMap.of("ndk_version", "something")))
            .build();

    BuckConfig buckConfig2 =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("ndk", ImmutableMap.of("ndk_version", "different")))
            .build();

    Object buckGlobalState =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            new TestCellBuilder().setBuckConfig(buckConfig1).setFilesystem(filesystem).build(),
            knownRuleTypesProvider,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer,
            backgroundTaskManagerFactory);

    assertNotEquals(
        "Daemon state should be replaced when not equal.",
        buckGlobalState,
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            new TestCellBuilder().setBuckConfig(buckConfig2).setFilesystem(filesystem).build(),
            knownRuleTypesProvider,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer,
            backgroundTaskManagerFactory));
  }

  @Test
  public void testAppleSdkChangesParserInvalidated() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    BuckConfig buckConfig = FakeBuckConfig.builder().build();

    Object buckGlobalState1 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build(),
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    Object buckGlobalState2 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build(),
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();
    assertEquals("Apple SDK should still be not found", buckGlobalState1, buckGlobalState2);

    Path appleDeveloperDirectoryPath = tmp.newFolder("android-sdk").toAbsolutePath();

    BuckConfig buckConfigWithDeveloperDirectory =
        FakeBuckConfig.builder()
            .setSections(
                "[apple]", "xcode_developer_dir = " + appleDeveloperDirectoryPath.toAbsolutePath())
            .build();

    Object buckGlobalState3 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder()
                    .setBuckConfig(buckConfigWithDeveloperDirectory)
                    .setFilesystem(filesystem)
                    .build(),
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();
    assertNotEquals("Apple SDK should be found", buckGlobalState2, buckGlobalState3);

    Object buckGlobalState4 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder()
                    .setBuckConfig(buckConfigWithDeveloperDirectory)
                    .setFilesystem(filesystem)
                    .build(),
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();
    assertEquals("Apple SDK should still be found", buckGlobalState3, buckGlobalState4);
  }

  @Test
  public void testAndroidSdkChangesParserInvalidated() throws IOException {
    // Disable the test on Windows for now since it's failing to find python.
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    Builder<Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("xcode-select", "--print-path"))
            .build();
    // First KnownBuildRuleTypes resolution.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // Check SDK.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // Check SDK.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // KnownBuildRuleTypes resolution.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // Check SDK.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));

    Object buckGlobalState1 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build(),
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();
    Object buckGlobalState2 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build(),
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();
    assertEquals(
        "Android SDK should be the same initial location", buckGlobalState1, buckGlobalState2);

    Path androidSdkPath = tmp.newFolder("android-sdk").toAbsolutePath();

    Cell cell = createCellWithAndroidSdk(androidSdkPath);

    Object buckGlobalState3 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    assertEquals("Daemon should not be re-created", buckGlobalState2, buckGlobalState3);
    Object buckGlobalState4 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    assertEquals(
        "Android SDK should be the same other location", buckGlobalState3, buckGlobalState4);
  }

  @Test
  public void testAndroidSdkChangesParserInvalidatedWhenToolchainsPresent() throws IOException {
    // Disable the test on Windows for now since it's failing to find python.
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    Builder<Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("xcode-select", "--print-path"))
            .build();
    // First KnownBuildRuleTypes resolution.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // Check SDK.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // Check SDK.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // KnownBuildRuleTypes resolution.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // Check SDK.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));

    Object buckGlobalState1 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build(),
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();
    Object buckGlobalState2 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build(),
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();
    assertEquals(
        "Android SDK should be the same initial location", buckGlobalState1, buckGlobalState2);

    Path androidSdkPath = tmp.newFolder("android-sdk").toAbsolutePath();

    Cell cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getToolchainProvider().getByName(AndroidSdkLocation.DEFAULT_NAME);

    Object buckGlobalState3 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    assertNotEquals("Android SDK should be the other location", buckGlobalState2, buckGlobalState3);
    Object buckGlobalState4 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    assertEquals(
        "Android SDK should be the same other location", buckGlobalState3, buckGlobalState4);
  }

  private Cell createCellWithAndroidSdk(Path androidSdkPath) {
    return new TestCellBuilder()
        .setBuckConfig(buckConfig)
        .setFilesystem(filesystem)
        .addEnvironmentVariable("ANDROID_HOME", androidSdkPath.toString())
        .addEnvironmentVariable("ANDROID_SDK", androidSdkPath.toString())
        .build();
  }

  @Test
  public void testParserInvalidatedWhenToolchainFailsToCreateFirstTime() throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    Path androidSdkPath = tmp.newFolder("android-sdk").toAbsolutePath();
    Files.deleteIfExists(androidSdkPath);

    Cell cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getToolchainProvider()
        .getByNameIfPresent(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class);
    BuckGlobalState buckGlobalStateWithBrokenAndroidSdk =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    tmp.newFolder("android-sdk");

    cell = createCellWithAndroidSdk(androidSdkPath);
    BuckGlobalState buckGlobalStateWithWorkingAndroidSdk =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    assertNotEquals(buckGlobalStateWithBrokenAndroidSdk, buckGlobalStateWithWorkingAndroidSdk);
  }

  @Test
  public void testParserInvalidatedWhenToolchainFailsToCreateAfterFirstCreation()
      throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    Path androidSdkPath = tmp.newFolder("android-sdk").toAbsolutePath();

    Cell cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getToolchainProvider()
        .getByNameIfPresent(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class);
    BuckGlobalState buckGlobalStateWithWorkingAndroidSdk =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    Files.deleteIfExists(androidSdkPath);

    cell = createCellWithAndroidSdk(androidSdkPath);
    BuckGlobalState buckGlobalStateWithBrokenAndroidSdk =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    assertNotEquals(buckGlobalStateWithWorkingAndroidSdk, buckGlobalStateWithBrokenAndroidSdk);
  }

  @Test
  public void testParserNotInvalidatedWhenToolchainFailsWithTheSameProblem() throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    Path androidSdkPath = tmp.newFolder("android-sdk").toAbsolutePath();
    Files.deleteIfExists(androidSdkPath);

    Cell cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getToolchainProvider()
        .getByNameIfPresent(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class);
    BuckGlobalState buckGlobalStateWithBrokenAndroidSdk1 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getToolchainProvider()
        .getByNameIfPresent(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class);
    BuckGlobalState buckGlobalStateWithBrokenAndroidSdk2 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    assertEquals(buckGlobalStateWithBrokenAndroidSdk1, buckGlobalStateWithBrokenAndroidSdk2);
  }

  @Test
  public void testParserNotInvalidatedWhenToolchainFailsWithTheSameProblemButNotInstantiated()
      throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    Path androidSdkPath = tmp.newFolder("android-sdk").toAbsolutePath();
    Files.deleteIfExists(androidSdkPath);

    Cell cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getToolchainProvider()
        .getByNameIfPresent(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class);
    BuckGlobalState buckGlobalStateWithBrokenAndroidSdk1 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    cell = createCellWithAndroidSdk(androidSdkPath);
    BuckGlobalState buckGlobalStateWithBrokenAndroidSdk2 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    assertEquals(buckGlobalStateWithBrokenAndroidSdk1, buckGlobalStateWithBrokenAndroidSdk2);
  }

  @Test
  public void testParserInvalidatedWhenToolchainFailsWithDifferentProblem() throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    Path androidSdkPath = tmp.newFolder("android-sdk").toAbsolutePath();
    Files.deleteIfExists(androidSdkPath);

    Cell cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getToolchainProvider()
        .getByNameIfPresent(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class);
    Object buckGlobalStateWithBrokenAndroidSdk1 =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            cell,
            knownRuleTypesProvider,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer,
            backgroundTaskManagerFactory);

    cell = createCellWithAndroidSdk(androidSdkPath.resolve("some-other-dir"));
    Object buckGlobalStateWithBrokenAndroidSdk2 =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            cell,
            knownRuleTypesProvider,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer,
            backgroundTaskManagerFactory);

    assertNotEquals(buckGlobalStateWithBrokenAndroidSdk1, buckGlobalStateWithBrokenAndroidSdk2);
  }

  @Test
  public void testBuckGlobalStateUptime() {
    Cell cell = new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build();
    SettableFakeClock clock = new SettableFakeClock(1000, 0);
    BuckGlobalState buckGlobalState =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProvider,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer,
                backgroundTaskManagerFactory)
            .getFirst();

    assertEquals(buckGlobalState.getUptime(), 0);
    clock.setCurrentTimeMillis(2000);
    assertEquals(buckGlobalState.getUptime(), 1000);
  }
}
