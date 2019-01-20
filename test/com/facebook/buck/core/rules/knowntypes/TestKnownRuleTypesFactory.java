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

package com.facebook.buck.core.rules.knowntypes;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.sandbox.SandboxExecutionStrategyFactory;
import com.facebook.buck.sandbox.TestSandboxExecutionStrategyFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableList;
import org.pf4j.PluginManager;

public class TestKnownRuleTypesFactory {

  public static KnownRuleTypes create(
      BuckConfig config, ToolchainProvider toolchainProvider, ProcessExecutor processExecutor) {
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();

    SandboxExecutionStrategyFactory sandboxExecutionStrategyFactory =
        new TestSandboxExecutionStrategyFactory();
    return KnownRuleTypes.of(
        KnownBuildRuleDescriptionsFactory.createBuildDescriptions(
            config,
            processExecutor,
            toolchainProvider,
            pluginManager,
            sandboxExecutionStrategyFactory),
        ImmutableList.of());
  }
}
