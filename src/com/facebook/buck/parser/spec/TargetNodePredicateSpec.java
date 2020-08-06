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
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetLanguageConstants;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/** Matches all {@link TargetNode} objects in a repository that match the specification. */
@BuckStyleValue
public abstract class TargetNodePredicateSpec implements TargetNodeSpec {

  @Override
  public abstract BuildFileSpec getBuildFileSpec();

  public abstract boolean onlyTests();

  @Override
  public TargetType getTargetType() {
    return TargetType.MULTIPLE_TARGETS;
  }

  @Override
  public ImmutableMap<BuildTarget, TargetNodeMaybeIncompatible> filter(
      Iterable<TargetNodeMaybeIncompatible> nodes) {
    ImmutableMap.Builder<BuildTarget, TargetNodeMaybeIncompatible> resultBuilder =
        ImmutableMap.builder();

    for (TargetNodeMaybeIncompatible node : nodes) {
      Optional<TargetNode<?>> targetNode = node.getTargetNodeOptional();
      if (!onlyTests() || !targetNode.isPresent() || targetNode.get().getRuleType().isTestRule()) {
        resultBuilder.put(node.getBuildTarget(), node);
      }
    }

    return resultBuilder.build();
  }

  @Override
  public BuildTargetPattern getBuildTargetPattern(Cell cell) {
    BuildFileSpec buildFileSpec = getBuildFileSpec();
    if (!cell.getCanonicalName().equals(buildFileSpec.getCellRelativeBaseName().getCellName())) {
      throw new IllegalArgumentException(
          String.format(
              "%s: Root of cell should agree with build file spec: %s vs %s",
              toString(), cell.getRoot(), buildFileSpec.getCellRelativeBaseName().getCellName()));
    }

    CanonicalCellName cellName = cell.getCanonicalName();

    ForwardRelativePath basePath = buildFileSpec.getCellRelativeBaseName().getPath();
    return BuildTargetPattern.of(
        CellRelativePath.of(cellName, basePath),
        buildFileSpec.isRecursive()
            ? BuildTargetPattern.Kind.RECURSIVE
            : BuildTargetPattern.Kind.PACKAGE,
        "");
  }

  public static TargetNodePredicateSpec of(BuildFileSpec buildFileSpec) {
    return of(buildFileSpec, false);
  }

  public static TargetNodePredicateSpec of(BuildFileSpec buildFileSpec, boolean onlyTests) {
    return ImmutableTargetNodePredicateSpec.of(buildFileSpec, onlyTests);
  }

  @Override
  public String toString() {
    StringBuilder builder =
        new StringBuilder(getBuildFileSpec().getCellRelativeBaseName().toString());
    if (getBuildFileSpec().isRecursive()) {
      builder
          .append(BuildTargetLanguageConstants.PATH_SYMBOL)
          .append(BuildTargetLanguageConstants.RECURSIVE_SYMBOL);
    } else {
      builder.append(BuildTargetLanguageConstants.TARGET_SYMBOL);
    }
    return builder.toString();
  }
}
