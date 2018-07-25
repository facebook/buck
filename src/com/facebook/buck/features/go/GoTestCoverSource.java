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

package com.facebook.buck.features.go;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Hashing;
import java.nio.file.Path;
import java.util.Map;
import java.util.SortedSet;

/**
 * GoTestCoverSource is responsible for annotating the source files via `go tool cover` utilty. The
 * annotated files should be used instead of original sources if the test coverage option is
 * desirable
 */
public class GoTestCoverSource extends AbstractBuildRule {
  @AddToRuleKey private final ImmutableSet<SourcePath> srcs;
  @AddToRuleKey private final Tool cover;
  @AddToRuleKey private final GoPlatform platform;
  @AddToRuleKey private final GoTestCoverStep.Mode coverageMode;

  private final ImmutableMap<String, Path> variables;
  private final ImmutableMap<SourcePath, SourcePath> coveredSources;
  private final ImmutableSet<SourcePath> testSources;
  private final ImmutableSortedSet<BuildRule> buildDeps;
  private final Path genDir;

  public GoTestCoverSource(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      GoPlatform platform,
      ImmutableSet<SourcePath> srcs,
      Tool cover,
      GoTestCoverStep.Mode coverageMode) {
    super(buildTarget, projectFilesystem);
    this.platform = platform;
    this.srcs = srcs;
    this.cover = cover;
    this.coverageMode = coverageMode;
    this.genDir = BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s/");
    this.buildDeps =
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(BuildableSupport.getDepsCollection(cover, ruleFinder))
            .addAll(ruleFinder.filterBuildRuleInputs(srcs))
            .build();

    // Generate annotation variables for source files
    ImmutableMap.Builder<String, Path> variables = ImmutableMap.builder();
    ImmutableMap.Builder<SourcePath, SourcePath> coveredSources = ImmutableMap.builder();
    ImmutableSet.Builder<SourcePath> testSources = ImmutableSet.builder();

    for (SourcePath path : srcs) {
      if (!isTestFile(pathResolver, buildTarget, path)) {
        Path srcPath = pathResolver.getAbsolutePath(path);

        variables.put(getVarName(srcPath), srcPath.getFileName());
        coveredSources.put(
            path,
            ExplicitBuildTargetSourcePath.of(buildTarget, genDir.resolve(srcPath.getFileName())));
      } else {
        testSources.add(path);
      }
    }

    this.testSources = testSources.build();
    this.coveredSources = coveredSources.build();
    this.variables = variables.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), genDir)));

    for (Map.Entry<SourcePath, SourcePath> entry : coveredSources.entrySet()) {
      steps.add(
          new GoTestCoverStep(
              getProjectFilesystem().getRootPath(),
              context.getSourcePathResolver().getAbsolutePath(entry.getKey()),
              genDir.resolve(context.getSourcePathResolver().getAbsolutePath(entry.getValue())),
              cover.getEnvironment(context.getSourcePathResolver()),
              cover.getCommandPrefix(context.getSourcePathResolver()),
              coverageMode));
    }

    buildableContext.recordArtifact(genDir);
    return steps.build();
  }

  private boolean isTestFile(
      SourcePathResolver resolver, BuildTarget buildTarget, SourcePath path) {
    return resolver.getSourcePathName(buildTarget, path).endsWith("_test.go");
  }

  static String getVarName(Path path) {
    return "Var_" + Hashing.sha256().hashString(path.getFileName().toString(), Charsets.UTF_8);
  }

  public ImmutableSet<SourcePath> getCoveredSources() {
    return new ImmutableSet.Builder<SourcePath>().addAll(coveredSources.values()).build();
  }

  public ImmutableSet<SourcePath> getTestSources() {
    return testSources;
  }

  public ImmutableMap<String, Path> getVariables() {
    return variables;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), genDir);
  }
}
