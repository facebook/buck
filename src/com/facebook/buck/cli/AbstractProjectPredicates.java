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

import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AssociatedTargetNodePredicate;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ProjectConfigDescription;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Predicate;

import org.immutables.value.Value;

/**
 * Value type containing predicates to identify nodes associated
 * with an IDE project.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractProjectPredicates {

  /**
   * {@link Predicate} returning nodes that represent roots of the IDE
   * project.
   */
  @Value.Parameter
  public abstract Predicate<TargetNode<?, ?>> getProjectRootsPredicate();

  /**
   * {@link AssociatedTargetNodePredicate} returning nodes associated
   * with the IDE project.
   */
  @Value.Parameter
  public abstract AssociatedTargetNodePredicate getAssociatedProjectPredicate();

  /**
   * Creates a {@link ProjectPredicates} value type configured for
   * the specified IDE.
   */
  public static ProjectPredicates forIde(ProjectCommand.Ide targetIde) {
    Predicate<TargetNode<?, ?>> projectRootsPredicate;
    AssociatedTargetNodePredicate associatedProjectPredicate;

    // Prepare the predicates to create the project graph based on the IDE.
    switch (targetIde) {
      case INTELLIJ:
        projectRootsPredicate =
            input ->
                input.getType() == Description.getBuildRuleType(ProjectConfigDescription.class);
        associatedProjectPredicate = (targetNode, targetGraph) -> {
          ProjectConfigDescription.Arg projectArg;
          if (targetNode.getType() ==
              Description.getBuildRuleType(ProjectConfigDescription.class)) {
            projectArg = (ProjectConfigDescription.Arg) targetNode.getConstructorArg();
          } else {
            return false;
          }

          BuildTarget projectTarget = null;
          if (projectArg.srcTarget.isPresent()) {
            projectTarget = projectArg.srcTarget.get();
          } else if (projectArg.testTarget.isPresent()) {
            projectTarget = projectArg.testTarget.get();
          }
          return (projectTarget != null && targetGraph.getOptional(projectTarget).isPresent());
        };
        break;
      case XCODE:
        projectRootsPredicate =
            input ->
                Description.getBuildRuleType(XcodeWorkspaceConfigDescription.class) ==
                    input.getType();
        associatedProjectPredicate = (targetNode, targetGraph) -> false;
        break;
      default:
        // unreachable
        throw new IllegalStateException("'ide' should always be of type 'INTELLIJ' or 'XCODE'");
    }

    return ProjectPredicates.of(
        projectRootsPredicate,
        associatedProjectPredicate);
  }
}
