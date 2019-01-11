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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.description.Description;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.sandbox.SandboxExecutionStrategyFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableList;
import org.pf4j.PluginManager;

/**
 * An implementation of {@link KnownRuleTypesFactory} that creates a list of {@link
 * DescriptionWithTargetGraph} for a given cell and merges it with a list of configuration rule
 * descriptions.
 */
public class DefaultKnownRuleTypesFactory implements KnownRuleTypesFactory {

  private final ProcessExecutor executor;
  private final PluginManager pluginManager;
  private final SandboxExecutionStrategyFactory sandboxExecutionStrategyFactory;
  private final ImmutableList<ConfigurationRuleDescription<?>> knownConfigurationDescriptions;

  public DefaultKnownRuleTypesFactory(
      ProcessExecutor executor,
      PluginManager pluginManager,
      SandboxExecutionStrategyFactory sandboxExecutionStrategyFactory,
      ImmutableList<ConfigurationRuleDescription<?>> knownConfigurationDescriptions) {
    this.executor = executor;
    this.pluginManager = pluginManager;
    this.sandboxExecutionStrategyFactory = sandboxExecutionStrategyFactory;
    this.knownConfigurationDescriptions = knownConfigurationDescriptions;
  }

  @Override
  public KnownRuleTypes create(Cell cell) {
    ImmutableList<Description<?>> knownBuildRuleDescriptions =
        KnownBuildRuleDescriptionsFactory.createBuildDescriptions(
            cell.getBuckConfig(),
            executor,
            cell.getToolchainProvider(),
            pluginManager,
            sandboxExecutionStrategyFactory);
    return KnownRuleTypes.of(knownBuildRuleDescriptions, knownConfigurationDescriptions);
  }
}
