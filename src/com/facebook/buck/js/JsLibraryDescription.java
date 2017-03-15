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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

import javax.annotation.Nullable;

public class JsLibraryDescription implements Description<JsLibraryDescription.Arg>, Flavored {

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    // this params object is used as base for the JsLibrary build rule, but also for all dynamically
    // created JsFile rules.
    // For the JsLibrary case, we want to propagate flavors to library dependencies
    // For the JsFile case, we only want to depend on the worker, not on any libraries
    params = JsUtil.withWorkerDependencyOnly(params, resolver, args.worker);

    final WorkerTool worker = resolver.getRuleWithType(args.worker, WorkerTool.class);
    final ImmutableBiMap<SourcePath, Flavor> sourcesToFlavors =
        mapSourcesToFlavors(resolver, args.srcs);
    final Optional<SourcePath> filePath = JsFlavors.extractSourcePath(
        sourcesToFlavors.inverse(),
        params.getBuildTarget().getFlavors().stream());

    return filePath.isPresent()
        ? createFileBuildRule(params, resolver, args, filePath.get(), worker)
        : new LibraryBuilder(targetGraph, resolver, params, sourcesToFlavors)
            .setSources(args.srcs)
            .setLibraryDependencies(args.libs)
            .build(worker);
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return JsFlavors.validateFlavors(flavors);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public Optional<String> extraArgs;
    public ImmutableSortedSet<SourcePath> srcs;
    public ImmutableSortedSet<BuildTarget> libs = ImmutableSortedSet.of();
    public BuildTarget worker;
  }

  private static class LibraryBuilder {
    private final TargetGraph targetGraph;
    private final BuildRuleResolver resolver;
    private final ImmutableBiMap<SourcePath, Flavor> sourcesToFlavors;
    private final BuildRuleParams baseParams;

    @Nullable
    private ImmutableList<JsFile> sourceFiles;

    @Nullable
    private ImmutableList<BuildRule> libraryDependencies;

    private LibraryBuilder(
        TargetGraph targetGraph, BuildRuleResolver resolver,
        BuildRuleParams baseParams,
        ImmutableBiMap<SourcePath, Flavor> sourcesToFlavors) {
      this.targetGraph = targetGraph;
      this.baseParams = baseParams;
      this.resolver = resolver;
      this.sourcesToFlavors = sourcesToFlavors;
    }

    private LibraryBuilder setSources(
        ImmutableSortedSet<SourcePath> sources) throws NoSuchBuildTargetException {
      final ImmutableList.Builder<JsFile> builder = ImmutableList.builder();
      for (SourcePath sourcePath : sources) {
        builder.add(this.requireJsFile(sourcePath));
      }
      this.sourceFiles = builder.build();
      return this;
    }

    private LibraryBuilder setLibraryDependencies(
        ImmutableSortedSet<BuildTarget> libraryDependencies)
        throws NoSuchBuildTargetException {

      BuildTarget buildTarget = baseParams.getBuildTarget();
      final BuildTarget[] targets = libraryDependencies.stream()
          .map(t -> JsUtil.verifyIsJsLibraryTarget(t, buildTarget, targetGraph))
          .map(hasFlavors() ? this::addFlavorsToLibraryTarget : Function.identity())
          .toArray(BuildTarget[]::new);

      final ImmutableList.Builder<BuildRule> builder = ImmutableList.builder();
      for (BuildTarget target : targets) {
        // `requireRule()` needed for dependencies to flavored versions
        builder.add(resolver.requireRule(target));
      }
      this.libraryDependencies = builder.build();
      return this;
    }

    private JsLibrary build(WorkerTool worker) {
      Preconditions.checkNotNull(sourceFiles, "No source files set");
      Preconditions.checkNotNull(libraryDependencies, "No library dependencies set");

      return new JsLibrary(
          baseParams.appendExtraDeps(
              Iterables.concat(sourceFiles, libraryDependencies)),
          sourceFiles.stream()
              .map(BuildRule::getSourcePathToOutput)
              .collect(MoreCollectors.toImmutableSortedSet()),
          libraryDependencies.stream()
              .map(BuildRule::getSourcePathToOutput)
              .collect(MoreCollectors.toImmutableSortedSet()),
          worker);
    }

    private boolean hasFlavors() {
      return !baseParams.getBuildTarget().getFlavors().isEmpty();
    }

    private JsFile requireJsFile(SourcePath file) throws NoSuchBuildTargetException {
      final Flavor fileFlavor = sourcesToFlavors.get(file);
      final BuildTarget target = baseParams.getBuildTarget().withAppendedFlavors(fileFlavor);
      resolver.requireRule(target);
      return resolver.getRuleWithType(target, JsFile.class);
    }

    private BuildTarget addFlavorsToLibraryTarget(BuildTarget unflavored) {
      return unflavored.withAppendedFlavors(baseParams.getBuildTarget().getFlavors());
    }

  }

  private static <A extends Arg> BuildRule createFileBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      SourcePath sourcePath,
      WorkerTool worker) throws NoSuchBuildTargetException {
    final BuildTarget target = params.getBuildTarget();

    if (target.getFlavors().contains(JsFlavors.PROD)) {
      final BuildTarget devTarget = withFileFlavorOnly(target);
      final BuildRule devFile = resolver.requireRule(devTarget);
      return new JsFile.JsFileProd(
          params.appendExtraDeps(devFile),
          resolver.getRuleWithType(devTarget, JsFile.class).getSourcePathToOutput(),
          args.extraArgs,
          worker
      );
    } else {
      return new JsFile.JsFileDev(
          new SourcePathRuleFinder(resolver).getRule(sourcePath)
              .map(params::appendExtraDeps)
              .orElse(params),
          sourcePath,
          Optional.empty(),
          args.extraArgs,
          worker
      );
    }
  }

  private static BuildTarget withFileFlavorOnly(BuildTarget target) {
    return target.withFlavors(
        target.getFlavors()
            .stream()
            .filter(JsFlavors::isFileFlavor)
            .toArray(Flavor[]::new));
  }

  private static ImmutableBiMap<SourcePath, Flavor> mapSourcesToFlavors(
      BuildRuleResolver resolver,
      ImmutableSortedSet<SourcePath> sourcePaths) {
    final SourcePathResolver sourcePathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(resolver));

    final ImmutableBiMap.Builder<SourcePath, Flavor> builder = ImmutableBiMap.builder();
    for (SourcePath sourcePath : sourcePaths) {
      final Path relativePath = sourcePathResolver.getRelativePath(sourcePath);
      builder.put(sourcePath, JsFlavors.fileFlavorForSourcePath(relativePath));
    }
    return builder.build();
  }
}
