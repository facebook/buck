/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.parser.targetnode;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableUnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.raw.ImmutableRawTargetNodeWithDeps;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNodeWithDeps;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.RawTargetNodeToTargetNodeFactory;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

/** Transforms {@link RawTargetNode} to {@link RawTargetNodeWithDeps} */
public class RawTargetNodeToRawTargetNodeWithDepsComputation
    implements GraphComputation<RawTargetNodeToRawTargetNodeWithDepsKey, RawTargetNodeWithDeps> {

  private final RawTargetNodeToTargetNodeFactory rawTargetNodeToTargetNodeFactory;
  private final Cell cell;

  private RawTargetNodeToRawTargetNodeWithDepsComputation(
      RawTargetNodeToTargetNodeFactory rawTargetNodeToTargetNodeFactory, Cell cell) {
    this.rawTargetNodeToTargetNodeFactory = rawTargetNodeToTargetNodeFactory;
    this.cell = cell;
  }

  /**
   * Create new instance of {@link RawTargetNodeToRawTargetNodeWithDepsComputation}
   *
   * @param rawTargetNodeToTargetNodeFactory An actual factory that will create {@link TargetNode}
   *     from {@link RawTargetNode} in order to resolve deps
   * @param cell A {@link Cell} object that contains targets used in this transformation, it is
   *     mostly used to resolve paths to absolute paths
   * @return
   */
  public static RawTargetNodeToRawTargetNodeWithDepsComputation of(
      RawTargetNodeToTargetNodeFactory rawTargetNodeToTargetNodeFactory, Cell cell) {
    return new RawTargetNodeToRawTargetNodeWithDepsComputation(
        rawTargetNodeToTargetNodeFactory, cell);
  }

  @Override
  public ComputationIdentifier<RawTargetNodeWithDeps> getIdentifier() {
    return RawTargetNodeToRawTargetNodeWithDepsKey.IDENTIFIER;
  }

  @Override
  public RawTargetNodeWithDeps transform(
      RawTargetNodeToRawTargetNodeWithDepsKey key, ComputationEnvironment env) {

    Path buildFileAbsolutePath =
        cell.getRoot()
            .resolve(key.getPackagePath())
            .resolve(cell.getBuckConfig().getView(ParserConfig.class).getBuildFileName());

    UnconfiguredBuildTarget unconfiguredBuildTarget = key.getRawTargetNode().getBuildTarget();

    // To discover dependencies, we coerce RawTargetNode to TargetNode, get
    // dependencies out of
    // it, then trash target node
    // THIS SOLUTION IS TEMPORARY and not 100% correct in general, because we have
    // to resolve
    // configuration for Target Node (we use empty configuration at this point)

    // Create short living UnconfiguredBuildTargetView
    // TODO: configure data object directly
    UnconfiguredBuildTargetView unconfiguredBuildTargetView =
        ImmutableUnconfiguredBuildTargetView.of(cell.getRoot(), unconfiguredBuildTarget);

    BuildTarget buildTarget =
        unconfiguredBuildTargetView.configure(EmptyTargetConfiguration.INSTANCE);

    // All target nodes are created sequentially from raw target nodes
    // TODO: use RawTargetNodeToTargetNode transformation
    TargetNode<?> targetNode =
        rawTargetNodeToTargetNodeFactory.createTargetNode(
            cell,
            buildFileAbsolutePath,
            buildTarget,
            key.getRawTargetNode(),
            id -> SimplePerfEvent.scope(Optional.empty(), PerfEventId.of("raw_to_targetnode")));

    ImmutableSet<UnconfiguredBuildTarget> deps =
        targetNode.getParseDeps().stream()
            .map(bt -> bt.getUnconfiguredBuildTargetView().getData())
            .collect(ImmutableSet.toImmutableSet());

    // END TEMPORARY

    return ImmutableRawTargetNodeWithDeps.of(key.getRawTargetNode(), deps);
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      RawTargetNodeToRawTargetNodeWithDepsKey key, ComputationEnvironment env) {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      RawTargetNodeToRawTargetNodeWithDepsKey key) {
    return ImmutableSet.of();
  }
}
