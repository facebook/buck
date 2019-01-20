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

package com.facebook.buck.android.toolchain.impl;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainInstantiationException;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AndroidSdkLocationFactoryTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() {
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
  }

  @Test
  public void testAndroidSdkLocationNotPresentWhenSdkRootNotPresent() {
    AndroidSdkLocationFactory factory = new AndroidSdkLocationFactory();

    String androidSdkNotPresentMessage = "Android SDK could not be found";

    ToolchainProvider toolchainProvider = new ToolchainProviderBuilder().build();

    thrown.expectMessage(androidSdkNotPresentMessage);
    thrown.expect(ToolchainInstantiationException.class);

    factory.createToolchain(
        toolchainProvider,
        ToolchainCreationContext.of(
            ImmutableMap.of(),
            FakeBuckConfig.builder().build(),
            projectFilesystem,
            new DefaultProcessExecutor(new TestConsole()),
            new ExecutableFinder(),
            TestRuleKeyConfigurationFactory.create()));
  }

  @Test
  public void testAndroidSdkLocationIsPresent() throws Exception {
    AndroidSdkLocationFactory factory = new AndroidSdkLocationFactory();

    Path sdkPath = temporaryFolder.newFolder("android_sdk");

    ToolchainProvider toolchainProvider = new ToolchainProviderBuilder().build();

    Optional<AndroidSdkLocation> toolchain =
        factory.createToolchain(
            toolchainProvider,
            ToolchainCreationContext.of(
                ImmutableMap.of(),
                FakeBuckConfig.builder().setSections("[android]", "sdk_path = " + sdkPath).build(),
                projectFilesystem,
                new DefaultProcessExecutor(new TestConsole()),
                new ExecutableFinder(),
                TestRuleKeyConfigurationFactory.create()));

    assertEquals(sdkPath, toolchain.get().getSdkRootPath());
  }
}
