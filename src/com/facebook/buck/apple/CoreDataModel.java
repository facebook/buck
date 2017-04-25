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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CoreDataModel extends AbstractBuildRule {

  public static final Flavor FLAVOR = InternalFlavor.of("core-data-model");

  @AddToRuleKey private final String moduleName;

  @AddToRuleKey private final Tool momc;

  @AddToRuleKey private final ImmutableSet<SourcePath> dataModelPaths;

  @AddToRuleKey private final String sdkName;

  @AddToRuleKey private final String minOSVersion;

  private final Path sdkRoot;
  private final Path outputDir;

  CoreDataModel(
      BuildRuleParams params,
      AppleCxxPlatform appleCxxPlatform,
      String moduleName,
      ImmutableSet<SourcePath> dataModelPaths) {
    super(params);
    this.moduleName = moduleName;
    this.dataModelPaths = dataModelPaths;
    String outputDirString =
        BuildTargets.getGenPath(getProjectFilesystem(), params.getBuildTarget(), "%s")
            .toString()
            .replace('#', '-'); // momc doesn't like # in paths
    this.outputDir = Paths.get(outputDirString);
    this.sdkName = appleCxxPlatform.getAppleSdk().getName();
    this.sdkRoot = appleCxxPlatform.getAppleSdkPaths().getSdkPath();
    this.minOSVersion = appleCxxPlatform.getMinVersion();
    this.momc = appleCxxPlatform.getMomc();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
    stepsBuilder.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), outputDir));
    for (SourcePath dataModelPath : dataModelPaths) {
      stepsBuilder.add(
          new ShellStep(getProjectFilesystem().getRootPath()) {
            @Override
            protected ImmutableList<String> getShellCommandInternal(
                ExecutionContext executionContext) {
              ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();

              commandBuilder.addAll(momc.getCommandPrefix(context.getSourcePathResolver()));
              commandBuilder.add(
                  "--sdkroot",
                  sdkRoot.toString(),
                  "--" + sdkName + "-deployment-target",
                  minOSVersion,
                  "--module",
                  moduleName,
                  context.getSourcePathResolver().getAbsolutePath(dataModelPath).toString(),
                  getProjectFilesystem().resolve(outputDir).toString());

              return commandBuilder.build();
            }

            @Override
            public ImmutableMap<String, String> getEnvironmentVariables(
                ExecutionContext executionContext) {
              return momc.getEnvironment(context.getSourcePathResolver());
            }

            @Override
            public String getShortName() {
              return "momc";
            }
          });
    }
    buildableContext.recordArtifact(outputDir);
    return stepsBuilder.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), outputDir);
  }
}
