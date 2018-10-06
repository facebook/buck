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

package com.facebook.buck.android;

import com.facebook.buck.android.aapt.MiniAapt;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Objects;
import javax.annotation.Nullable;

public class AndroidResourceIndex extends AbstractBuildRuleWithDeclaredAndExtraDeps {
  @AddToRuleKey public final SourcePath resDir;

  public AndroidResourceIndex(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePath resDir) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.resDir = resDir;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                Objects.requireNonNull(getPathToOutputFile().getParent()))));

    steps.add(
        new WriteFileStep(
            getProjectFilesystem(), "", getPathToOutputFile(), /* executable */ false));

    steps.add(
        new MiniAapt(
            context.getSourcePathResolver(),
            getProjectFilesystem(),
            resDir,
            getPathToOutputFile(),
            ImmutableSet.of(),
            false,
            false,
            MiniAapt.ResourceCollectionType.ANDROID_RESOURCE_INDEX));
    return steps.build();
  }

  public Path getPathToOutputFile() {
    return getPathToOutputDir().resolve("resource_index.json");
  }

  private Path getPathToOutputDir() {
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "__%s");
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToOutputDir());
  }
}
