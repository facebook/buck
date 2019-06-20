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
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.json.JsonBuilder;
import com.facebook.buck.util.json.JsonBuilder.ObjectBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.stream.Stream;

/** JsLibrary rule */
public class JsLibrary extends ModernBuildRule<JsLibrary.JsLibraryImpl> {

  JsLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      BuildTargetSourcePath filesDependency,
      ImmutableSortedSet<SourcePath> libraryDependencies,
      WorkerTool worker) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new JsLibraryImpl(
            libraryDependencies, filesDependency, worker, buildTarget, projectFilesystem));
  }

  /** Abstract JsLibrary buildable implementation */
  abstract static class AbstractBuildable implements Buildable {
    @AddToRuleKey final WorkerTool worker;
    @AddToRuleKey final BuildTarget buildTarget;
    @AddToRuleKey final OutputPath output;

    protected AbstractBuildable(
        WorkerTool worker, BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
      this.worker = worker;
      this.buildTarget = buildTarget;
      this.output =
          new PublicOutputPath(
              BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s.jslib"));
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      SourcePathResolver resolver = buildContext.getSourcePathResolver();
      Path outputPath = filesystem.resolve(outputPathResolver.resolvePath(output));
      return ImmutableList.of(
          JsUtil.jsonWorkerShellStepAddingFlavors(
              worker,
              getJobArgs(resolver, outputPath, filesystem),
              buildTarget,
              resolver,
              filesystem));
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
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem) {
      super(worker, buildTarget, projectFilesystem);
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
  public BuildTargetSourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
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

  /**
   * An internal rule type to make the aggregation result of {@link JsFile} dependencies cacheable
   * independently of {@link JsLibrary} dependencies.
   */
  static class Files extends AbstractBuildRule {

    private final BuildableSupport.DepsSupplier depsSupplier;
    @AddToRuleKey private final FilesImpl buildable;

    Files(
        BuildTarget target,
        ProjectFilesystem filesystem,
        SourcePathRuleFinder ruleFinder,
        ImmutableSortedSet<BuildTargetSourcePath> sources,
        WorkerTool worker) {
      super(target, filesystem);
      this.buildable = new FilesImpl(sources, worker, target);
      this.depsSupplier = BuildableSupport.buildDepsSupplier(this, ruleFinder);
    }

    @Override
    public ImmutableList<? extends Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      return getBuildable().getBuildSteps(context, buildableContext, getProjectFilesystem());
    }

    @Override
    public BuildTargetSourcePath getSourcePathToOutput() {
      return ExplicitBuildTargetSourcePath.of(
          getBuildTarget(), getBuildable().getOutputPath(getProjectFilesystem()));
    }

    Stream<JsFile<?>> getJsFiles(SourcePathRuleFinder ruleFinder) {
      return getBuildable().sources.stream().map(ruleFinder::getRule).map(this::buildRuleAsJsFile);
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

    /** JsLibrary.Files buildable implementation */
    static class FilesImpl implements AddsToRuleKey {

      @AddToRuleKey final WorkerTool worker;
      @AddToRuleKey private final BuildTarget buildTarget;
      @AddToRuleKey private final ImmutableSortedSet<BuildTargetSourcePath> sources;

      FilesImpl(
          ImmutableSortedSet<BuildTargetSourcePath> sources,
          WorkerTool worker,
          BuildTarget buildTarget) {
        this.worker = worker;
        this.buildTarget = buildTarget;
        this.sources = sources;
      }

      public ImmutableList<Step> getBuildSteps(
          BuildContext context, BuildableContext buildableContext, ProjectFilesystem filesystem) {
        SourcePathResolver resolver = context.getSourcePathResolver();
        ExplicitBuildTargetSourcePath output =
            ExplicitBuildTargetSourcePath.of(buildTarget, getOutputPath(filesystem));
        Path outputPath = resolver.getAbsolutePath(output);
        buildableContext.recordArtifact(resolver.getRelativePath(output));
        return ImmutableList.of(
            RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), filesystem, outputPath)),
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

      private ObjectBuilder getJobArgs(
          SourcePathResolver resolver, Path outputPath, ProjectFilesystem filesystem) {
        ImmutableSortedSet<Flavor> flavors = buildTarget.getFlavors();

        return JsonBuilder.object()
            .addString("command", "library-files")
            .addBoolean("release", flavors.contains(JsFlavors.RELEASE))
            .addString("rootPath", filesystem.getRootPath().toString())
            .addString("platform", JsUtil.getPlatformString(flavors))
            .addString("outputFilePath", outputPath.toString())
            .addArray(
                "sourceFilePaths",
                sources.stream()
                    .map(resolver::getAbsolutePath)
                    .map(Path::toString)
                    .collect(JsonBuilder.toArrayOfStrings()));
      }
    }

    FilesImpl getBuildable() {
      return buildable;
    }

    @Override
    public SortedSet<BuildRule> getBuildDeps() {
      return depsSupplier.get();
    }
  }
}
