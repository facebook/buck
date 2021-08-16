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
import com.facebook.buck.core.filesystems.AbsPath;
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
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Weigher;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
  private final Cache<JsLibraryDescriptionArg, GroupedJsSourceSet> groupedSourcesCache =
      CacheBuilder.newBuilder()
          .weakKeys()
          .maximumWeight(1 << 16)
          .weigher(
              (Weigher<JsLibraryDescriptionArg, GroupedJsSourceSet>)
                  (args, groupedSources) -> args.getFlatSrcs().size())
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

    GroupedJsSourceSet groupedSources;
    try {
      groupedSources =
          groupedSourcesCache.get(
              args, () -> new GroupedJsSourceSet(args, graphBuilder.getSourcePathResolver()));
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
    Optional<JsSourcePath> file =
        JsFlavors.extractSourcePath(
            groupedSources.mainSourcesToFlavors.inverse(),
            buildTarget.getFlavors().getSet().stream());

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
          groupedSources.additionalSourcesByMainSource.get(file.get()),
          worker,
          withDownwardApi);
    } else {
      if (buildTarget.getFlavors().contains(JsFlavors.LIBRARY_FILES)) {
        return new LibraryFilesBuilder(graphBuilder, buildTarget, groupedSources, withDownwardApi)
            .setSources(isMissingTransformProfile ? ImmutableSet.of() : groupedSources.mainSources)
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

    Optional<ImmutableSet<String>> getAssetExtensions();

    Optional<ImmutableSet<String>> getAssetPlatforms();

    @Hint(execConfiguration = true)
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
    private final GroupedJsSourceSet groupedSources;
    private final BuildTarget fileBaseTarget;
    private final boolean withDownwardApi;
    private Optional<String> forbidBuildingReason = Optional.empty();

    @Nullable private ImmutableList<JsFile> jsFileRules;

    public LibraryFilesBuilder(
        ActionGraphBuilder graphBuilder,
        BuildTarget baseTarget,
        GroupedJsSourceSet groupedSources,
        boolean withDownwardApi) {
      this.graphBuilder = graphBuilder;
      this.baseTarget = baseTarget;
      this.groupedSources = groupedSources;

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
      Flavor fileFlavor = groupedSources.mainSourcesToFlavors.get(file);
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
      ImmutableSet<JsSourcePath> additionalSources,
      WorkerTool worker,
      boolean withDownwardApi) {

    return JsFile.create(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        JsUtil.getExtraJson(args, buildTarget, graphBuilder, cellRoots),
        worker,
        source,
        additionalSources,
        args.getBasePath(),
        withDownwardApi,
        buildTarget.getFlavors().contains(JsFlavors.RELEASE));
  }

  /**
   * A regex that, when matching against a possible asset filename minus its file extension, matches
   * the name without any suffix that specifies a scale factor, e.g. "@1.5x".
   */
  private static final Pattern STRIP_SCALE_FROM_ASSET_NAME =
      Pattern.compile("^.*?(?=(?:@[0-9.]+x)?$)");

  /**
   * Represents the set of main source files in a JS library, the internal build flavor uniquely
   * associated with each one, and any additional sources that should be processed along with each
   * main source.
   */
  class GroupedJsSourceSet {
    /**
     * All "main" sources in a library. Each main source can be transformed independently of other
     * main sources.
     */
    public final ImmutableSet<JsSourcePath> mainSources;

    /** A 1:1 mapping from main sources to generated flavors. */
    public final ImmutableBiMap<JsSourcePath, Flavor> mainSourcesToFlavors;

    /** A mapping from main sources to their additional sources, if any. */
    public final ImmutableSetMultimap<JsSourcePath, JsSourcePath> additionalSourcesByMainSource;

    private final JsLibraryDescriptionArg args;

    public GroupedJsSourceSet(
        JsLibraryDescriptionArg args, SourcePathResolverAdapter sourcePathResolverAdapter) {
      this.args = args;

      ImmutableSet<JsSourcePath> ungroupedSources = args.getFlatSrcs();

      ImmutableSetMultimap<JsSourcePath, JsSourcePath> groupings =
          buildGroupings(ungroupedSources, sourcePathResolverAdapter);
      mainSources = groupings.keySet();

      additionalSourcesByMainSource =
          groupings.entries().stream()
              .filter(entry -> entry.getKey() != entry.getValue())
              .collect(
                  ImmutableSetMultimap.toImmutableSetMultimap(
                      Map.Entry::getKey, Map.Entry::getValue));

      ImmutableBiMap.Builder<JsSourcePath, Flavor> flavorsBuilder = ImmutableBiMap.builder();
      for (JsSourcePath source : mainSources) {
        RelPath relativePath =
            source
                .getInnerPath()
                .map(RelPath::get)
                .orElseGet(() -> sourcePathResolverAdapter.getCellUnsafeRelPath(source.getPath()));
        flavorsBuilder.put(source, JsFlavors.fileFlavorForSourcePath(relativePath));
      }
      mainSourcesToFlavors = flavorsBuilder.build();
    }

    private ImmutableSetMultimap<JsSourcePath, JsSourcePath> buildGroupings(
        ImmutableSet<JsSourcePath> ungroupedSources,
        SourcePathResolverAdapter sourcePathResolverAdapter) {
      ImmutableSetMultimap.Builder<JsSourcePath, JsSourcePath> builder =
          ImmutableSetMultimap.builder();
      // Since canonical paths may or may not be concrete source paths, iterate
      // over the sources in order and use the first concrete path we encounter
      // as the "main source" for each group of sources that share a canonical
      // path.
      Map<AbsPath, JsSourcePath> mainSourceByCanonicalPath = new HashMap<>();
      ungroupedSources.stream()
          .sorted()
          .forEachOrdered(
              // TODO(moti): Use a collector instead of sorted().forEachOrdered() and a separate map
              (JsSourcePath source) -> {
                AbsPath canonicalSource = canonicalize(source, sourcePathResolverAdapter);
                JsSourcePath mainSource =
                    mainSourceByCanonicalPath.computeIfAbsent(canonicalSource, unused -> source);
                builder.put(mainSource, source);
              });
      return builder.build();
    }

    // Returns a canonical path for the given source, used for grouping sources
    // to be transformed together. The canonical path does not necessarily
    // represent an actual file on disk.
    private AbsPath canonicalize(
        JsSourcePath source, SourcePathResolverAdapter sourcePathResolverAdapter) {
      AbsPath combinedPath =
          source
              .getInnerPath()
              .map(
                  innerPath ->
                      sourcePathResolverAdapter
                          .getAbsolutePath(source.getPath())
                          .resolve(innerPath))
              .orElseGet(() -> sourcePathResolverAdapter.getAbsolutePath(source.getPath()));
      return combinedPath
          .getParent()
          .resolve(canonicalizeBaseName(combinedPath.getFileName().toString()));
    }

    private ImmutableSet<String> getAssetPlatforms() {
      return args.getAssetPlatforms().orElseGet(jsConfig::getAssetPlatforms);
    }

    private ImmutableSet<String> getAssetExtensions() {
      return args.getAssetExtensions().orElseGet(jsConfig::getAssetExtensions);
    }

    private String stripPlatformFromAssetName(String nameWithoutExt) {
      String maybePlatform = Files.getFileExtension(nameWithoutExt);
      if (getAssetPlatforms().contains(maybePlatform)) {
        return Files.getNameWithoutExtension(nameWithoutExt);
      }
      return nameWithoutExt;
    }

    private String stripScaleFromAssetName(String nameWithoutPlatformAndExt) {
      Matcher nameWithoutScaleMatcher =
          STRIP_SCALE_FROM_ASSET_NAME.matcher(nameWithoutPlatformAndExt);
      boolean found = nameWithoutScaleMatcher.find();
      Preconditions.checkState(
          found,
          "Expected STRIP_SCALE_FROM_ASSET_NAME regex to always return a match (even an empty string)");
      return nameWithoutScaleMatcher.group();
    }

    private String canonicalizeBaseName(String baseName) {
      String ext = Files.getFileExtension(baseName);
      if (getAssetExtensions().contains(ext)) {
        String nameWithoutExt = Files.getNameWithoutExtension(baseName);
        String nameWithoutPlatform = stripPlatformFromAssetName(nameWithoutExt);
        return stripScaleFromAssetName(nameWithoutPlatform) + "." + ext;
      }
      return baseName;
    }
  }
}
