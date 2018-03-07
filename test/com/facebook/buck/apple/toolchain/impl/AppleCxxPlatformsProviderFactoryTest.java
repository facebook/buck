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

package com.facebook.buck.apple.toolchain.impl;

import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryProvider;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkLocation;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.apple.toolchain.AppleToolchainProvider;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
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

public class AppleCxxPlatformsProviderFactoryTest {
  @Rule public TemporaryPaths temp = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testPlatformConflicts() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform-conflict", temp);
    workspace.setUp();
    Path root = workspace.getPath("Platforms");

    BuckConfig buckConfig = FakeBuckConfig.builder().build();

    ToolchainProviderBuilder toolchainProviderBuilder = new ToolchainProviderBuilder();

    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    ProcessExecutor processExecutor = new DefaultProcessExecutor(Console.createNullConsole());

    ToolchainCreationContext toolchainCreationContext =
        ToolchainCreationContext.of(
            ImmutableMap.of(),
            buckConfig,
            projectFilesystem,
            processExecutor,
            new ExecutableFinder(),
            TestRuleKeyConfigurationFactory.create());

    Optional<Path> appleDeveloperDir =
        new AppleDeveloperDirectoryProviderFactory()
            .createToolchain(toolchainProviderBuilder.build(), toolchainCreationContext)
            .map(AppleDeveloperDirectoryProvider::getAppleDeveloperDirectory);

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

    ToolchainProvider toolchainProvider =
        toolchainProviderBuilder
            .withToolchain(AppleSdkLocation.DEFAULT_NAME, AppleSdkLocation.of(appleSdkPaths))
            .withToolchain(
                AppleToolchainProvider.DEFAULT_NAME, AppleToolchainProvider.of(appleToolchains))
            .build();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        Matchers.containsString(
            "There are two conflicting SDKs providing the same platform \"macosx-i386\":\n"));

    new AppleCxxPlatformsProviderFactory()
        .createToolchain(toolchainProvider, toolchainCreationContext);

    fail("AppleCxxPlatformsProviderFactory.createToolchain should fail");
  }
}
