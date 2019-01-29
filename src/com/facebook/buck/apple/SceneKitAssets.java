/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class SceneKitAssets extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  public static final Flavor FLAVOR = InternalFlavor.of("scenekit-assets");

  @AddToRuleKey private final Optional<Tool> copySceneKitAssets;

  @AddToRuleKey private final ImmutableSet<SourcePath> sceneKitAssetsPaths;

  @AddToRuleKey private final String sdkName;

  @AddToRuleKey private final String minOSVersion;

  private final Path outputDir;

  SceneKitAssets(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      AppleCxxPlatform appleCxxPlatform,
      ImmutableSet<SourcePath> sceneKitAssetsPaths) {
    super(buildTarget, projectFilesystem, params);
    this.sceneKitAssetsPaths = sceneKitAssetsPaths;
    String outputDirString =
        BuildTargetPaths.getGenPath(getProjectFilesystem(), buildTarget, "%s").toString();
    this.outputDir = Paths.get(outputDirString);
    this.sdkName = appleCxxPlatform.getAppleSdk().getName();
    this.minOSVersion = appleCxxPlatform.getMinVersion();
    this.copySceneKitAssets = appleCxxPlatform.getCopySceneKitAssets();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
    stepsBuilder.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), outputDir)));
    for (SourcePath inputPath : sceneKitAssetsPaths) {
      Path absoluteInputPath = context.getSourcePathResolver().getAbsolutePath(inputPath);

      if (copySceneKitAssets.isPresent()) {
        stepsBuilder.add(
            new ShellStep(getProjectFilesystem().getRootPath()) {
              @Override
              protected ImmutableList<String> getShellCommandInternal(
                  ExecutionContext executionContext) {
                ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
                commandBuilder.addAll(
                    copySceneKitAssets.get().getCommandPrefix(context.getSourcePathResolver()));
                commandBuilder.add(
                    absoluteInputPath.toString(),
                    "-o",
                    getProjectFilesystem()
                        .resolve(outputDir)
                        .resolve(absoluteInputPath.getFileName())
                        .toString(),
                    "--target-platform=" + sdkName,
                    "--target-version=" + minOSVersion);

                return commandBuilder.build();
              }

              @Override
              public ImmutableMap<String, String> getEnvironmentVariables(
                  ExecutionContext executionContext) {
                return copySceneKitAssets.get().getEnvironment(context.getSourcePathResolver());
              }

              @Override
              public String getShortName() {
                return "copy-scenekit-assets";
              }
            });
      } else {
        stepsBuilder.add(
            CopyStep.forDirectory(
                getProjectFilesystem(),
                absoluteInputPath,
                outputDir,
                CopyStep.DirectoryMode.CONTENTS_ONLY));
      }
    }
    buildableContext.recordArtifact(
        context.getSourcePathResolver().getRelativePath(getSourcePathToOutput()));
    return stepsBuilder.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputDir);
  }
}
