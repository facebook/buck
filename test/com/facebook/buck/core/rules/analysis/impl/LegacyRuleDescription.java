/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.rules.analysis.impl;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.BuildTargetSourcePathToArtifactConverter;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.LegacyProviderCompatibleDescription;
import com.facebook.buck.core.rules.ProviderCreationContext;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import javax.annotation.Nullable;

public class LegacyRuleDescription
    implements LegacyProviderCompatibleDescription<LegacyRuleDescriptionArg> {

  @Override
  public Class<LegacyRuleDescriptionArg> getConstructorArgType() {
    return LegacyRuleDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      LegacyRuleDescriptionArg args) {

    SourcePath declaredOutput =
        Iterables.getOnlyElement(
                context
                    .getProviderInfoCollection()
                    .get(DefaultInfo.PROVIDER)
                    .get()
                    .defaultOutputs())
            .asBound()
            .getSourcePath();

    FakeBuildRule rule =
        new FakeBuildRule(buildTarget, context.getProjectFilesystem(), params) {

          @Nullable
          @Override
          public SourcePath getSourcePathToOutput() {
            return declaredOutput;
          }

          @Override
          public ImmutableList<Step> getBuildSteps(
              BuildContext context, BuildableContext buildableContext) {
            ImmutableList.Builder<Step> steps = ImmutableList.builder();

            Map<String, Object> data = new HashMap<>();
            data.put("target", buildTarget.getShortName());
            data.put("val", args.getVal());

            // AbstractLegacyRuleDescriptionArg doesn't implement HasSrcs, but we still write an
            // empty list into the JSON for consistency in tests
            List<Path> srcs = new ArrayList<>();
            data.put("srcs", srcs);

            SortedSet<BuildRule> depRules = getBuildDeps();
            List<Object> deps = new ArrayList<>();
            data.put("dep", deps);
            Path output = context.getSourcePathResolver().getRelativePath(getSourcePathToOutput());
            data.put("outputs", ImmutableList.of(output));

            ImmutableList<Path> toRead =
                depRules.stream()
                    .map(BuildRule::getSourcePathToOutput)
                    .map(sourcePath -> context.getSourcePathResolver().getAbsolutePath(sourcePath))
                    .collect(ImmutableList.toImmutableList());

            steps.add(
                new AbstractExecutionStep("read vals") {
                  @Override
                  public StepExecutionResult execute(ExecutionContext ctx) throws IOException {
                    for (Path path : toRead) {
                      deps.add(ObjectMappers.createParser(path).readValueAs(Map.class));
                    }
                    return StepExecutionResult.of(0);
                  }
                });

            steps.addAll(
                MakeCleanDirectoryStep.of(
                    BuildCellRelativePath.of(
                        context
                            .getSourcePathResolver()
                            .getRelativePath(getSourcePathToOutput())
                            .getParent())));
            steps.add(
                new AbstractExecutionStep("write json") {
                  @Override
                  public StepExecutionResult execute(ExecutionContext ctx) throws IOException {
                    getProjectFilesystem()
                        .writeContentsToPath(
                            ObjectMappers.WRITER.writeValueAsString(data),
                            context
                                .getSourcePathResolver()
                                .getRelativePath(getSourcePathToOutput()));
                    return StepExecutionResult.of(0);
                  }
                });

            return steps.build();
          }
        };
    return rule;
  }

  @Override
  public ProviderInfoCollection createProviders(
      ProviderCreationContext context, BuildTarget buildTarget, LegacyRuleDescriptionArg args) {
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    SourcePath sourcePath =
        ExplicitBuildTargetSourcePath.of(
            buildTarget, BuildPaths.getGenDir(filesystem, buildTarget).resolve("output"));
    Artifact artifact = BuildTargetSourcePathToArtifactConverter.convert(filesystem, sourcePath);

    return ProviderInfoCollectionImpl.builder()
        .build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableSet.of(artifact)));
  }

  @RuleArg
  interface AbstractLegacyRuleDescriptionArg extends BuildRuleArg, HasDeclaredDeps {
    int getVal();
  }
}
