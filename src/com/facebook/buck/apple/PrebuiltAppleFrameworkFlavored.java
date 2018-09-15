/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.DefaultFilteredDirectoryCopier;
import com.google.common.collect.ImmutableList;
import com.google.common.io.MoreFiles;
import java.io.IOException;
import java.nio.file.Path;
import javax.annotation.Nullable;

public class PrebuiltAppleFrameworkFlavored extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private final Path out;

  @AddToRuleKey private final SourcePath frameworkPath;
  private final SourcePath frameworkBinaryPath;
  private final String frameworkName;

  protected PrebuiltAppleFrameworkFlavored(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathResolver pathResolver,
      SourcePath frameworkPath,
      SourcePath frameworkBinaryPath) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.frameworkPath = frameworkPath;
    this.frameworkBinaryPath = frameworkBinaryPath;
    this.frameworkName =
        MoreFiles.getNameWithoutExtension(pathResolver.getAbsolutePath(frameworkPath));
    this.out =
        BuildTargetPaths.getGenPath(getProjectFilesystem(), buildTarget, "%s")
            .resolve(pathResolver.getAbsolutePath(frameworkPath).getFileName().toString());
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    builder.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), out.getParent())));
    builder.add(
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), out))
            .withRecursive(true));

    Path relativeFrameworkPath = context.getSourcePathResolver().getRelativePath(frameworkPath);
    Path absoluteOutPath = context.getSourcePathResolver().getAbsolutePath(getSourcePathToOutput());
    builder.add(
        new AbstractExecutionStep("framework copy resources") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws IOException {
            DefaultFilteredDirectoryCopier.getInstance()
                .copyDir(
                    getProjectFilesystem(),
                    relativeFrameworkPath,
                    absoluteOutPath,
                    path ->
                        !path.startsWith(relativeFrameworkPath.resolve("Modules"))
                            && !path.startsWith(relativeFrameworkPath.resolve("Headers"))
                            && !path.startsWith(relativeFrameworkPath.resolve(frameworkName)));
            return StepExecutionResults.SUCCESS;
          }
        });

    builder.add(
        CopyStep.forFile(
            getProjectFilesystem(),
            context.getSourcePathResolver().getAbsolutePath(frameworkBinaryPath),
            absoluteOutPath.resolve(frameworkName)));

    buildableContext.recordArtifact(out);
    return builder.build();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), out);
  }
}
