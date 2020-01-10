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

package com.facebook.buck.core.rules.resolver.impl;

import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.config.registry.impl.ConfigurationRuleRegistryFactory;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.Executors;

/**
 * An action graph builder used for tests. See also {@link
 * com.facebook.buck.core.rules.resolver.impl.FakeActionGraphBuilder}.
 */
public class TestActionGraphBuilder extends MultiThreadedActionGraphBuilder {

  public TestActionGraphBuilder(
      TargetGraph targetGraph,
      TargetNodeToBuildRuleTransformer buildRuleGenerator,
      CellProvider cellProvider) {
    super(
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
        targetGraph,
        ConfigurationRuleRegistryFactory.createRegistry(targetGraph),
        buildRuleGenerator,
        cellProvider);
  }

  public TestActionGraphBuilder(
      TargetGraph targetGraph,
      TargetNodeToBuildRuleTransformer buildRuleGenerator,
      ToolchainProvider toolchainProvider) {
    this(
        targetGraph,
        buildRuleGenerator,
        new TestCellBuilder().setToolchainProvider(toolchainProvider).build().getCellProvider());
  }

  public TestActionGraphBuilder(
      TargetGraph targetGraph, TargetNodeToBuildRuleTransformer buildRuleGenerator) {
    this(targetGraph, buildRuleGenerator, new TestCellBuilder().build().getCellProvider());
  }

  public TestActionGraphBuilder(TargetNodeToBuildRuleTransformer buildRuleGenerator) {
    this(TargetGraph.EMPTY, buildRuleGenerator);
  }

  public TestActionGraphBuilder(TargetGraph targetGraph) {
    this(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
  }

  public TestActionGraphBuilder(TargetGraph targetGraph, ProjectFilesystem filesystem) {
    this(
        targetGraph,
        new DefaultTargetNodeToBuildRuleTransformer(),
        new TestCellBuilder().setFilesystem(filesystem).build().getCellProvider());
  }

  public TestActionGraphBuilder(TargetGraph targetGraph, ToolchainProvider toolchainProvider) {
    this(targetGraph, new DefaultTargetNodeToBuildRuleTransformer(), toolchainProvider);
  }

  public TestActionGraphBuilder() {
    this(TargetGraph.EMPTY);
  }
}
