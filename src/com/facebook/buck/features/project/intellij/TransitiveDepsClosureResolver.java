/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Calculates the transitive closure of exported deps for every node in a {@link TargetGraph}. */
public class TransitiveDepsClosureResolver {

  private final TargetGraph targetGraph;
  private final ImmutableSet<String> ignoredTargetLabels;
  private final Map<BuildTarget, ImmutableSet<BuildTarget>> index;

  public TransitiveDepsClosureResolver(
      TargetGraph targetGraph, ImmutableSet<String> ignoredTargetLabels) {
    this.targetGraph = targetGraph;
    this.ignoredTargetLabels = ignoredTargetLabels;
    index = new HashMap<>();
  }

  /**
   * @param buildTarget target to process.
   * @return the set of {@link BuildTarget}s that must be appended to the dependencies of a node Y
   *     if node Y depends on X.
   */
  public ImmutableSet<BuildTarget> getTransitiveDepsClosure(BuildTarget buildTarget) {
    if (index.containsKey(buildTarget)) {
      return index.get(buildTarget);
    }

    ImmutableSet<BuildTarget> exportedDeps = ImmutableSet.of();
    TargetNode<?> targetNode = targetGraph.get(buildTarget);
    if (targetNode.getConstructorArg() instanceof JavaLibraryDescription.CoreArg) {
      JavaLibraryDescription.CoreArg arg =
          (JavaLibraryDescription.CoreArg) targetNode.getConstructorArg();
      exportedDeps = arg.getExportedDeps();
    }

    ImmutableSet<BuildTarget> transitiveDepsClosure =
        Stream.concat(exportedDeps.stream(), targetNode.getBuildDeps().stream())
            .filter(
                target -> {
                  CommonDescriptionArg arg =
                      (CommonDescriptionArg) targetGraph.get(target).getConstructorArg();
                  return !arg.labelsContainsAnyOf(ignoredTargetLabels);
                })
            .sorted()
            .flatMap(
                target ->
                    Stream.concat(getTransitiveDepsClosure(target).stream(), Stream.of(target)))
            .collect(ImmutableSet.toImmutableSet());

    index.put(buildTarget, transitiveDepsClosure);
    return transitiveDepsClosure;
  }
}
