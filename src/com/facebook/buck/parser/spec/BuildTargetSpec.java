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

package com.facebook.buck.parser.spec;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.function.Function;
import java.util.stream.StreamSupport;
import org.immutables.value.Value;

/** Matches a {@link TargetNode} that corresponds to a single build target */
@BuckStyleValue
public abstract class BuildTargetSpec implements TargetNodeSpec {

  /** @return Build target to match with this spec and its output label, if any */
  public abstract UnconfiguredBuildTargetWithOutputs getUnconfiguredBuildTargetViewWithOutputs();

  public UnconfiguredBuildTarget getUnconfiguredBuildTarget() {
    return getUnconfiguredBuildTargetViewWithOutputs().getBuildTarget();
  }

  /** Returns a {@code BuildTargetSpec} with an empty output label. */
  public static BuildTargetSpec of(UnconfiguredBuildTarget unconfiguredBuildTarget) {
    return from(
        UnconfiguredBuildTargetWithOutputs.of(unconfiguredBuildTarget, OutputLabel.defaultLabel()));
  }

  @Value.Derived
  @Override
  public BuildFileSpec getBuildFileSpec() {
    return BuildFileSpec.fromUnconfiguredBuildTarget(getUnconfiguredBuildTarget());
  }

  /**
   * Returns a new instance of {@link BuildTargetSpec} and automatically resolve {@link
   * BuildFileSpec} based on {@link UnconfiguredBuildTarget} properties. The returned {@link
   * BuildTargetSpec} may carry a non-empty output label through {@link
   * UnconfiguredBuildTargetWithOutputs}.
   *
   * @param targetWithOutputs Build target to match
   */
  public static BuildTargetSpec from(UnconfiguredBuildTargetWithOutputs targetWithOutputs) {
    return ImmutableBuildTargetSpec.ofImpl(targetWithOutputs);
  }

  /**
   * Create new instance of {@link BuildTargetSpec} and automatically resolve {@link BuildFileSpec}
   * based on {@link UnconfiguredBuildTarget} properties. The returned {@link BuildTargetSpec}
   * carries an empty output label.
   *
   * @param target Build target to match
   */
  public static BuildTargetSpec from(UnconfiguredBuildTarget target) {
    // TODO(buck_team): use factory to create specs
    return BuildTargetSpec.of(target);
  }

  @Override
  public TargetType getTargetType() {
    return TargetType.SINGLE_TARGET;
  }

  @Override
  public ImmutableMap<BuildTarget, TargetNodeMaybeIncompatible> filter(
      Iterable<TargetNodeMaybeIncompatible> nodes) {
    return StreamSupport.stream(nodes.spliterator(), false)
        .filter(
            input ->
                input
                    .getBuildTarget()
                    .getUnflavoredBuildTarget()
                    .equals(getUnconfiguredBuildTarget().getUnflavoredBuildTarget()))
        .collect(
            ImmutableMap.toImmutableMap(
                TargetNodeMaybeIncompatible::getBuildTarget, Function.identity()));
  }

  @Override
  public ImmutableMap<UnflavoredBuildTarget, UnconfiguredTargetNode> filterUnconfigured(
      Iterable<UnconfiguredTargetNode> nodes) {
    return StreamSupport.stream(nodes.spliterator(), false)
        .filter(
            input ->
                input
                    .getBuildTarget()
                    .equals(getUnconfiguredBuildTarget().getUnflavoredBuildTarget()))
        .collect(
            ImmutableMap.toImmutableMap(
                UnconfiguredTargetNode::getBuildTarget, Function.identity()));
  }

  @Override
  public BuildTargetPattern getBuildTargetPattern(Cell cell) {
    BuildFileSpec buildFileSpec = getBuildFileSpec();
    if (!cell.getCanonicalName().equals(buildFileSpec.getCellRelativeBaseName().getCellName())) {
      throw new IllegalArgumentException(
          String.format(
              "Root of cell should agree with build file spec for %s: %s vs %s",
              toString(),
              cell.getCanonicalName(),
              buildFileSpec.getCellRelativeBaseName().getCellName()));
    }
    // TODO(strager): Check this invariant during construction.
    Preconditions.checkState(
        cell.getCanonicalName().equals(getUnconfiguredBuildTarget().getCell()));

    // TODO(strager): Check these invariants during construction.
    ForwardRelPath basePath = buildFileSpec.getCellRelativeBaseName().getPath();
    if (!basePath.equals(getUnconfiguredBuildTarget().getCellRelativeBasePath().getPath())) {
      throw new IllegalStateException(
          String.format(
              "Base path for %s's build target and build file spec should agree: %s vs %s",
              toString(),
              basePath,
              getUnconfiguredBuildTarget().getCellRelativeBasePath().getPath()));
    }
    if (buildFileSpec.isRecursive()) {
      throw new IllegalStateException(String.format("%s should be non-recursive", toString()));
    }

    return BuildTargetPattern.of(
        CellRelativePath.of(cell.getCanonicalName(), basePath),
        BuildTargetPattern.Kind.SINGLE,
        getUnconfiguredBuildTarget().getShortNameAndFlavorPostfix());
  }

  @Override
  public String toString() {
    return getUnconfiguredBuildTarget().getFullyQualifiedName();
  }
}
