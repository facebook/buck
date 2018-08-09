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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.DescriptionCreationContext;
import com.facebook.buck.core.model.targetgraph.DescriptionProvider;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.sandbox.SandboxExecutionStrategyFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.pf4j.PluginManager;

/** Loads all known build rule types from plugins. */
class KnownBuildRuleDescriptionsFactory {

  static ImmutableList<DescriptionWithTargetGraph<?>> createBuildDescriptions(
      BuckConfig config,
      ProcessExecutor processExecutor,
      ToolchainProvider toolchainProvider,
      PluginManager pluginManager,
      SandboxExecutionStrategyFactory sandboxExecutionStrategyFactory) {

    ImmutableList.Builder<DescriptionWithTargetGraph<?>> builder = ImmutableList.builder();

    SandboxExecutionStrategy sandboxExecutionStrategy =
        sandboxExecutionStrategyFactory.create(processExecutor, config);

    DescriptionCreationContext descriptionCreationContext =
        DescriptionCreationContext.of(
            config, toolchainProvider, sandboxExecutionStrategy, pluginManager);
    List<DescriptionProvider> descriptionProviders =
        pluginManager.getExtensions(DescriptionProvider.class);
    for (DescriptionProvider provider : descriptionProviders) {
      builder.addAll(provider.getDescriptions(descriptionCreationContext));
    }

    return builder.build();
  }
}
