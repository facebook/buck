/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

/**
 * A {@link SourcePath} that utilizes the output from a {@link BuildTarget} as the file it
 * represents.
 */
public class BuildTargetSourcePath extends AbstractSourcePath {
  private final BuildTarget buildTarget;

  public BuildTargetSourcePath(BuildTarget buildTarget) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
  }

  @Override
  public Path resolve(BuildContext context) {
    return resolve(context.getDependencyGraph());
  }

  @Override
  public Path resolve(DependencyGraph graph) {
    BuildRule rule = graph.findBuildRuleByTarget(buildTarget);
    if (rule == null) {
      throw new HumanReadableException("Cannot resolve: %s", buildTarget);
    }

    Path path = rule.getBuildable().getPathToOutputFile();
    if (path == null) {
      throw new HumanReadableException("No known output for: %s", buildTarget);
    }

    return path;
  }

  @Override
  public String asReference() {
    return buildTarget.getFullyQualifiedName();
  }

  public BuildTarget getTarget() {
    return buildTarget;
  }
}
