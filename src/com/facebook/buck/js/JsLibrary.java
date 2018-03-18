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

package com.facebook.buck.js;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.json.JsonBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.function.BiFunction;

public class JsLibrary extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> libraryDependencies;

  @AddToRuleKey private final SourcePath filesDependency;

  @AddToRuleKey private final WorkerTool worker;

  protected JsLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePath filesDependency,
      ImmutableSortedSet<SourcePath> libraryDependencies,
      WorkerTool worker) {
    super(buildTarget, projectFilesystem, params);
    this.filesDependency = filesDependency;
    this.libraryDependencies = libraryDependencies;
    this.worker = worker;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return getBuildSteps(
        context,
        buildableContext,
        getSourcePathToOutput(),
        getProjectFilesystem(),
        worker,
        this::getJobArgs);
  }

  private String getJobArgs(SourcePathResolver resolver, Path outputPath) {
    ImmutableSortedSet<Flavor> flavors = getBuildTarget().getFlavors();

    return JsonBuilder.object()
        .addString("command", "library-dependencies")
        .addBoolean("release", flavors.contains(JsFlavors.RELEASE))
        .addString("rootPath", getProjectFilesystem().getRootPath().toString())
        .addString("platform", JsUtil.getPlatformString(flavors))
        .addString("outputPath", outputPath.toString())
        .addArray(
            "dependencyLibraryFilePaths",
            libraryDependencies
                .stream()
                .map(resolver::getAbsolutePath)
                .map(Path::toString)
                .collect(JsonBuilder.toArrayOfStrings()))
        .addString(
            "aggregatedSourceFilesFilePath", resolver.getAbsolutePath(filesDependency).toString())
        .toString();
  }

  @Override
  public BuildTargetSourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.jslib"));
  }

  public ImmutableSortedSet<SourcePath> getLibraryDependencies() {
    return libraryDependencies;
  }

  /**
   * An internal rule type to make he aggregation result of {@link JsFile} dependencies cacheable
   * independently of {@link JsLibrary} dependencies.
   */
  public static class Files extends AbstractBuildRuleWithDeclaredAndExtraDeps {

    @AddToRuleKey private final ImmutableSortedSet<SourcePath> sources;

    @AddToRuleKey private final WorkerTool worker;

    Files(
        BuildTarget target,
        ProjectFilesystem filesystem,
        BuildRuleParams params,
        ImmutableSortedSet<SourcePath> sources,
        WorkerTool worker) {
      super(target, filesystem, params);
      this.sources = sources;
      this.worker = worker;
    }

    @Override
    public ImmutableList<? extends Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      return JsLibrary.getBuildSteps(
          context,
          buildableContext,
          getSourcePathToOutput(),
          getProjectFilesystem(),
          worker,
          this::getJobArgs);
    }

    private String getJobArgs(SourcePathResolver resolver, Path outputPath) {
      ImmutableSortedSet<Flavor> flavors = getBuildTarget().getFlavors();

      return JsonBuilder.object()
          .addString("command", "library-files")
          .addBoolean("release", flavors.contains(JsFlavors.RELEASE))
          .addString("rootPath", getProjectFilesystem().getRootPath().toString())
          .addString("platform", JsUtil.getPlatformString(flavors))
          .addString("outputFilePath", outputPath.toString())
          .addArray(
              "sourceFilePaths",
              sources
                  .stream()
                  .map(resolver::getAbsolutePath)
                  .map(Path::toString)
                  .collect(JsonBuilder.toArrayOfStrings()))
          .toString();
    }

    @Override
    public BuildTargetSourcePath getSourcePathToOutput() {
      return ExplicitBuildTargetSourcePath.of(
          getBuildTarget(),
          BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.jslib"));
    }
  }

  private static ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext,
      BuildTargetSourcePath output,
      ProjectFilesystem filesystem,
      WorkerTool worker,
      BiFunction<SourcePathResolver, Path, String> jobArgs) {
    SourcePathResolver resolver = context.getSourcePathResolver();
    Path outputPath = resolver.getAbsolutePath(output);
    buildableContext.recordArtifact(resolver.getRelativePath(output));
    return ImmutableList.of(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), filesystem, outputPath)),
        JsUtil.workerShellStep(
            worker, jobArgs.apply(resolver, outputPath), output.getTarget(), resolver, filesystem));
  }
}
