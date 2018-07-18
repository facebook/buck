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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.BuildTargetMacro;
import com.facebook.buck.rules.macros.MacroContainer;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Weigher;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.immutables.value.Value;

public class JsLibraryDescription
    implements DescriptionWithTargetGraph<JsLibraryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<JsLibraryDescription.AbstractJsLibraryDescriptionArg> {

  static final ImmutableSet<FlavorDomain<?>> FLAVOR_DOMAINS =
      ImmutableSet.of(JsFlavors.PLATFORM_DOMAIN, JsFlavors.OPTIMIZATION_DOMAIN);
  private final Cache<
          ImmutableSet<Either<SourcePath, Pair<SourcePath, String>>>,
          ImmutableBiMap<Either<SourcePath, Pair<SourcePath, String>>, Flavor>>
      sourcesToFlavorsCache =
          CacheBuilder.newBuilder()
              .weakKeys()
              .maximumWeight(1 << 16)
              .weigher(
                  (Weigher<ImmutableSet<?>, ImmutableBiMap<?, ?>>)
                      (sources, flavors) -> sources.size())
              .build();

  @Override
  public Class<JsLibraryDescriptionArg> getConstructorArgType() {
    return JsLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      JsLibraryDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ImmutableBiMap<Either<SourcePath, Pair<SourcePath, String>>, Flavor> sourcesToFlavors;
    try {
      sourcesToFlavors =
          sourcesToFlavorsCache.get(
              args.getSrcs(), () -> mapSourcesToFlavors(sourcePathResolver, args.getSrcs()));
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
    Optional<Either<SourcePath, Pair<SourcePath, String>>> file =
        JsFlavors.extractSourcePath(sourcesToFlavors.inverse(), buildTarget.getFlavors().stream());

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();
    BuildTarget workerTarget = args.getWorker();
    WorkerTool worker = graphBuilder.getRuleWithType(workerTarget, WorkerTool.class);

    // this params object is used as base for the JsLibrary build rule, but also for all dynamically
    // created JsFile rules.
    // For the JsLibrary case, we want to propagate flavors to library dependencies
    // For the JsFile case, we only want to depend on the worker, not on any libraries
    Predicate<BuildTarget> isWorker = workerTarget::equals;
    Predicate<BuildTarget> extraDepsFilter =
        args.getExtraJson()
            .map(StringWithMacros::getMacros)
            .map(RichStream::from)
            .map(JsLibraryDescription::getMacroTargets)
            .map(macroTargets -> isWorker.or(macroTargets::contains))
            .orElse(isWorker);
    ImmutableSortedSet<BuildRule> workerAndMacrosExtraDeps =
        params
            .getExtraDeps()
            .get()
            .stream()
            .filter(x -> extraDepsFilter.test(x.getBuildTarget()))
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    BuildRuleParams baseParams =
        params.withoutDeclaredDeps().withExtraDeps(workerAndMacrosExtraDeps);

    if (file.isPresent()) {
      return buildTarget.getFlavors().contains(JsFlavors.RELEASE)
          ? createReleaseFileRule(
              buildTarget, projectFilesystem, baseParams, graphBuilder, cellRoots, args, worker)
          : createDevFileRule(
              buildTarget,
              projectFilesystem,
              baseParams,
              ruleFinder,
              sourcePathResolver,
              graphBuilder,
              cellRoots,
              args,
              file.get(),
              worker);
    } else if (buildTarget.getFlavors().contains(JsFlavors.LIBRARY_FILES)) {
      return new LibraryFilesBuilder(graphBuilder, buildTarget, baseParams, sourcesToFlavors)
          .setSources(args.getSrcs())
          .build(projectFilesystem, worker);
    } else {
      // We allow the `deps_query` to contain different kinds of build targets, but filter out
      // all targets that don't refer to a JsLibrary rule.
      // That prevents users from having to wrap every query into "kind(js_library, ...)".
      Stream<BuildTarget> queryDeps =
          args.getDepsQuery()
              .map(Query::getResolvedQuery)
              .orElseGet(ImmutableSortedSet::of)
              .stream()
              .filter(target -> JsUtil.isJsLibraryTarget(target, context.getTargetGraph()));
      Stream<BuildTarget> declaredDeps = args.getDeps().stream();
      Stream<BuildTarget> deps = Stream.concat(declaredDeps, queryDeps);
      return new LibraryBuilder(context.getTargetGraph(), graphBuilder, buildTarget, baseParams)
          .setLibraryDependencies(deps)
          .build(projectFilesystem, worker);
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

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractJsLibraryDescriptionArg arg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (arg.getDepsQuery().isPresent()) {
      extraDepsBuilder.addAll(
          QueryUtils.extractParseTimeTargets(buildTarget, cellRoots, arg.getDepsQuery().get())
              .iterator());
    }
  }

  @BuckStyleImmutable
  @Value.Immutable(copy = true)
  interface AbstractJsLibraryDescriptionArg
      extends CommonDescriptionArg, HasDepsQuery, HasExtraJson, HasTests {
    ImmutableSet<Either<SourcePath, Pair<SourcePath, String>>> getSrcs();

    BuildTarget getWorker();

    @Hint(isDep = false, isInput = false)
    Optional<String> getBasePath();
  }

  private static class LibraryFilesBuilder {

    private final ActionGraphBuilder graphBuilder;
    private final BuildTarget baseTarget;
    private final ImmutableBiMap<Either<SourcePath, Pair<SourcePath, String>>, Flavor>
        sourcesToFlavors;
    private final BuildTarget fileBaseTarget;
    private final BuildRuleParams baseParams;

    @Nullable private ImmutableList<JsFile> jsFileRules;

    public LibraryFilesBuilder(
        ActionGraphBuilder graphBuilder,
        BuildTarget baseTarget,
        BuildRuleParams baseParams,
        ImmutableBiMap<Either<SourcePath, Pair<SourcePath, String>>, Flavor> sourcesToFlavors) {
      this.graphBuilder = graphBuilder;
      this.baseTarget = baseTarget;
      this.sourcesToFlavors = sourcesToFlavors;

      // Platform information is only relevant when building release-optimized files.
      // Stripping platform targets from individual files allows us to use the base version of
      // every file in the build for all supported platforms, leading to improved cache reuse.
      this.fileBaseTarget =
          !baseTarget.getFlavors().contains(JsFlavors.RELEASE)
              ? baseTarget.withFlavors()
              : baseTarget;
      this.baseParams = baseParams;
    }

    private LibraryFilesBuilder setSources(
        ImmutableSet<Either<SourcePath, Pair<SourcePath, String>>> sources) {
      this.jsFileRules = ImmutableList.copyOf(sources.stream().map(this::requireJsFile).iterator());
      return this;
    }

    private JsFile requireJsFile(Either<SourcePath, Pair<SourcePath, String>> file) {
      Flavor fileFlavor = sourcesToFlavors.get(file);
      BuildTarget target = fileBaseTarget.withAppendedFlavors(fileFlavor);
      graphBuilder.requireRule(target);
      return graphBuilder.getRuleWithType(target, JsFile.class);
    }

    private JsLibrary.Files build(ProjectFilesystem projectFileSystem, WorkerTool worker) {
      Preconditions.checkNotNull(jsFileRules, "No source files set");

      return new JsLibrary.Files(
          baseTarget.withAppendedFlavors(JsFlavors.LIBRARY_FILES),
          projectFileSystem,
          baseParams.copyAppendingExtraDeps(jsFileRules),
          jsFileRules
              .stream()
              .map(JsFile::getSourcePathToOutput)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
          worker);
    }
  }

  private static class LibraryBuilder {

    private final TargetGraph targetGraph;
    private final ActionGraphBuilder graphBuilder;
    private final BuildTarget baseTarget;
    private final BuildRuleParams baseParams;

    @Nullable private ImmutableList<JsLibrary> libraryDependencies;

    private LibraryBuilder(
        TargetGraph targetGraph,
        ActionGraphBuilder graphBuilder,
        BuildTarget baseTarget,
        BuildRuleParams baseParams) {
      this.targetGraph = targetGraph;
      this.baseTarget = baseTarget;
      this.baseParams = baseParams;
      this.graphBuilder = graphBuilder;
    }

    private LibraryBuilder setLibraryDependencies(Stream<BuildTarget> deps) {
      this.libraryDependencies =
          deps.map(hasFlavors() ? this::addFlavorsToLibraryTarget : Function.identity())
              // `requireRule()` needed for dependencies to flavored versions
              .map(graphBuilder::requireRule)
              .map(this::verifyIsJsLibraryRule)
              .collect(ImmutableList.toImmutableList());
      return this;
    }

    private JsLibrary build(ProjectFilesystem projectFilesystem, WorkerTool worker) {
      Preconditions.checkNotNull(libraryDependencies, "No library dependencies set");

      BuildTarget filesTarget = baseTarget.withAppendedFlavors(JsFlavors.LIBRARY_FILES);
      BuildRule filesRule = graphBuilder.requireRule(filesTarget);
      return new JsLibrary(
          baseTarget,
          projectFilesystem,
          baseParams.copyAppendingExtraDeps(
              Iterables.concat(ImmutableList.of(filesRule), libraryDependencies)),
          graphBuilder.getRuleWithType(filesTarget, JsLibrary.Files.class).getSourcePathToOutput(),
          libraryDependencies
              .stream()
              .map(JsLibrary::getSourcePathToOutput)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
          worker);
    }

    private boolean hasFlavors() {
      return !baseTarget.getFlavors().isEmpty();
    }

    private BuildTarget addFlavorsToLibraryTarget(BuildTarget unflavored) {
      return unflavored.withAppendedFlavors(baseTarget.getFlavors());
    }

    JsLibrary verifyIsJsLibraryRule(BuildRule rule) {
      if (!(rule instanceof JsLibrary)) {
        BuildTarget target = rule.getBuildTarget();
        throw new HumanReadableException(
            "js_library target '%s' can only depend on other js_library targets, but one of its "
                + "dependencies, '%s', is of type %s.",
            baseTarget, target, targetGraph.get(target).getBuildRuleType().getName());
      }

      return (JsLibrary) rule;
    }
  }

  private static BuildRule createReleaseFileRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      JsLibraryDescriptionArg args,
      WorkerTool worker) {
    BuildTarget devTarget = withFileFlavorOnly(buildTarget);
    BuildRule devFile = graphBuilder.requireRule(devTarget);
    return new JsFile.JsFileRelease(
        buildTarget,
        projectFilesystem,
        params.copyAppendingExtraDeps(devFile),
        graphBuilder.getRuleWithType(devTarget, JsFile.class).getSourcePathToOutput(),
        JsUtil.getExtraJson(args, buildTarget, graphBuilder, cellRoots),
        worker);
  }

  private static <A extends AbstractJsLibraryDescriptionArg> BuildRule createDevFileRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver sourcePathResolver,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      A args,
      Either<SourcePath, Pair<SourcePath, String>> source,
      WorkerTool worker) {
    SourcePath sourcePath = source.transform(x -> x, Pair::getFirst);
    Optional<String> subPath = Optional.ofNullable(source.transform(x -> null, Pair::getSecond));

    Optional<Path> virtualPath =
        args.getBasePath()
            .map(
                basePath ->
                    changePathPrefix(
                            sourcePath,
                            basePath,
                            projectFilesystem,
                            sourcePathResolver,
                            cellRoots,
                            buildTarget.getUnflavoredBuildTarget())
                        .resolve(subPath.orElse("")));

    return new JsFile.JsFileDev(
        buildTarget,
        projectFilesystem,
        ruleFinder.getRule(sourcePath).map(params::copyAppendingExtraDeps).orElse(params),
        sourcePath,
        subPath,
        virtualPath,
        JsUtil.getExtraJson(args, buildTarget, graphBuilder, cellRoots),
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

    ImmutableBiMap.Builder<Either<SourcePath, Pair<SourcePath, String>>, Flavor> builder =
        ImmutableBiMap.builder();
    for (Either<SourcePath, Pair<SourcePath, String>> source : sources) {
      Path relativePath =
          source.transform(
              sourcePathResolver::getRelativePath, pair -> Paths.get(pair.getSecond()));
      builder.put(source, JsFlavors.fileFlavorForSourcePath(relativePath));
    }
    return builder.build();
  }

  private static ImmutableSet<BuildTarget> getMacroTargets(RichStream<MacroContainer> containers) {
    return containers
        .map(MacroContainer::getMacro)
        .filter(BuildTargetMacro.class)
        .map(BuildTargetMacro::getTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  private static Path changePathPrefix(
      SourcePath sourcePath,
      String basePath,
      ProjectFilesystem projectFilesystem,
      SourcePathResolver sourcePathResolver,
      CellPathResolver cellPathResolver,
      UnflavoredBuildTarget target) {
    Path cellPath = cellPathResolver.getCellPathOrThrow(target);
    Path directoryOfBuildFile = cellPath.resolve(target.getBasePath());
    Path transplantTo = MorePaths.normalize(directoryOfBuildFile.resolve(basePath));
    Path absolutePath =
        sourcePathResolver
            .getPathSourcePath(sourcePath)
            .map(
                pathSourcePath -> // for sub paths, replace the leading directory with the base path
                transplantTo.resolve(
                        MorePaths.relativize(
                            directoryOfBuildFile, sourcePathResolver.getAbsolutePath(sourcePath))))
            .orElse(transplantTo); // build target output paths are replaced completely

    return projectFilesystem
        .getPathRelativeToProjectRoot(absolutePath)
        .orElseThrow(
            () ->
                new HumanReadableException(
                    "%s: Using '%s' as base path for '%s' would move the file "
                        + "out of the project root.",
                    target, basePath, sourcePathResolver.getRelativePath(sourcePath)));
  }
}
