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

package com.facebook.buck.cli;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndTargets;

import com.google.common.collect.ImmutableSet;

public class ProjectCommandTests {
  // Utility class, do not instantiate.
  private ProjectCommandTests() { }

  public static TargetGraphAndTargets createTargetGraph(
      TargetGraph projectGraph,
      ProjectCommandOptions.Ide targetIde,
      ImmutableSet<BuildTarget> passedInTargetsSet,
      ImmutableSet<String> defaultExcludePaths,
      boolean withTests
  ) {
    ProjectCommand.ProjectPredicates projectPredicates =
        ProjectCommand.getProjectPredicates(
            targetIde,
            passedInTargetsSet,
            defaultExcludePaths);

    ImmutableSet<BuildTarget> graphRoots;
    if (!passedInTargetsSet.isEmpty()) {
      graphRoots = passedInTargetsSet;
    } else {
      graphRoots = ProjectCommand.getRootsFromPredicate(
          projectGraph,
          projectPredicates.getProjectRootsPredicate());
    }
    ImmutableSet<BuildTarget> explicitTests;
    if (withTests) {
      explicitTests = TargetGraphAndTargets.getExplicitTestTargets(
          graphRoots,
          projectGraph);
    } else {
      explicitTests = ImmutableSet.of();
    }

    return TargetGraphAndTargets.create(
        graphRoots,
        projectGraph,
        projectPredicates.getAssociatedProjectPredicate(),
        withTests,
        explicitTests);
  }
}
