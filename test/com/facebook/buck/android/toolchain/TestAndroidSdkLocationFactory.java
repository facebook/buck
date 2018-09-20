/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.android.toolchain;

import com.facebook.buck.android.toolchain.impl.AndroidSdkLocationFactory;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.google.common.collect.ImmutableMap;

public class TestAndroidSdkLocationFactory {
  public static AndroidSdkLocation create(ProjectFilesystem filesystem) {
    ToolchainCreationContext toolchainCreationContext =
        ToolchainCreationContext.of(
            ImmutableMap.copyOf(System.getenv()),
            FakeBuckConfig.builder().build(),
            filesystem,
            new DefaultProcessExecutor(new TestConsole()),
            new ExecutableFinder(),
            TestRuleKeyConfigurationFactory.create());

    return new AndroidSdkLocationFactory()
        .createToolchain(new ToolchainProviderBuilder().build(), toolchainCreationContext)
        .get();
  }
}
