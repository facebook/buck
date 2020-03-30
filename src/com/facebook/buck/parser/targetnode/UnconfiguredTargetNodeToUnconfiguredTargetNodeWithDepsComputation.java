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

package com.facebook.buck.parser.targetnode;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNodeWithDeps;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.parser.UnconfiguredTargetNodeToTargetNodeFactory;
import com.facebook.buck.parser.config.ParserConfig;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

/** Transforms {@link UnconfiguredTargetNode} to {@link UnconfiguredTargetNodeWithDeps} */
public class UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsComputation
    implements GraphComputation<
        UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsKey, UnconfiguredTargetNodeWithDeps> {

  private final UnconfiguredTargetNodeToTargetNodeFactory unconfiguredTargetNodeToTargetNodeFactory;
  private final Cell cell;

  private UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsComputation(
      UnconfiguredTargetNodeToTargetNodeFactory unconfiguredTargetNodeToTargetNodeFactory,
      Cell cell) {
    this.unconfiguredTargetNodeToTargetNodeFactory = unconfiguredTargetNodeToTargetNodeFactory;
    this.cell = cell;
  }

  /**
   * Create new instance of {@link
   * UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsComputation}
   *
   * @param unconfiguredTargetNodeToTargetNodeFactory An actual factory that will create {@link
   *     TargetNode} from {@link UnconfiguredTargetNode} in order to resolve deps
   * @param cell A {@link Cell} object that contains targets used in this transformation, it is
   *     mostly used to resolve paths to absolute paths
   */
  public static UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsComputation of(
      UnconfiguredTargetNodeToTargetNodeFactory unconfiguredTargetNodeToTargetNodeFactory,
      Cell cell) {
    return new UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsComputation(
        unconfiguredTargetNodeToTargetNodeFactory, cell);
  }

  @Override
  public ComputationIdentifier<UnconfiguredTargetNodeWithDeps> getIdentifier() {
    return UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsKey.IDENTIFIER;
  }

  @Override
  public UnconfiguredTargetNodeWithDeps transform(
      UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsKey key, ComputationEnvironment env) {

    AbsPath buildFileAbsolutePath =
        cell.getRoot()
            .resolve(key.getPackagePath())
            .resolve(cell.getBuckConfig().getView(ParserConfig.class).getBuildFileName());

    UnconfiguredBuildTarget unconfiguredBuildTarget =
        key.getUnconfiguredTargetNode().getBuildTarget();

    // To discover dependencies, we coerce UnconfiguredTargetNode to TargetNode, get
    // dependencies out of
    // it, then trash target node
    // THIS SOLUTION IS TEMPORARY and not 100% correct in general, because we have
    // to resolve
    // configuration for Target Node (we use empty configuration at this point)

    BuildTarget buildTarget =
        unconfiguredBuildTarget.configure(UnconfiguredTargetConfiguration.INSTANCE);

    // TODO(nga): obtain proper dependency stack
    DependencyStack dependencyStack =
        DependencyStack.top(key.getUnconfiguredTargetNode().getBuildTarget());

    // All target nodes are created sequentially from raw target nodes
    // TODO: use RawTargetNodeToTargetNode transformation
    TargetNode<?> targetNode =
        unconfiguredTargetNodeToTargetNodeFactory
            .createTargetNode(
                cell,
                buildFileAbsolutePath,
                buildTarget,
                dependencyStack,
                key.getUnconfiguredTargetNode(),
                id ->
                    SimplePerfEvent.scope(
                        Optional.empty(), SimplePerfEvent.PerfEventId.of("raw_to_targetnode")))
            .assertGetTargetNode(dependencyStack);

    ImmutableSet<UnconfiguredBuildTarget> deps =
        targetNode.getParseDeps().stream()
            .map(BuildTarget::getUnconfiguredBuildTarget)
            .collect(ImmutableSet.toImmutableSet());
    // END TEMPORARY

    return UnconfiguredTargetNodeWithDeps.of(key.getUnconfiguredTargetNode(), deps);
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsKey key, ComputationEnvironment env) {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsKey key) {
    return ImmutableSet.of();
  }
}
