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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.features.python.toolchain.PythonEnvironment;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PythonPackagedBinary extends PythonBinary implements HasRuntimeDeps {

  @AddToRuleKey private final Tool builder;
  @AddToRuleKey private final ImmutableList<Arg> buildArgs;
  private final Tool pathToPexExecuter;
  @AddToRuleKey private final String mainModule;
  @AddToRuleKey private final PythonEnvironment pythonEnvironment;
  @AddToRuleKey private final ImmutableSet<String> preloadLibraries;
  private final boolean cache;
  private final ImmutableSortedSet<BuildRule> buildDeps;

  PythonPackagedBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Supplier<? extends SortedSet<BuildRule>> originalDeclareDeps,
      PythonPlatform pythonPlatform,
      Tool builder,
      ImmutableList<Arg> buildArgs,
      Tool pathToPexExecuter,
      String pexExtension,
      PythonEnvironment pythonEnvironment,
      String mainModule,
      PythonPackageComponents components,
      ImmutableSet<String> preloadLibraries,
      boolean cache,
      boolean legacyOutputPath) {
    super(
        buildTarget,
        projectFilesystem,
        originalDeclareDeps,
        pythonPlatform,
        mainModule,
        components,
        preloadLibraries,
        pexExtension,
        legacyOutputPath);
    this.builder = builder;
    this.buildArgs = buildArgs;
    this.pathToPexExecuter = pathToPexExecuter;
    this.pythonEnvironment = pythonEnvironment;
    this.mainModule = mainModule;
    this.preloadLibraries = preloadLibraries;
    this.cache = cache;
    this.buildDeps =
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(components.getDeps(ruleFinder))
            .addAll(BuildableSupport.getDepsCollection(builder, ruleFinder))
            .addAll(
                buildArgs.stream()
                    .flatMap(a -> BuildableSupport.deriveDeps(a, ruleFinder))
                    .collect(Collectors.toList()))
            .build();
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    return new CommandTool.Builder(pathToPexExecuter)
        .addArg(SourcePathArg.of(getSourcePathToOutput()))
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    Path binPath = context.getSourcePathResolver().getRelativePath(getSourcePathToOutput());

    // Make sure the parent directory exists.
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), binPath.getParent())));

    // Delete any other pex that was there (when switching between pex styles).
    steps.add(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), binPath),
            true));

    SourcePathResolverAdapter resolver = context.getSourcePathResolver();

    // Generate and return the PEX build step.
    steps.add(
        new PexStep(
            getProjectFilesystem(),
            builder.getEnvironment(resolver),
            ImmutableList.<String>builder()
                .addAll(builder.getCommandPrefix(resolver))
                .addAll(Arg.stringify(buildArgs, resolver))
                .build(),
            pythonEnvironment.getPythonPath(),
            pythonEnvironment.getPythonVersion(),
            binPath,
            mainModule,
            getComponents().resolve(resolver),
            preloadLibraries));

    // Record the executable package for caching.
    buildableContext.recordArtifact(binPath);

    return steps.build();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return RichStream.from(super.getRuntimeDeps(buildRuleResolver))
        .concat(
            BuildableSupport.getDeps(pathToPexExecuter, buildRuleResolver)
                .map(BuildRule::getBuildTarget));
  }

  @Override
  public boolean isCacheable() {
    return cache;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }
}
