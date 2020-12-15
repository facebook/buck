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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.description.metadata.MetadataProvidingDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.versions.HasVersionUniverse;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.immutables.value.Value;

/** Python library rule description */
public class PythonLibraryDescription
    implements DescriptionWithTargetGraph<PythonLibraryDescriptionArg>,
        VersionPropagator<PythonLibraryDescriptionArg>,
        MetadataProvidingDescription<PythonLibraryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<PythonLibraryDescriptionArg> {

  private final DownwardApiConfig downwardApiConfig;
  private final ToolchainProvider toolchainProvider;
  private final PythonBuckConfig pythonBuckConfig;

  private static final FlavorDomain<MetadataType> METADATA_TYPE =
      FlavorDomain.from("Python Metadata Type", MetadataType.class);

  private static final FlavorDomain<LibraryType> LIBRARY_TYPE =
      FlavorDomain.from("Python Library Type", LibraryType.class);

  public PythonLibraryDescription(
      DownwardApiConfig downwardApiConfig,
      ToolchainProvider toolchainProvider,
      PythonBuckConfig pythonBuckConfig) {
    this.downwardApiConfig = downwardApiConfig;
    this.toolchainProvider = toolchainProvider;
    this.pythonBuckConfig = pythonBuckConfig;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(
        ImmutableSet.of(
            LIBRARY_TYPE,
            getPythonPlatforms(toolchainTargetConfiguration),
            toolchainProvider
                .getByName(
                    CxxPlatformsProvider.DEFAULT_NAME,
                    toolchainTargetConfiguration,
                    CxxPlatformsProvider.class)
                .getUnresolvedCxxPlatforms()));
  }

  @Override
  public Class<PythonLibraryDescriptionArg> getConstructorArgType() {
    return PythonLibraryDescriptionArg.class;
  }

  private PythonPlatform getPythonPlatform(
      BuildTarget target, PythonLibraryDescription.AbstractPythonLibraryDescriptionArg args) {
    FlavorDomain<PythonPlatform> pythonPlatforms =
        toolchainProvider
            .getByName(
                PythonPlatformsProvider.DEFAULT_NAME,
                target.getTargetConfiguration(),
                PythonPlatformsProvider.class)
            .getPythonPlatforms();
    return pythonPlatforms
        .getValue(target)
        .orElseGet(
            () ->
                pythonPlatforms.getValue(
                    args.getPlatform()
                        .<Flavor>map(InternalFlavor::of)
                        .orElseThrow(IllegalArgumentException::new)));
  }

  private UnresolvedCxxPlatform getCxxPlatform(
      BuildTarget target, PythonLibraryDescription.AbstractPythonLibraryDescriptionArg args) {
    CxxPlatformsProvider cxxPlatformsProvider =
        toolchainProvider.getByName(
            CxxPlatformsProvider.DEFAULT_NAME,
            target.getTargetConfiguration(),
            CxxPlatformsProvider.class);
    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        cxxPlatformsProvider.getUnresolvedCxxPlatforms();
    return cxxPlatforms
        .getValue(target)
        .orElseGet(
            () ->
                args.getCxxPlatform()
                    .map(cxxPlatforms::getValue)
                    .orElseThrow(IllegalArgumentException::new));
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PythonLibraryDescriptionArg args) {

    Optional<Map.Entry<Flavor, LibraryType>> optionalType =
        LIBRARY_TYPE.getFlavorAndValue(buildTarget);
    if (optionalType.isPresent()) {
      PythonPlatform pythonPlatform = getPythonPlatform(buildTarget, args);
      UnresolvedCxxPlatform cxxPlatform = getCxxPlatform(buildTarget, args);
      CxxPlatform resolvedCxxPlatform =
          cxxPlatform.resolve(
              context.getActionGraphBuilder(), buildTarget.getTargetConfiguration());
      BuildTarget baseTarget =
          buildTarget.withoutFlavors(
              optionalType.get().getKey(), pythonPlatform.getFlavor(), cxxPlatform.getFlavor());
      Optional<PythonMappedComponents> sources =
          getSources(
              context.getActionGraphBuilder(), baseTarget, pythonPlatform, resolvedCxxPlatform);

      switch (optionalType.get().getValue()) {
        case COMPILE:
          return PythonCompileRule.from(
              buildTarget,
              context.getProjectFilesystem(),
              context.getActionGraphBuilder(),
              pythonPlatform.getEnvironment(),
              sources.orElseThrow(
                  () ->
                      new HumanReadableException(
                          "%s: rule has no sources to compile", buildTarget)),
              args.getIgnoreCompileErrors(),
              downwardApiConfig.isEnabledForPython());
        case SOURCE_DB:
          {
            ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
            PythonMappedComponents sourcesBeforeOverriding =
                sources.orElseGet(() -> PythonMappedComponents.of(ImmutableSortedMap.of()));
            PythonMappedComponents sourcesOverriddenWithTypeStubs =
                PythonMappedComponents.of(
                    ImmutableSortedMap.copyOf(
                        PythonLibraryDescription.getSourcesOverriddenWithTypeStubs(
                            sourcesBeforeOverriding.getComponents(),
                            buildTarget,
                            graphBuilder,
                            resolvedCxxPlatform,
                            args)));

            return PythonSourceDatabase.from(
                buildTarget,
                context.getProjectFilesystem(),
                graphBuilder,
                pythonPlatform,
                resolvedCxxPlatform,
                sourcesOverriddenWithTypeStubs,
                getPackageDeps(
                    context.getActionGraphBuilder(),
                    baseTarget,
                    pythonPlatform,
                    resolvedCxxPlatform));
          }
      }
    }

    return new PythonLibrary(
        buildTarget,
        context.getProjectFilesystem(),
        params.getDeclaredDeps(),
        args.getZipSafe(),
        args.isExcludeDepsFromMergedLinking());
  }

  private <T> T getMetadata(
      ActionGraphBuilder graphBuilder,
      BuildTarget baseTarget,
      MetadataType type,
      Class<T> clazz,
      Flavor... flavors) {
    return graphBuilder
        .requireMetadata(
            baseTarget.withAppendedFlavors(
                ImmutableSet.<Flavor>builder()
                    .add(type.getFlavor())
                    .addAll(ImmutableList.copyOf(flavors))
                    .build()),
            clazz)
        .orElseThrow(IllegalStateException::new);
  }

  @SuppressWarnings("unchecked")
  private Optional<PythonMappedComponents> getModules(
      ActionGraphBuilder graphBuilder,
      BuildTarget baseTarget,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform) {
    return getMetadata(
        graphBuilder,
        baseTarget,
        MetadataType.MODULES,
        Optional.class,
        pythonPlatform.getFlavor(),
        cxxPlatform.getFlavor());
  }

  @SuppressWarnings("unchecked")
  private ImmutableSortedSet<BuildRule> getPackageDeps(
      ActionGraphBuilder graphBuilder,
      BuildTarget baseTarget,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform) {
    return getMetadata(
        graphBuilder,
        baseTarget,
        MetadataType.PACKAGE_DEPS,
        ImmutableSortedSet.class,
        pythonPlatform.getFlavor(),
        cxxPlatform.getFlavor());
  }

  @SuppressWarnings("unchecked")
  private Optional<PythonMappedComponents> getSources(
      ActionGraphBuilder graphBuilder,
      BuildTarget baseTarget,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform) {
    return getMetadata(
        graphBuilder,
        baseTarget,
        MetadataType.SOURCES,
        Optional.class,
        pythonPlatform.getFlavor(),
        cxxPlatform.getFlavor());
  }

  /**
   * Return sources with any overrides from the `type_stubs` argument.
   *
   * @throws HumanReadableException if the type stub does not have a .pyi suffix or if there is no
   *     matching source file (and `checkForMatchingSource` is true).
   */
  public static Map<Path, SourcePath> getSourcesOverriddenWithTypeStubs(
      Map<Path, SourcePath> sources,
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      PythonLibraryDescriptionArg args) {
    Map<Path, SourcePath> sourcesOverriddenWithTypeStubs = new TreeMap<>(sources);
    PythonUtil.forEachModuleParam(
        buildTarget,
        graphBuilder,
        cxxPlatform,
        "type_stubs",
        PythonUtil.getBasePath(buildTarget, args.getBaseModule()),
        ImmutableList.of(args.getTypeStubs()),
        (name, src) -> {
          if (!MorePaths.getFileExtension(name).equals("pyi")) {
            throw new HumanReadableException("type stubs must have `.pyi` suffix");
          }
          String pyFilename = MorePaths.getNameWithoutExtension(name) + ".py";
          Path pyName =
              name.getParent() == null
                  ? name.getFileSystem().getPath(pyFilename)
                  : name.getParent().resolve(pyFilename);
          if (sourcesOverriddenWithTypeStubs.remove(pyName) == null) {
            throw new HumanReadableException("type stubs must have corresponding `.py` in `srcs`");
          }
          sourcesOverriddenWithTypeStubs.put(name, src);
        });
    return sourcesOverriddenWithTypeStubs;
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      PythonLibraryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {

    Optional<Map.Entry<Flavor, MetadataType>> optionalType =
        METADATA_TYPE.getFlavorAndValue(buildTarget);
    if (!optionalType.isPresent()) {
      return Optional.empty();
    }

    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        toolchainProvider
            .getByName(
                CxxPlatformsProvider.DEFAULT_NAME,
                buildTarget.getTargetConfiguration(),
                CxxPlatformsProvider.class)
            .getUnresolvedCxxPlatforms();

    // Extract type.
    Map.Entry<Flavor, MetadataType> type = optionalType.get();
    BuildTarget baseTarget = buildTarget.withoutFlavors(type.getKey());

    // Extract Python and C++ platforms.
    Map.Entry<Flavor, PythonPlatform> pythonPlatform =
        getPythonPlatforms(buildTarget.getTargetConfiguration())
            .getFlavorAndValue(baseTarget)
            .orElseThrow(IllegalArgumentException::new);
    Map.Entry<Flavor, UnresolvedCxxPlatform> cxxPlatform =
        cxxPlatforms.getFlavorAndValue(baseTarget).orElseThrow(IllegalArgumentException::new);
    baseTarget = baseTarget.withoutFlavors(pythonPlatform.getKey(), cxxPlatform.getKey());

    switch (type.getValue()) {
      case MODULES:
        {
          ImmutableSortedMap<Path, SourcePath> components =
              PythonUtil.parseModules(
                  baseTarget,
                  graphBuilder,
                  pythonPlatform.getValue(),
                  cxxPlatform
                      .getValue()
                      .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
                  selectedVersions,
                  pythonBuckConfig.getSrcExtCheckStyle(),
                  args);
          return Optional.of(
                  components.isEmpty()
                      ? Optional.empty()
                      : Optional.of(PythonMappedComponents.of(components)))
              .map(metadataClass::cast);
        }

      case RESOURCES:
        {
          ImmutableSortedMap<Path, SourcePath> components =
              PythonUtil.parseResources(
                  baseTarget,
                  graphBuilder,
                  pythonPlatform.getValue(),
                  cxxPlatform
                      .getValue()
                      .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
                  selectedVersions,
                  args);
          return Optional.of(
                  components.isEmpty()
                      ? Optional.empty()
                      : Optional.of(PythonMappedComponents.of(components)))
              .map(metadataClass::cast);
        }

      case SOURCES:
        {
          ImmutableSortedMap<Path, SourcePath> components =
              PythonUtil.parseSources(
                  baseTarget,
                  graphBuilder,
                  pythonPlatform.getValue(),
                  cxxPlatform
                      .getValue()
                      .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
                  selectedVersions,
                  args);
          return Optional.of(
                  components.isEmpty()
                      ? Optional.empty()
                      : Optional.of(PythonMappedComponents.of(components)))
              .map(metadataClass::cast);
        }

      case MODULES_FOR_TYPING:
        {
          CxxPlatform resolvedCxxPlatform =
              cxxPlatform.getValue().resolve(graphBuilder, buildTarget.getTargetConfiguration());

          Map<Path, SourcePath> sources =
              new TreeMap<>(
                  getModules(
                          graphBuilder, baseTarget, pythonPlatform.getValue(), resolvedCxxPlatform)
                      .map(PythonMappedComponents::getComponents)
                      .orElseGet(ImmutableSortedMap::of));

          Map<Path, SourcePath> modulesForTyping =
              getSourcesOverriddenWithTypeStubs(
                  sources, buildTarget, graphBuilder, resolvedCxxPlatform, args);

          return Optional.of(
                  modulesForTyping.isEmpty()
                      ? Optional.empty()
                      : Optional.of(
                          PythonMappedComponents.of(ImmutableSortedMap.copyOf(modulesForTyping))))
              .map(metadataClass::cast);
        }

      case PACKAGE_DEPS:
        {
          ImmutableList<BuildTarget> depTargets =
              PythonUtil.getParamForPlatform(
                  pythonPlatform.getValue(),
                  cxxPlatform
                      .getValue()
                      .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
                  args.getDeps(),
                  args.getPlatformDeps());
          return Optional.of(graphBuilder.getAllRules(depTargets)).map(metadataClass::cast);
        }
    }

    throw new IllegalStateException();
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  private FlavorDomain<PythonPlatform> getPythonPlatforms(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider
        .getByName(
            PythonPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            PythonPlatformsProvider.class)
        .getPythonPlatforms();
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      PythonLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // When generating non-meta rules we use the resolved C++ platform, so make sure it's added to
    // parse-time deps.
    if (LIBRARY_TYPE.getFlavorAndValue(buildTarget).isPresent()) {
      targetGraphOnlyDepsBuilder.addAll(
          getCxxPlatform(buildTarget, constructorArg)
              .getParseTimeDeps(buildTarget.getTargetConfiguration()));
    }
  }

  /** Ways of building this library. */
  enum LibraryType implements FlavorConvertible {
    /** Compile the sources in this library into bytecode. */
    COMPILE(InternalFlavor.of("compile")),
    SOURCE_DB(InternalFlavor.of("source-db")),
    ;

    private final Flavor flavor;

    LibraryType(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  enum MetadataType implements FlavorConvertible {
    RESOURCES(InternalFlavor.of("resources")),
    MODULES(InternalFlavor.of("modules")),
    SOURCES(InternalFlavor.of("sources")),
    MODULES_FOR_TYPING(InternalFlavor.of("modules-for-typing")),
    PACKAGE_DEPS(InternalFlavor.of("package-deps")),
    ;

    private final Flavor flavor;

    MetadataType(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  /** Arguments shared by Python rules */
  interface CoreArg extends BuildRuleArg, HasDeclaredDeps, HasTests {
    @Value.Default
    default SourceSortedSet getSrcs() {
      return SourceSortedSet.EMPTY;
    }

    Optional<VersionMatchedCollection<SourceSortedSet>> getVersionedSrcs();

    @Value.Default
    default PatternMatchedCollection<SourceSortedSet> getPlatformSrcs() {
      return PatternMatchedCollection.of();
    }

    @Value.Default
    default SourceSortedSet getResources() {
      return SourceSortedSet.EMPTY;
    }

    Optional<VersionMatchedCollection<SourceSortedSet>> getVersionedResources();

    @Value.Default
    default PatternMatchedCollection<SourceSortedSet> getPlatformResources() {
      return PatternMatchedCollection.of();
    }

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }

    Optional<String> getBaseModule();

    Optional<Boolean> getZipSafe();

    @Value.Default
    default boolean isExcludeDepsFromMergedLinking() {
      return false;
    }
  }

  @RuleArg
  interface AbstractPythonLibraryDescriptionArg extends CoreArg, HasVersionUniverse {
    Optional<String> getPlatform();

    Optional<Flavor> getCxxPlatform();

    @Value.Default
    default SourceSortedSet getTypeStubs() {
      return SourceSortedSet.EMPTY;
    }

    @Value.Default
    default boolean getIgnoreCompileErrors() {
      return false;
    }
  }
}
