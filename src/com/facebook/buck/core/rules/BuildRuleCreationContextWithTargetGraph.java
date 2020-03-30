/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.rules;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;

/**
 * Contains common objects used during {@link BuildRule} creation in {@link
 * DescriptionWithTargetGraph#createBuildRule}.
 */
@BuckStyleValue
public interface BuildRuleCreationContextWithTargetGraph extends BuildRuleCreationContext {

  TargetGraph getTargetGraph();

  @Override
  ActionGraphBuilder getActionGraphBuilder();

  @Override
  ProjectFilesystem getProjectFilesystem();

  @Override
  CellPathResolver getCellPathResolver();

  @Override
  ToolchainProvider getToolchainProvider();

  @Override
  ConfigurationRuleRegistry getConfigurationRuleRegistry();

  /**
   * @return the {@link ProviderInfoCollection} that was created by the rules {@link
   *     LegacyProviderCompatibleDescription#createProviders(ProviderCreationContext, BuildTarget,
   *     BuildRuleArg)}
   */
  ProviderInfoCollection getProviderInfoCollection();

  static BuildRuleCreationContextWithTargetGraph of(
      TargetGraph targetGraph,
      ActionGraphBuilder actionGraphBuilder,
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellPathResolver,
      ToolchainProvider toolchainProvider,
      ConfigurationRuleRegistry configurationRuleRegistry,
      ProviderInfoCollection providerInfoCollection) {
    return ImmutableBuildRuleCreationContextWithTargetGraph.of(
        targetGraph,
        actionGraphBuilder,
        projectFilesystem,
        cellPathResolver,
        toolchainProvider,
        configurationRuleRegistry,
        providerInfoCollection);
  }
}
