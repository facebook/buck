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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.Pair;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.immutables.value.Value;

public class JsLibraryDescription implements Description<JsLibraryDescriptionArg>, Flavored {

  static final ImmutableSet<FlavorDomain<?>> FLAVOR_DOMAINS =
      ImmutableSet.of(JsFlavors.PLATFORM_DOMAIN, JsFlavors.OPTIMIZATION_DOMAIN);

  @Override
  public Class<JsLibraryDescriptionArg> getConstructorArgType() {
    return JsLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      JsLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {

    params.getBuildTarget().getBasePath();
    // this params object is used as base for the JsLibrary build rule, but also for all dynamically
    // created JsFile rules.
    // For the JsLibrary case, we want to propagate flavors to library dependencies
    // For the JsFile case, we only want to depend on the worker, not on any libraries
    params = JsUtil.withWorkerDependencyOnly(params, resolver, args.getWorker());

    final WorkerTool worker = resolver.getRuleWithType(args.getWorker(), WorkerTool.class);
    final SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    final SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);
    final ImmutableBiMap<Either<SourcePath, Pair<SourcePath, String>>, Flavor> sourcesToFlavors =
        mapSourcesToFlavors(sourcePathResolver, args.getSrcs());
    final Optional<Either<SourcePath, Pair<SourcePath, String>>> file =
        JsFlavors.extractSourcePath(
            sourcesToFlavors.inverse(), params.getBuildTarget().getFlavors().stream());

    if (file.isPresent()) {
      return params.getBuildTarget().getFlavors().contains(JsFlavors.RELEASE)
          ? createReleaseFileRule(params, resolver, args, worker)
          : createDevFileRule(params, ruleFinder, sourcePathResolver, args, file.get(), worker);
    } else {
      return new LibraryBuilder(targetGraph, resolver, params, sourcesToFlavors)
          .setSources(args.getSrcs())
          .setLibraryDependencies(args.getLibs())
          .build(worker);
    }
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return JsFlavors.validateFlavors(flavors, FLAVOR_DOMAINS);
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(FLAVOR_DOMAINS);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractJsLibraryDescriptionArg extends CommonDescriptionArg {
    Optional<String> getExtraArgs();

    ImmutableSet<Either<SourcePath, Pair<SourcePath, String>>> getSrcs();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getLibs();

    BuildTarget getWorker();

    @Hint(isDep = false, isInput = false)
    Optional<String> getBasePath();
  }

  private static class LibraryBuilder {
    private final TargetGraph targetGraph;
    private final BuildRuleResolver resolver;
    private final ImmutableBiMap<Either<SourcePath, Pair<SourcePath, String>>, Flavor>
        sourcesToFlavors;
    private final BuildRuleParams baseParams;
    private final BuildTarget fileBaseTarget;

    @Nullable private ImmutableList<JsFile> sourceFiles;

    @Nullable private ImmutableList<BuildRule> libraryDependencies;

    private LibraryBuilder(
        TargetGraph targetGraph,
        BuildRuleResolver resolver,
        BuildRuleParams baseParams,
        ImmutableBiMap<Either<SourcePath, Pair<SourcePath, String>>, Flavor> sourcesToFlavors) {
      this.targetGraph = targetGraph;
      this.baseParams = baseParams;
      this.resolver = resolver;
      this.sourcesToFlavors = sourcesToFlavors;

      BuildTarget buildTarget = baseParams.getBuildTarget();
      // Platform information is only relevant when building release-optimized files.
      // Stripping platform targets from individual files allows us to use the base version of
      // every file in the build for all supported platforms, leading to improved cache reuse.
      this.fileBaseTarget =
          !buildTarget.getFlavors().contains(JsFlavors.RELEASE)
              ? buildTarget.withFlavors()
              : buildTarget;
    }

    private LibraryBuilder setSources(
        ImmutableSet<Either<SourcePath, Pair<SourcePath, String>>> sources)
        throws NoSuchBuildTargetException {
      final ImmutableList.Builder<JsFile> builder = ImmutableList.builder();
      for (Either<SourcePath, Pair<SourcePath, String>> source : sources) {
        builder.add(this.requireJsFile(source));
      }
      this.sourceFiles = builder.build();
      return this;
    }

    private LibraryBuilder setLibraryDependencies(
        ImmutableSortedSet<BuildTarget> libraryDependencies) throws NoSuchBuildTargetException {

      BuildTarget buildTarget = baseParams.getBuildTarget();
      final BuildTarget[] targets =
          libraryDependencies
              .stream()
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
          baseParams.copyAppendingExtraDeps(Iterables.concat(sourceFiles, libraryDependencies)),
          sourceFiles
              .stream()
              .map(BuildRule::getSourcePathToOutput)
              .collect(MoreCollectors.toImmutableSortedSet()),
          libraryDependencies
              .stream()
              .map(BuildRule::getSourcePathToOutput)
              .collect(MoreCollectors.toImmutableSortedSet()),
          worker);
    }

    private boolean hasFlavors() {
      return !baseParams.getBuildTarget().getFlavors().isEmpty();
    }

    private JsFile requireJsFile(Either<SourcePath, Pair<SourcePath, String>> file)
        throws NoSuchBuildTargetException {
      final Flavor fileFlavor = sourcesToFlavors.get(file);
      final BuildTarget target = fileBaseTarget.withAppendedFlavors(fileFlavor);
      resolver.requireRule(target);
      return resolver.getRuleWithType(target, JsFile.class);
    }

    private BuildTarget addFlavorsToLibraryTarget(BuildTarget unflavored) {
      return unflavored.withAppendedFlavors(baseParams.getBuildTarget().getFlavors());
    }
  }

  private static BuildRule createReleaseFileRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      JsLibraryDescriptionArg args,
      WorkerTool worker)
      throws NoSuchBuildTargetException {
    final BuildTarget devTarget = withFileFlavorOnly(params.getBuildTarget());
    final BuildRule devFile = resolver.requireRule(devTarget);
    return new JsFile.JsFileRelease(
        params.copyAppendingExtraDeps(devFile),
        resolver.getRuleWithType(devTarget, JsFile.class).getSourcePathToOutput(),
        args.getExtraArgs(),
        worker);
  }

  private static <A extends AbstractJsLibraryDescriptionArg> BuildRule createDevFileRule(
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver sourcePathResolver,
      A args,
      Either<SourcePath, Pair<SourcePath, String>> source,
      WorkerTool worker) {
    final SourcePath sourcePath = source.transform(x -> x, Pair::getFirst);
    final Optional<String> subPath =
        Optional.ofNullable(source.transform(x -> null, Pair::getSecond));

    final Optional<Path> virtualPath =
        args.getBasePath()
            .map(
                basePath ->
                    changePathPrefix(
                            sourcePath,
                            basePath,
                            params,
                            sourcePathResolver,
                            params.getBuildTarget().getUnflavoredBuildTarget())
                        .resolve(subPath.orElse("")));

    return new JsFile.JsFileDev(
        ruleFinder.getRule(sourcePath).map(params::copyAppendingExtraDeps).orElse(params),
        sourcePath,
        subPath,
        virtualPath,
        args.getExtraArgs(),
        worker);
  }

  private static BuildTarget withFileFlavorOnly(BuildTarget target) {
    return target.withFlavors(
        target.getFlavors().stream().filter(JsFlavors::isFileFlavor).toArray(Flavor[]::new));
  }

  private static ImmutableBiMap<Either<SourcePath, Pair<SourcePath, String>>, Flavor>
      mapSourcesToFlavors(
          SourcePathResolver sourcePathResolver,
          ImmutableSet<Either<SourcePath, Pair<SourcePath, String>>> sources) {

    final ImmutableBiMap.Builder<Either<SourcePath, Pair<SourcePath, String>>, Flavor> builder =
        ImmutableBiMap.builder();
    for (Either<SourcePath, Pair<SourcePath, String>> source : sources) {
      final Path relativePath =
          source.isLeft()
              ? sourcePathResolver.getRelativePath(source.getLeft())
              : Paths.get(source.getRight().getSecond());
      builder.put(source, JsFlavors.fileFlavorForSourcePath(relativePath));
    }
    return builder.build();
  }

  private static Path changePathPrefix(
      SourcePath sourcePath,
      String basePath,
      BuildRuleParams params,
      SourcePathResolver sourcePathResolver,
      UnflavoredBuildTarget target) {
    final Path directoryOfBuildFile = target.getCellPath().resolve(target.getBasePath());
    final Path transplantTo = MorePaths.normalize(directoryOfBuildFile.resolve(basePath));
    final Path absolutePath =
        sourcePathResolver
            .getPathSourcePath(sourcePath)
            .map(
                pathSourcePath -> // for sub paths, replace the leading directory with the base path
                transplantTo.resolve(
                        MorePaths.relativize(
                            directoryOfBuildFile, sourcePathResolver.getAbsolutePath(sourcePath))))
            .orElse(transplantTo); // build target output paths are replaced completely

    return params
        .getProjectFilesystem()
        .getPathRelativeToProjectRoot(absolutePath)
        .orElseThrow(
            () ->
                new HumanReadableException(
                    "%s: Using '%s' as base path for '%s' would move the file "
                        + "out of the project root.",
                    target, basePath, sourcePathResolver.getRelativePath(sourcePath)));
  }
}
