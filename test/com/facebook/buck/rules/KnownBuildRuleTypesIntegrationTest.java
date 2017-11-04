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

package com.facebook.buck.rules;

import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.android.toolchain.TestAndroidToolchain;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleCxxPlatforms;
import com.facebook.buck.apple.AppleSdkDiscovery;
import com.facebook.buck.apple.AppleToolchainDiscovery;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.plugin.BuckPluginManagerFactory;
import com.facebook.buck.sandbox.TestSandboxExecutionStrategyFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.toolchain.impl.TestToolchainProvider;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.pf4j.PluginManager;

public class KnownBuildRuleTypesIntegrationTest {

  @Rule public TemporaryPaths temp = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testPlatformConflicts() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform-conflict", temp);
    workspace.setUp();
    Path root = workspace.getPath("Platforms");

    BuckConfig buckConfig = FakeBuckConfig.builder().build();

    ProcessExecutor processExecutor = new DefaultProcessExecutor(Console.createNullConsole());
    Optional<Path> appleDeveloperDir =
        AppleCxxPlatforms.getAppleDeveloperDirectory(buckConfig, processExecutor);

    ImmutableMap<String, AppleToolchain> appleToolchains =
        AppleToolchainDiscovery.discoverAppleToolchains(appleDeveloperDir, ImmutableList.of());

    assumeThat(appleToolchains, is(not(anEmptyMap())));

    ImmutableMap<AppleSdk, AppleSdkPaths> appleSdkPaths =
        AppleSdkDiscovery.discoverAppleSdkPaths(
            appleDeveloperDir,
            ImmutableList.of(root),
            appleToolchains,
            buckConfig.getView(AppleConfig.class));

    assumeThat(appleSdkPaths, is(not(anEmptyMap())));

    SdkEnvironment sdkEnvironment =
        SdkEnvironment.of(
            Optional.of(appleSdkPaths),
            Optional.of(appleToolchains),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    TestToolchainProvider toolchainProvider = new TestToolchainProvider();
    toolchainProvider.addAndroidToolchain(new TestAndroidToolchain());

    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        Matchers.containsString(
            "There are two conflicting SDKs providing the same platform \"macosx-i386\":\n"));
    KnownBuildRuleTypes.createInstance(
        buckConfig,
        projectFilesystem,
        processExecutor,
        toolchainProvider,
        sdkEnvironment,
        pluginManager,
        new TestSandboxExecutionStrategyFactory());
  }
}
