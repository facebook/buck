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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.HasOutputName;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.SortedSet;
import javax.annotation.Nullable;

/** Fast Genrule substitute to provide getSourcePathToOutput only */
class EmptyBuildRule extends AbstractBuildRule implements HasOutputName {
  private final String out;

  public EmptyBuildRule(BuildTarget buildTarget, ProjectFilesystem projectFilesystem, String out) {
    super(buildTarget, projectFilesystem);
    this.out = out;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    Path pathToOutDirectory =
        BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s");
    Path pathToOutFile = pathToOutDirectory.resolve(out);

    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), pathToOutFile);
  }

  @Override
  public String getOutputName() {
    return out;
  }
}
