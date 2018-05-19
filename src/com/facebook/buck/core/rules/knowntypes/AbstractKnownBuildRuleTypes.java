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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.description.DescriptionCache;
import com.facebook.buck.core.description.DescriptionCreationContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.targetgraph.DescriptionProvider;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.type.BuildRuleType;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.sandbox.SandboxExecutionStrategyFactory;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.immutables.value.Value;
import org.pf4j.PluginManager;

/** A registry of all the build rules types understood by Buck. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractKnownBuildRuleTypes {

  /** @return all the underlying {@link DescriptionWithTargetGraph}s. */
  @Value.Parameter
  abstract ImmutableList<DescriptionWithTargetGraph<?>> getDescriptions();

  // Verify that there are no duplicate rule types being defined.
  @Value.Check
  protected void check() {
    Set<BuildRuleType> types = new HashSet<>();
    for (DescriptionWithTargetGraph<?> description : getDescriptions()) {
      BuildRuleType type = DescriptionCache.getBuildRuleType(description);
      if (!types.add(DescriptionCache.getBuildRuleType(description))) {
        throw new IllegalStateException(String.format("multiple descriptions with type %s", type));
      }
    }
  }

  @Value.Lazy
  protected ImmutableMap<BuildRuleType, DescriptionWithTargetGraph<?>> getDescriptionsByType() {
    return getDescriptions()
        .stream()
        .collect(ImmutableMap.toImmutableMap(DescriptionCache::getBuildRuleType, d -> d));
  }

  @Value.Lazy
  protected ImmutableMap<String, BuildRuleType> getTypesByName() {
    return getDescriptions()
        .stream()
        .map(DescriptionCache::getBuildRuleType)
        .collect(ImmutableMap.toImmutableMap(BuildRuleType::getName, t -> t));
  }

  public BuildRuleType getBuildRuleType(String named) {
    BuildRuleType type = getTypesByName().get(named);
    if (type == null) {
      throw new HumanReadableException("Unable to find build rule type: " + named);
    }
    return type;
  }

  public DescriptionWithTargetGraph<?> getDescription(BuildRuleType buildRuleType) {
    DescriptionWithTargetGraph<?> description = getDescriptionsByType().get(buildRuleType);
    if (description == null) {
      throw new HumanReadableException(
          "Unable to find description for build rule type: " + buildRuleType);
    }
    return description;
  }

  static KnownBuildRuleTypes createInstance(
      BuckConfig config,
      ProcessExecutor processExecutor,
      ToolchainProvider toolchainProvider,
      PluginManager pluginManager,
      SandboxExecutionStrategyFactory sandboxExecutionStrategyFactory) {

    KnownBuildRuleTypes.Builder builder = KnownBuildRuleTypes.builder();

    SandboxExecutionStrategy sandboxExecutionStrategy =
        sandboxExecutionStrategyFactory.create(processExecutor, config);

    DescriptionCreationContext descriptionCreationContext =
        DescriptionCreationContext.of(config, toolchainProvider, sandboxExecutionStrategy);
    List<DescriptionProvider> descriptionProviders =
        pluginManager.getExtensions(DescriptionProvider.class);
    for (DescriptionProvider provider : descriptionProviders) {
      for (DescriptionWithTargetGraph<?> description :
          provider.getDescriptions(descriptionCreationContext)) {
        builder.addDescriptions(description);
      }
    }

    return builder.build();
  }
}
