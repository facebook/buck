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

package com.facebook.buck.features.js;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.shell.ProvidesWorkerTool;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Weigher;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.immutables.value.Value;

public class JsLibraryDescription
    implements DescriptionWithTargetGraph<JsLibraryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<JsLibraryDescription.AbstractJsLibraryDescriptionArg> {

  static final ImmutableSet<FlavorDomain<?>> FLAVOR_DOMAINS =
      ImmutableSet.of(
          JsFlavors.PLATFORM_DOMAIN,
          JsFlavors.OPTIMIZATION_DOMAIN,
          JsFlavors.TRANSFORM_PROFILE_DOMAIN);
  private final Cache<ImmutableSet<JsSourcePath>, ImmutableBiMap<JsSourcePath, Flavor>>
      sourcesToFlavorsCache =
          CacheBuilder.newBuilder()
              .weakKeys()
              .maximumWeight(1 << 16)
              .weigher(
                  (Weigher<ImmutableSet<?>, ImmutableBiMap<?, ?>>)
                      (sources, flavors) -> sources.size())
              .build();

  private final DownwardApiConfig downwardApiConfig;
  private final JsConfig jsConfig;

  public JsLibraryDescription(DownwardApiConfig downwardApiConfig, JsConfig jsConfig) {
    this.downwardApiConfig = downwardApiConfig;
    this.jsConfig = jsConfig;
  }

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

    ImmutableBiMap<JsSourcePath, Flavor> sourcesToFlavors;
    try {
      sourcesToFlavors =
          sourcesToFlavorsCache.get(
              args.getFlatSrcs(),
              () -> mapSourcesToFlavors(graphBuilder.getSourcePathResolver(), args.getFlatSrcs()));
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
    Optional<JsSourcePath> file =
        JsFlavors.extractSourcePath(
            sourcesToFlavors.inverse(), buildTarget.getFlavors().getSet().stream());

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();
    BuildTarget workerTarget = args.getWorker();
    WorkerTool worker =
        graphBuilder.getRuleWithType(workerTarget, ProvidesWorkerTool.class).getWorkerTool();

    boolean withDownwardApi = downwardApiConfig.isEnabledForJs();

    boolean isMissingTransformProfile =
        jsConfig.getRequireTransformProfileFlavorForFiles()
            && Sets.intersection(
                    buildTarget.getFlavors().getSet(),
                    JsFlavors.TRANSFORM_PROFILE_DOMAIN.getFlavors())
                .isEmpty();

    if (file.isPresent()) {
      if (isMissingTransformProfile) {
        throw new HumanReadableException("Cannot instantiate js_file without a transform profile");
      }

      return createFileRule(
          buildTarget,
          projectFilesystem,
          graphBuilder,
          cellRoots,
          args,
          file.get(),
          worker,
          withDownwardApi);
    } else {
      if (buildTarget.getFlavors().contains(JsFlavors.LIBRARY_FILES)) {
        return new LibraryFilesBuilder(graphBuilder, buildTarget, sourcesToFlavors, withDownwardApi)
            .setSources(isMissingTransformProfile ? ImmutableSet.of() : args.getFlatSrcs())
            .setForbidBuildingReason(
                isMissingTransformProfile
                    ? Optional.of("missing transform profile")
                    : Optional.empty())
            .build(projectFilesystem, worker);
      } else {
        // We allow the `deps_query` to contain different kinds of build targets, but filter out
        // all targets that don't refer to a JsLibrary rule.
        // That prevents users from having to wrap every query into "kind(js_library, ...)".
        Stream<BuildTarget> queryDeps =
            args.getDepsQuery().map(Query::getResolvedQuery).orElseGet(ImmutableSortedSet::of)
                .stream()
                .filter(target -> JsUtil.isJsLibraryTarget(target, context.getTargetGraph()));
        Stream<BuildTarget> declaredDeps = args.getDeps().stream();
        Stream<BuildTarget> deps = Stream.concat(declaredDeps, queryDeps);
        return new LibraryBuilder(
                context.getTargetGraph(), graphBuilder, buildTarget, withDownwardApi)
            .setLibraryDependencies(deps)
            .build(projectFilesystem, worker);
      }
    }
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return JsFlavors.validateFlavors(flavors, FLAVOR_DOMAINS);
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(FLAVOR_DOMAINS);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractJsLibraryDescriptionArg arg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (arg.getDepsQuery().isPresent()) {
      extraDepsBuilder.addAll(
          QueryUtils.extractParseTimeTargets(buildTarget, cellRoots, arg.getDepsQuery().get())
              .iterator());
    }
  }

  @RuleArg
  interface AbstractJsLibraryDescriptionArg
      extends BuildRuleArg, HasDepsQuery, HasExtraJson, HasTests {
    // Sources in input format. Do not use this directly - use getFlatSrcs.
    ImmutableSet<Either<SourcePath, Pair<SourcePath, String>>> getSrcs();

    // Sources in a simplified "flat" format for internal processing.
    // TODO(moti): Use a coercer / other mechanism to achieve the same thing?
    @Value.Lazy
    default ImmutableSet<JsSourcePath> getFlatSrcs() {
      return getSrcs().stream().map(JsSourcePath::of).collect(ImmutableSet.toImmutableSet());
    }

    BuildTarget getWorker();

    @Hint(isDep = false, isInput = false)
    Optional<String> getBasePath();

    @Override
    default JsLibraryDescriptionArg withDepsQuery(Query query) {
      if (getDepsQuery().equals(Optional.of(query))) {
        return (JsLibraryDescriptionArg) this;
      }
      return JsLibraryDescriptionArg.builder().from(this).setDepsQuery(query).build();
    }
  }

  private static class LibraryFilesBuilder {

    private final ActionGraphBuilder graphBuilder;
    private final BuildTarget baseTarget;
    private final ImmutableBiMap<JsSourcePath, Flavor> sourcesToFlavors;
    private final BuildTarget fileBaseTarget;
    private final boolean withDownwardApi;
    private Optional<String> forbidBuildingReason = Optional.empty();

    @Nullable private ImmutableList<JsFile> jsFileRules;

    public LibraryFilesBuilder(
        ActionGraphBuilder graphBuilder,
        BuildTarget baseTarget,
        ImmutableBiMap<JsSourcePath, Flavor> sourcesToFlavors,
        boolean withDownwardApi) {
      this.graphBuilder = graphBuilder;
      this.baseTarget = baseTarget;
      this.sourcesToFlavors = sourcesToFlavors;

      // Platform information is only relevant when building release-optimized files.
      // Stripping platform targets from individual files allows us to use the base version of
      // every file in the build for all supported platforms, leading to improved cache reuse.
      // However, we preserve the transform profile flavor domain, because those flavors do
      // affect unoptimized builds.
      this.fileBaseTarget =
          !baseTarget.getFlavors().contains(JsFlavors.RELEASE)
              ? baseTarget.withFlavors(
                  Sets.intersection(
                      baseTarget.getFlavors().getSet(),
                      JsFlavors.TRANSFORM_PROFILE_DOMAIN.getFlavors()))
              : baseTarget;
      this.withDownwardApi = withDownwardApi;
    }

    private LibraryFilesBuilder setSources(ImmutableSet<JsSourcePath> sources) {
      this.jsFileRules = ImmutableList.copyOf(sources.stream().map(this::requireJsFile).iterator());
      return this;
    }

    private LibraryFilesBuilder setForbidBuildingReason(Optional<String> forbidBuildingReason) {
      this.forbidBuildingReason = forbidBuildingReason;
      return this;
    }

    private JsFile requireJsFile(JsSourcePath file) {
      Flavor fileFlavor = sourcesToFlavors.get(file);
      BuildTarget target = fileBaseTarget.withAppendedFlavors(fileFlavor);
      graphBuilder.requireRule(target);
      return graphBuilder.getRuleWithType(target, JsFile.class);
    }

    private JsLibrary.Files build(ProjectFilesystem projectFileSystem, WorkerTool worker) {
      Objects.requireNonNull(jsFileRules, "No source files set");

      return new JsLibrary.Files(
          baseTarget.withAppendedFlavors(JsFlavors.LIBRARY_FILES),
          projectFileSystem,
          graphBuilder,
          jsFileRules.stream()
              .map(JsFile::getSourcePathToOutput)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
          worker,
          withDownwardApi,
          forbidBuildingReason);
    }
  }

  private static class LibraryBuilder {

    private final TargetGraph targetGraph;
    private final ActionGraphBuilder graphBuilder;
    private final BuildTarget baseTarget;
    private final boolean withDownwardApi;

    @Nullable private ImmutableList<JsLibrary> libraryDependencies;

    private LibraryBuilder(
        TargetGraph targetGraph,
        ActionGraphBuilder graphBuilder,
        BuildTarget baseTarget,
        boolean withDownwardApi) {
      this.targetGraph = targetGraph;
      this.baseTarget = baseTarget;
      this.graphBuilder = graphBuilder;
      this.withDownwardApi = withDownwardApi;
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
      Objects.requireNonNull(libraryDependencies, "No library dependencies set");

      BuildTarget filesTarget = baseTarget.withAppendedFlavors(JsFlavors.LIBRARY_FILES);
      graphBuilder.requireRule(filesTarget);
      return new JsLibrary(
          baseTarget,
          projectFilesystem,
          graphBuilder,
          graphBuilder.getRuleWithType(filesTarget, JsLibrary.Files.class).getSourcePathToOutput(),
          libraryDependencies.stream()
              .map(JsLibrary::getSourcePathToOutput)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
          worker,
          withDownwardApi);
    }

    private boolean hasFlavors() {
      return !baseTarget.getFlavors().isEmpty();
    }

    private BuildTarget addFlavorsToLibraryTarget(BuildTarget unflavored) {
      return unflavored.withAppendedFlavors(baseTarget.getFlavors().getSet());
    }

    JsLibrary verifyIsJsLibraryRule(BuildRule rule) {
      if (!(rule instanceof JsLibrary)) {
        BuildTarget target = rule.getBuildTarget();
        throw new HumanReadableException(
            "js_library target '%s' can only depend on other js_library targets, but one of its "
                + "dependencies, '%s', is of type %s.",
            baseTarget, target, targetGraph.get(target).getRuleType().getName());
      }

      return (JsLibrary) rule;
    }
  }

  private static <A extends AbstractJsLibraryDescriptionArg> BuildRule createFileRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      A args,
      JsSourcePath source,
      WorkerTool worker,
      boolean withDownwardApi) {

    return JsFile.create(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        JsUtil.getExtraJson(args, buildTarget, graphBuilder, cellRoots),
        worker,
        source,
        args.getBasePath(),
        withDownwardApi,
        buildTarget.getFlavors().contains(JsFlavors.RELEASE));
  }

  private static ImmutableBiMap<JsSourcePath, Flavor> mapSourcesToFlavors(
      SourcePathResolverAdapter sourcePathResolverAdapter, ImmutableSet<JsSourcePath> sources) {

    ImmutableBiMap.Builder<JsSourcePath, Flavor> builder = ImmutableBiMap.builder();
    for (JsSourcePath source : sources) {
      RelPath relativePath =
          source
              .getInnerPath()
              .map(RelPath::get)
              .orElseGet(() -> sourcePathResolverAdapter.getCellUnsafeRelPath(source.getPath()));
      builder.put(source, JsFlavors.fileFlavorForSourcePath(relativePath));
    }
    return builder.build();
  }
}
