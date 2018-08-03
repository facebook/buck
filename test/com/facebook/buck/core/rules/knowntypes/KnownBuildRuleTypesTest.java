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

package com.facebook.buck.core.rules.knowntypes;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.toolchain.Toolchain;
import com.facebook.buck.toolchain.impl.DefaultToolchainProvider;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pf4j.PluginManager;

public class KnownBuildRuleTypesTest {

  @ClassRule public static TemporaryFolder folder = new TemporaryFolder();
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private static final String FAKE_XCODE_DEV_PATH = "/Fake/Path/To/Xcode.app/Contents/Developer";
  private static final ImmutableMap<String, String> environment =
      ImmutableMap.copyOf(System.getenv());

  private ExecutableFinder executableFinder = new ExecutableFinder();

  @Test
  public void createInstanceShouldReturnDifferentInstancesIfCalledWithDifferentParameters()
      throws Exception {
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    DefaultToolchainProvider toolchainProvider =
        new DefaultToolchainProvider(
            pluginManager,
            environment,
            buckConfig,
            filesystem,
            KnownBuildRuleTypesTestUtil.createExecutor(temporaryFolder),
            executableFinder,
            TestRuleKeyConfigurationFactory.create());

    KnownBuildRuleTypes knownBuildRuleTypes1 =
        KnownBuildRuleTypesTestUtil.createInstance(
            buckConfig,
            toolchainProvider,
            KnownBuildRuleTypesTestUtil.createExecutor(temporaryFolder));

    Path javac = temporaryFolder.newExecutableFile();
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("tools", ImmutableMap.of("javac", javac.toString()));
    buckConfig = FakeBuckConfig.builder().setFilesystem(filesystem).setSections(sections).build();

    ProcessExecutor processExecutor =
        KnownBuildRuleTypesTestUtil.createExecutor(javac.toString(), "");

    toolchainProvider =
        new DefaultToolchainProvider(
            pluginManager,
            environment,
            buckConfig,
            filesystem,
            KnownBuildRuleTypesTestUtil.createExecutor(temporaryFolder),
            executableFinder,
            TestRuleKeyConfigurationFactory.create());

    KnownBuildRuleTypes knownBuildRuleTypes2 =
        KnownBuildRuleTypesTestUtil.createInstance(buckConfig, toolchainProvider, processExecutor);

    assertNotEquals(knownBuildRuleTypes1, knownBuildRuleTypes2);
  }

  @Test
  public void canSetDefaultPlatformToDefault() throws Exception {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("cxx", ImmutableMap.of("default_platform", "default"));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();

    DefaultToolchainProvider toolchainProvider =
        new DefaultToolchainProvider(
            BuckPluginManagerFactory.createPluginManager(),
            environment,
            buckConfig,
            filesystem,
            KnownBuildRuleTypesTestUtil.createExecutor(temporaryFolder),
            executableFinder,
            TestRuleKeyConfigurationFactory.create());

    // This would throw if "default" weren't available as a platform.
    KnownBuildRuleTypesTestUtil.createInstance(
        buckConfig, toolchainProvider, KnownBuildRuleTypesTestUtil.createExecutor(temporaryFolder));
  }

  @Test
  public void canOverrideDefaultHostPlatform() throws Exception {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    Flavor flavor = InternalFlavor.of("flavor");
    String flag = "-flag";
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("cxx#" + flavor, ImmutableMap.of("cflags", flag));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    DefaultToolchainProvider toolchainProvider =
        new DefaultToolchainProvider(
            BuckPluginManagerFactory.createPluginManager(),
            environment,
            buckConfig,
            filesystem,
            KnownBuildRuleTypesTestUtil.createExecutor(temporaryFolder),
            executableFinder,
            TestRuleKeyConfigurationFactory.create());
    CxxPlatformsProvider cxxPlatformsProvider =
        toolchainProvider.getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
    assertThat(
        cxxPlatformsProvider.getCxxPlatforms().getValue(flavor).getCflags(),
        Matchers.contains(flag));
    KnownBuildRuleTypesTestUtil.createInstance(
        buckConfig, toolchainProvider, KnownBuildRuleTypesTestUtil.createExecutor(temporaryFolder));
  }

  @Test
  public void canOverrideMultipleHostPlatforms() throws Exception {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of(
            "cxx#linux-x86_64", ImmutableMap.of("cache_links", "true"),
            "cxx#macosx-x86_64", ImmutableMap.of("cache_links", "true"),
            "cxx#windows-x86_64", ImmutableMap.of("cache_links", "true"));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    DefaultToolchainProvider toolchainProvider =
        new DefaultToolchainProvider(
            BuckPluginManagerFactory.createPluginManager(),
            environment,
            buckConfig,
            filesystem,
            KnownBuildRuleTypesTestUtil.createExecutor(temporaryFolder),
            executableFinder,
            TestRuleKeyConfigurationFactory.create());

    // It should be legal to override multiple host platforms even though
    // only one will be practically used in a build.
    KnownBuildRuleTypesTestUtil.createInstance(
        buckConfig, toolchainProvider, KnownBuildRuleTypesTestUtil.createExecutor(temporaryFolder));
  }

  @Test
  public void toolchainAreNotCreated() throws Exception {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    DefaultToolchainProvider toolchainProvider =
        new DefaultToolchainProvider(
            BuckPluginManagerFactory.createPluginManager(),
            environment,
            buckConfig,
            filesystem,
            KnownBuildRuleTypesTestUtil.createExecutor(temporaryFolder),
            executableFinder,
            TestRuleKeyConfigurationFactory.create()) {
          @Override
          public Toolchain getByName(String toolchainName) {
            throw new IllegalStateException(
                "Toolchain creation is not allowed during construction of KnownBuildRuleTypesTest");
          }
        };

    KnownBuildRuleTypesTestUtil.createInstance(
        buckConfig, toolchainProvider, KnownBuildRuleTypesTestUtil.createExecutor(temporaryFolder));
  }
}
