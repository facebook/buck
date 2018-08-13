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

package com.facebook.buck.android.toolchain.ndk.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainInstantiationException;
import com.facebook.buck.core.toolchain.impl.DefaultToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidNdkFactoryTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private ProjectFilesystem projectFilesystem;
  private ProcessExecutor processExecutor;
  private ExecutableFinder executableFinder;
  private RuleKeyConfiguration ruleKeyConfiguration;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    processExecutor = new DefaultProcessExecutor(new TestConsole());
    executableFinder = new ExecutableFinder();
    ruleKeyConfiguration = TestRuleKeyConfigurationFactory.create();
  }

  @Test
  public void testAndroidNdkNotPresentWhenNdkRootNotPresent() {

    DefaultToolchainProvider defaultToolchainProvider =
        new DefaultToolchainProvider(
            BuckPluginManagerFactory.createPluginManager(),
            ImmutableMap.of(),
            FakeBuckConfig.builder().build(),
            projectFilesystem,
            processExecutor,
            executableFinder,
            ruleKeyConfiguration);

    Optional<AndroidNdk> androidNdk =
        defaultToolchainProvider.getByNameIfPresent(AndroidNdk.DEFAULT_NAME, AndroidNdk.class);

    assertFalse(androidNdk.isPresent());
  }

  @Test
  public void testAndroidNdkFailureMessage() {

    DefaultToolchainProvider defaultToolchainProvider =
        new DefaultToolchainProvider(
            BuckPluginManagerFactory.createPluginManager(),
            ImmutableMap.of(),
            FakeBuckConfig.builder().build(),
            projectFilesystem,
            processExecutor,
            executableFinder,
            ruleKeyConfiguration);

    try {
      defaultToolchainProvider.getByName(AndroidNdk.DEFAULT_NAME, AndroidNdk.class);
    } catch (ToolchainInstantiationException e) {
      assertEquals(
          "Android NDK could not be found. Make sure to set one of these  environment "
              + "variables: ANDROID_NDK_REPOSITORY, ANDROID_NDK, or NDK_HOME or ndk.ndk_path or "
              + "ndk.ndk_repository_path in your .buckconfig",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testEscapeCFlagsWithNdk15() {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    TestAndroidNdkFactory factory =
        new TestAndroidNdkFactory(
            new TestAndroidNdkResolver(
                projectFilesystem.getRootPath().getFileSystem(),
                ImmutableMap.of(),
                new AndroidBuckConfig(buckConfig, Platform.detect()),
                Optional.of(Paths.get("")),
                Optional.of("15")));

    AndroidNdk androidNdk =
        factory
            .createToolchain(
                new ToolchainProviderBuilder().build(),
                ToolchainCreationContext.of(
                    ImmutableMap.of(),
                    buckConfig,
                    projectFilesystem,
                    processExecutor,
                    executableFinder,
                    ruleKeyConfiguration))
            .get();

    assertFalse(androidNdk.shouldEscapeCFlagsInDoubleQuotes());
  }

  @Test
  public void testEscapeCFlagsWithNdk16() {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    TestAndroidNdkFactory factory =
        new TestAndroidNdkFactory(
            new TestAndroidNdkResolver(
                projectFilesystem.getRootPath().getFileSystem(),
                ImmutableMap.of(),
                new AndroidBuckConfig(buckConfig, Platform.detect()),
                Optional.of(Paths.get("")),
                Optional.of("16")));

    AndroidNdk androidNdk =
        factory
            .createToolchain(
                new ToolchainProviderBuilder().build(),
                ToolchainCreationContext.of(
                    ImmutableMap.of(),
                    buckConfig,
                    projectFilesystem,
                    processExecutor,
                    executableFinder,
                    ruleKeyConfiguration))
            .get();

    assertTrue(androidNdk.shouldEscapeCFlagsInDoubleQuotes());
  }

  private static class TestAndroidNdkResolver extends AndroidNdkResolver {
    private final Optional<Path> ndkRoot;
    private final Optional<String> ndkVersion;

    public TestAndroidNdkResolver(
        FileSystem fileSystem,
        ImmutableMap<String, String> environment,
        AndroidBuckConfig config,
        Optional<Path> ndkRoot,
        Optional<String> ndkVersion) {
      super(fileSystem, environment, config);
      this.ndkRoot = ndkRoot;
      this.ndkVersion = ndkVersion;
    }

    @Override
    public Optional<String> getNdkVersion() {
      return ndkVersion;
    }

    @Override
    public Path getNdkOrThrow() {
      return ndkRoot.get();
    }
  }

  private static class TestAndroidNdkFactory extends AndroidNdkFactory {

    private final AndroidNdkResolver androidNdkResolver;

    private TestAndroidNdkFactory(AndroidNdkResolver androidNdkResolver) {
      this.androidNdkResolver = androidNdkResolver;
    }

    @Override
    AndroidNdkResolver createAndroidNdkResolver(
        FileSystem filesystem,
        ImmutableMap<String, String> environment,
        AndroidBuckConfig androidBuckConfig) {
      return androidNdkResolver;
    }
  }
}
