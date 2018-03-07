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

import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.toolchain.ToolchainInstantiationException;
import com.facebook.buck.toolchain.impl.DefaultToolchainProvider;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidNdkFactoryTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
  }

  @Test
  public void testAndroidNdkNotPresentWhenNdkRootNotPresent() {

    DefaultToolchainProvider defaultToolchainProvider =
        new DefaultToolchainProvider(
            BuckPluginManagerFactory.createPluginManager(),
            ImmutableMap.of(),
            FakeBuckConfig.builder().build(),
            projectFilesystem,
            new DefaultProcessExecutor(new TestConsole()),
            new ExecutableFinder(),
            TestRuleKeyConfigurationFactory.create());

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
            new DefaultProcessExecutor(new TestConsole()),
            new ExecutableFinder(),
            TestRuleKeyConfigurationFactory.create());

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
}
