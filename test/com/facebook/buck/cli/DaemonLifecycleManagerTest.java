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

package com.facebook.buck.cli;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.KnownBuildRuleTypesFactory;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DaemonLifecycleManagerTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;
  private DaemonLifecycleManager daemonLifecycleManager;

  @Before
  public void setUp() throws InterruptedException {
    filesystem = new ProjectFilesystem(tmp.getRoot());
    daemonLifecycleManager = new DaemonLifecycleManager();
  }

  @Test
  public void whenBuckConfigChangesParserInvalidated() throws IOException, InterruptedException {
    Object daemon =
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "somesection", ImmutableMap.of("somename", "somevalue")))
                        .build())
                .setFilesystem(filesystem)
                .build());

    assertEquals(
        "Daemon should not be replaced when config equal.",
        daemon,
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "somesection", ImmutableMap.of("somename", "somevalue")))
                        .build())
                .setFilesystem(filesystem)
                .build()));

    assertNotEquals(
        "Daemon should be replaced when config not equal.",
        daemon,
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "somesection", ImmutableMap.of("somename", "someothervalue")))
                        .build())
                .setFilesystem(filesystem)
                .build()));
  }

  @Test
  public void whenAndroidNdkVersionChangesParserInvalidated()
      throws IOException, InterruptedException {

    BuckConfig buckConfig1 =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("ndk", ImmutableMap.of("ndk_version", "something")))
            .build();

    BuckConfig buckConfig2 =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("ndk", ImmutableMap.of("ndk_version", "different")))
            .build();

    Object daemon =
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder().setBuckConfig(buckConfig1).setFilesystem(filesystem).build());

    assertNotEquals(
        "Daemon should be replaced when not equal.",
        daemon,
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder().setBuckConfig(buckConfig2).setFilesystem(filesystem).build()));
  }

  @Test
  public void testAppleSdkChangesParserInvalidated() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    Optional<Path> appleDeveloperDirectory = getAppleDeveloperDir(buckConfig);
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
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
        new SimpleImmutableEntry<>(
            processExecutorParams,
            new FakeProcess(0, appleDeveloperDirectory.get().toString(), "")));
    // KnownBuildRuleTypes resolution.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            processExecutorParams,
            new FakeProcess(0, appleDeveloperDirectory.get().toString(), "")));
    // Check SDK.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            processExecutorParams,
            new FakeProcess(0, appleDeveloperDirectory.get().toString(), "")));
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(fakeProcessesBuilder.build());

    KnownBuildRuleTypesFactory factory =
        new KnownBuildRuleTypesFactory(fakeProcessExecutor, new FakeAndroidDirectoryResolver());

    Object daemon1 =
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(buckConfig)
                .setFilesystem(filesystem)
                .setKnownBuildRuleTypesFactory(factory)
                .build());
    Object daemon2 =
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(buckConfig)
                .setFilesystem(filesystem)
                .setKnownBuildRuleTypesFactory(factory)
                .build());
    assertEquals("Apple SDK should still be not found", daemon1, daemon2);
    Object daemon3 =
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(buckConfig)
                .setFilesystem(filesystem)
                .setKnownBuildRuleTypesFactory(factory)
                .build());
    assertNotEquals("Apple SDK should be found", daemon2, daemon3);
    Object daemon4 =
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(buckConfig)
                .setFilesystem(filesystem)
                .setKnownBuildRuleTypesFactory(factory)
                .build());
    assertEquals("Apple SDK should still be found", daemon3, daemon4);
  }

  @Test
  public void testAndroidSdkChangesParserInvalidated() throws IOException, InterruptedException {
    // Disable the test on Windows for now since it's failing to find python.
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
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
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(fakeProcessesBuilder.build());

    FakeAndroidDirectoryResolver androidResolver1 =
        new FakeAndroidDirectoryResolver(
            Optional.of(filesystem.getPath("/path/to/sdkv1")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    KnownBuildRuleTypesFactory factory1 =
        new KnownBuildRuleTypesFactory(fakeProcessExecutor, androidResolver1);
    FakeAndroidDirectoryResolver androidResolver2 =
        new FakeAndroidDirectoryResolver(
            Optional.of(filesystem.getPath("/path/to/sdkv2")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    KnownBuildRuleTypesFactory factory2 =
        new KnownBuildRuleTypesFactory(fakeProcessExecutor, androidResolver2);

    Object daemon1 =
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(buckConfig)
                .setFilesystem(filesystem)
                .setKnownBuildRuleTypesFactory(factory1)
                .build());
    Object daemon2 =
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(buckConfig)
                .setFilesystem(filesystem)
                .setKnownBuildRuleTypesFactory(factory1)
                .build());
    assertEquals("Android SDK should be the same initial location", daemon1, daemon2);
    Object daemon3 =
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(buckConfig)
                .setFilesystem(filesystem)
                .setKnownBuildRuleTypesFactory(factory2)
                .build());
    assertNotEquals("Android SDK should be the other location", daemon2, daemon3);
    Object daemon4 =
        daemonLifecycleManager.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(buckConfig)
                .setFilesystem(filesystem)
                .setKnownBuildRuleTypesFactory(factory2)
                .build());
    assertEquals("Android SDK should be the same other location", daemon3, daemon4);
  }

  private Optional<Path> getAppleDeveloperDir(BuckConfig buckConfig) {
    DefaultProcessExecutor defaultExecutor =
        new DefaultProcessExecutor(Console.createNullConsole());
    AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
    Supplier<Optional<Path>> appleDeveloperDirectorySupplier =
        appleConfig.getAppleDeveloperDirectorySupplier(defaultExecutor);
    return appleDeveloperDirectorySupplier.get();
  }
}
