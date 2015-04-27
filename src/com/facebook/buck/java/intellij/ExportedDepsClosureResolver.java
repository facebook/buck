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

package com.facebook.buck.java.intellij;

import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.util.HashMap;
import java.util.Map;

/**
 * Calculates the transitive closure of exported deps for every node in a {@link TargetGraph}.
 */
public class ExportedDepsClosureResolver {

  private TargetGraph targetGraph;
  private Map<BuildTarget, ImmutableSet<BuildTarget>> index;

  public ExportedDepsClosureResolver(TargetGraph targetGraph) {
    Preconditions.checkArgument(targetGraph.isAcyclic());
    this.targetGraph = targetGraph;
    index = new HashMap<>();
  }

  /**
   * @param buildTarget target to process.
   * @return the set of {@link BuildTarget}s that must be appended to the
   *         dependencies of a node Y if node Y depends on X.
   */
  public ImmutableSet<BuildTarget> getExportedDepsClosure(BuildTarget buildTarget) {
    if (index.containsKey(buildTarget)) {
      return index.get(buildTarget);
    }

    ImmutableSet<BuildTarget> exportedDeps = ImmutableSet.of();
    TargetNode<?> targetNode = Preconditions.checkNotNull(targetGraph.get(buildTarget));
    if (targetNode.getType().equals(JavaLibraryDescription.TYPE)) {
      JavaLibraryDescription.Arg arg = (JavaLibraryDescription.Arg) targetNode.getConstructorArg();
      exportedDeps = arg.exportedDeps.get();
    } else if (targetNode.getType().equals(AndroidLibraryDescription.TYPE)) {
      AndroidLibraryDescription.Arg arg =
          (AndroidLibraryDescription.Arg) targetNode.getConstructorArg();
      exportedDeps = arg.exportedDeps.get();
    }

    ImmutableSet<BuildTarget> exportedDepsClosure = FluentIterable.from(exportedDeps)
        .transformAndConcat(
            new Function<BuildTarget, Iterable<BuildTarget>>() {
              @Override
              public Iterable<BuildTarget> apply(BuildTarget input) {
                return Iterables.concat(ImmutableSet.of(input), getExportedDepsClosure(input));
              }
            })
        .toSet();
    index.put(buildTarget, exportedDepsClosure);
    return exportedDepsClosure;
  }

}
