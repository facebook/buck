/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;
import javax.annotation.Nullable;

public class FakeBinaryBuildRule extends AbstractBuildRule implements BinaryBuildRule {

  private final Tool tool;

  protected FakeBinaryBuildRule(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem, Tool tool) {
    super(buildTarget, projectFilesystem);
    this.tool = tool;
  }

  @Override
  public Tool getExecutableCommand() {
    return tool;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }
}
