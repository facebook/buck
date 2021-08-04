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

package com.facebook.buck.features.python;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Optional;
import java.util.stream.Stream;

public class PythonSourceDatabase extends ModernBuildRule<PythonSourceDatabase.Impl>
    implements HasRuntimeDeps {

  private final PythonComponents sources;
  private final PythonComponentsGroup dependencies;

  private PythonSourceDatabase(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      PythonComponents sources,
      PythonComponentsGroup dependencies) {
    super(buildTarget, filesystem, ruleFinder, new Impl(sources, dependencies));
    this.sources = sources;
    this.dependencies = dependencies;
  }

  static PythonSourceDatabase from(
      BuildTarget target,
      ProjectFilesystem filesystem,
      ActionGraphBuilder graphBuilder,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      PythonComponents sources,
      Iterable<BuildRule> firstOrderDeps) {
    PythonComponentsGroup.Builder builder = new PythonComponentsGroup.Builder();
    PythonUtil.forEachDependency(
        graphBuilder,
        pythonPlatform,
        cxxPlatform,
        firstOrderDeps,
        packagable ->
            packagable
                .getPythonModulesForTyping(pythonPlatform, cxxPlatform, graphBuilder)
                .ifPresent(modules -> builder.putComponent(packagable.getBuildTarget(), modules)),
        extension -> {},
        linkable -> {},
        cxxResourcesProvider -> {});
    return new PythonSourceDatabase(target, filesystem, graphBuilder, sources, builder.build());
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(Impl.OUTPUT_PATH);
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return Streams.concat(
            BuildableSupport.deriveDeps(sources, buildRuleResolver),
            BuildableSupport.deriveDeps(dependencies, buildRuleResolver))
        .map(BuildRule::getBuildTarget);
  }

  @VisibleForTesting
  PythonSourceDatabaseEntry getSourceDatabaseForTesting(SourcePathResolverAdapter resolver)
      throws IOException {
    ImmutableMap.Builder<String, String> sourcesBuilder = ImmutableMap.builder();
    sources
        .resolvePythonComponents(resolver)
        .forEachPythonComponent(
            (dst, src) ->
                sourcesBuilder.put(
                    dst.toString(),
                    getProjectFilesystem().getRootPath().relativize(src).toString()));
    ImmutableMap.Builder<String, String> dependenciesBuilder = ImmutableMap.builder();
    dependencies
        .resolve(resolver, true)
        .forEachModule(
            Optional.empty(),
            (dst, src) ->
                dependenciesBuilder.put(
                    dst.toString(),
                    getProjectFilesystem().getRootPath().relativize(src).toString()));
    return ImmutablePythonSourceDatabaseEntry.ofImpl(
        sourcesBuilder.build(), dependenciesBuilder.build());
  }

  static class Impl implements Buildable {

    private static final OutputPath OUTPUT_PATH = new OutputPath("db.json");

    // TODO(agallagher): We technically don't need build time deps on everything here to just
    // generate the DB, since it just needs to materialize the paths (and runtime deps above
    // makes sure all source deps are materialized eventually).
    @AddToRuleKey private final PythonComponents sources;
    @AddToRuleKey private final PythonComponentsGroup dependencies;

    public Impl(PythonComponents sources, PythonComponentsGroup dependencies) {
      this.sources = sources;
      this.dependencies = dependencies;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return ImmutableList.of(
          new AbstractExecutionStep("write-source-db") {

            private final RelPath output = outputPathResolver.resolvePath(OUTPUT_PATH);
            private final PythonComponents.Resolved resolvedSources =
                sources.resolvePythonComponents(buildContext.getSourcePathResolver());
            private final PythonResolvedComponentsGroup resolvedDependencies =
                dependencies.resolve(buildContext.getSourcePathResolver(), true);

            @Override
            public StepExecutionResult execute(StepExecutionContext context) throws IOException {
              try (OutputStream stream =
                  new BufferedOutputStream(
                      Files.newOutputStream(filesystem.resolve(output.getPath())))) {
                PythonSourceDatabaseEntry.serialize(
                    resolvedSources,
                    resolvedDependencies,
                    Optional.of(context.getBuildCellRootPath()),
                    stream);
              }
              return StepExecutionResult.of(0);
            }
          });
    }
  }
}
