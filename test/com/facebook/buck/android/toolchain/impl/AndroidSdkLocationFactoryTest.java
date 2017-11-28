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
import static org.junit.Assert.assertFalse;

import com.facebook.buck.android.AndroidLegacyToolchain;
import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.android.DefaultAndroidLegacyToolchain;
import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidSdkLocationFactoryTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
  }

  @Test
  public void testAndroidSdkLocationNotPresentWhenSdkRootNotPresent() throws Exception {
    AndroidSdkLocationFactory factory = new AndroidSdkLocationFactory();

    FakeAndroidDirectoryResolver androidDirectoryResolver = new FakeAndroidDirectoryResolver();

    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(
                AndroidLegacyToolchain.DEFAULT_NAME,
                new DefaultAndroidLegacyToolchain(
                    () ->
                        AndroidPlatformTarget.getDefaultPlatformTarget(
                            androidDirectoryResolver, Optional.empty(), Optional.empty()),
                    androidDirectoryResolver))
            .build();

    Optional<AndroidSdkLocation> toolchain =
        factory.createToolchain(
            toolchainProvider,
            ToolchainCreationContext.of(
                ImmutableMap.of(),
                FakeBuckConfig.builder().build(),
                projectFilesystem,
                new DefaultProcessExecutor(new TestConsole()),
                new ExecutableFinder()));

    assertFalse(toolchain.isPresent());
  }

  @Test
  public void testAndroidSdkLocationIsPresent() throws Exception {
    AndroidSdkLocationFactory factory = new AndroidSdkLocationFactory();

    Path sdkLocation = Paths.get("/sdk/location");

    FakeAndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(sdkLocation), Optional.empty(), Optional.empty(), Optional.empty());

    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(
                AndroidLegacyToolchain.DEFAULT_NAME,
                new DefaultAndroidLegacyToolchain(
                    () ->
                        AndroidPlatformTarget.getDefaultPlatformTarget(
                            androidDirectoryResolver, Optional.empty(), Optional.empty()),
                    androidDirectoryResolver))
            .build();

    Optional<AndroidSdkLocation> toolchain =
        factory.createToolchain(
            toolchainProvider,
            ToolchainCreationContext.of(
                ImmutableMap.of(),
                FakeBuckConfig.builder().build(),
                projectFilesystem,
                new DefaultProcessExecutor(new TestConsole()),
                new ExecutableFinder()));

    assertEquals(sdkLocation, toolchain.get().getSdkRootPath());
  }
}
