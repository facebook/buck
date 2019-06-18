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

package com.facebook.buck.features.js;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.json.JsonBuilder;
import com.facebook.buck.util.json.JsonBuilder.ObjectBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/** JsLibrary rule */
public class JsLibrary extends AbstractBuildRule {

  private final BuildableSupport.DepsSupplier depsSupplier;
  @AddToRuleKey private final JsLibraryImpl buildable;

  JsLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      BuildTargetSourcePath filesDependency,
      ImmutableSortedSet<SourcePath> libraryDependencies,
      WorkerTool worker) {
    super(buildTarget, projectFilesystem);
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, ruleFinder);
    this.buildable = new JsLibraryImpl(libraryDependencies, filesDependency, worker, buildTarget);
  }

  /** Abstract JsLibrary buildable implementation */
  abstract static class AbstractBuildable implements AddsToRuleKey {
    @AddToRuleKey final WorkerTool worker;
    @AddToRuleKey final BuildTarget buildTarget;

    protected AbstractBuildable(WorkerTool worker, BuildTarget buildTarget) {
      this.worker = worker;
      this.buildTarget = buildTarget;
    }

    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext, ProjectFilesystem filesystem) {
      SourcePathResolver resolver = context.getSourcePathResolver();
      ExplicitBuildTargetSourcePath output =
          ExplicitBuildTargetSourcePath.of(buildTarget, getOutputPath(filesystem));
      Path outputPath = resolver.getAbsolutePath(output);
      buildableContext.recordArtifact(resolver.getRelativePath(output));
      return ImmutableList.of(
          JsUtil.jsonWorkerShellStepAddingFlavors(
              worker,
              getJobArgs(resolver, outputPath, filesystem),
              output.getTarget(),
              resolver,
              filesystem));
    }

    Path getOutputPath(ProjectFilesystem filesystem) {
      return BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s.jslib");
    }

    abstract ObjectBuilder getJobArgs(
        SourcePathResolver resolver, Path outputPath, ProjectFilesystem filesystem);
  }

  /** JsLibrary buildable implementation */
  static class JsLibraryImpl extends AbstractBuildable {

    @AddToRuleKey private final ImmutableSortedSet<SourcePath> libraryDependencies;
    @AddToRuleKey private final BuildTargetSourcePath filesDependency;

    JsLibraryImpl(
        ImmutableSortedSet<SourcePath> libraryDependencies,
        BuildTargetSourcePath filesDependency,
        WorkerTool worker,
        BuildTarget buildTarget) {
      super(worker, buildTarget);
      this.libraryDependencies = libraryDependencies;
      this.filesDependency = filesDependency;
    }

    @Override
    ObjectBuilder getJobArgs(
        SourcePathResolver resolver, Path outputPath, ProjectFilesystem filesystem) {
      ImmutableSortedSet<Flavor> flavors = buildTarget.getFlavors();

      return JsonBuilder.object()
          .addString("command", "library-dependencies")
          .addBoolean("release", flavors.contains(JsFlavors.RELEASE))
          .addString("rootPath", filesystem.getRootPath().toString())
          .addString("platform", JsUtil.getPlatformString(flavors))
          .addString("outputPath", outputPath.toString())
          .addArray(
              "dependencyLibraryFilePaths",
              libraryDependencies.stream()
                  .map(resolver::getAbsolutePath)
                  .map(Path::toString)
                  .collect(JsonBuilder.toArrayOfStrings()))
          .addString(
              "aggregatedSourceFilesFilePath",
              resolver.getAbsolutePath(filesDependency).toString());
    }
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return getBuildable().getBuildSteps(context, buildableContext, getProjectFilesystem());
  }

  @Override
  public BuildTargetSourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(), getBuildable().getOutputPath(getProjectFilesystem()));
  }

  ImmutableSortedSet<SourcePath> getLibraryDependencies() {
    return getBuildable().libraryDependencies;
  }

  Stream<JsFile<?>> getJsFiles(SourcePathRuleFinder ruleFinder) {
    BuildRule fileRule = ruleFinder.getRule(getBuildable().filesDependency);
    if (fileRule instanceof Files) {
      return ((Files) fileRule).getJsFiles(ruleFinder);
    }
    throw new IllegalStateException(
        String.format(
            "JsLibrary rule %s was set up with 'filesDependency' that is not an instance of 'JsLibrary.Files'",
            getBuildTarget()));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
  }

  private JsLibraryImpl getBuildable() {
    return buildable;
  }

  /**
   * An internal rule type to make the aggregation result of {@link JsFile} dependencies cacheable
   * independently of {@link JsLibrary} dependencies.
   */
  static class Files extends AbstractBuildRuleWithDeclaredAndExtraDeps {

    @AddToRuleKey private final ImmutableSortedSet<BuildTargetSourcePath> sources;

    @AddToRuleKey private final WorkerTool worker;

    Files(
        BuildTarget target,
        ProjectFilesystem filesystem,
        BuildRuleParams params,
        ImmutableSortedSet<BuildTargetSourcePath> sources,
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

    private ObjectBuilder getJobArgs(SourcePathResolver resolver, Path outputPath) {
      ImmutableSortedSet<Flavor> flavors = getBuildTarget().getFlavors();

      return JsonBuilder.object()
          .addString("command", "library-files")
          .addBoolean("release", flavors.contains(JsFlavors.RELEASE))
          .addString("rootPath", getProjectFilesystem().getRootPath().toString())
          .addString("platform", JsUtil.getPlatformString(flavors))
          .addString("outputFilePath", outputPath.toString())
          .addArray(
              "sourceFilePaths",
              sources.stream()
                  .map(resolver::getAbsolutePath)
                  .map(Path::toString)
                  .collect(JsonBuilder.toArrayOfStrings()));
    }

    @Override
    public BuildTargetSourcePath getSourcePathToOutput() {
      return ExplicitBuildTargetSourcePath.of(
          getBuildTarget(),
          BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.jslib"));
    }

    Stream<JsFile<?>> getJsFiles(SourcePathRuleFinder ruleFinder) {
      return sources.stream().map(ruleFinder::getRule).map(this::buildRuleAsJsFile);
    }

    private JsFile<?> buildRuleAsJsFile(BuildRule x) {
      if (x instanceof JsFile) {
        return (JsFile<?>) x;
      }
      throw new IllegalStateException(
          String.format(
              "JsLibrary.Files rule %s has a source that is not a JsFile instance: %s",
              getBuildTarget(), x.getBuildTarget()));
    }
  }

  private static ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext,
      BuildTargetSourcePath output,
      ProjectFilesystem filesystem,
      WorkerTool worker,
      BiFunction<SourcePathResolver, Path, ObjectBuilder> jobArgs) {
    SourcePathResolver resolver = context.getSourcePathResolver();
    Path outputPath = resolver.getAbsolutePath(output);
    buildableContext.recordArtifact(resolver.getRelativePath(output));
    return ImmutableList.of(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), filesystem, outputPath)),
        JsUtil.jsonWorkerShellStepAddingFlavors(
            worker, jobArgs.apply(resolver, outputPath), output.getTarget(), resolver, filesystem));
  }
}
