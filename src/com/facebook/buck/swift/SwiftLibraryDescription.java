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

package com.facebook.buck.swift;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxLinkOptions;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxToolFlags;
import com.facebook.buck.cxx.DepsBuilder;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.SwiftPlatformsProvider;
import com.facebook.buck.swift.toolchain.SwiftTargetTriple;
import com.facebook.buck.swift.toolchain.UnresolvedSwiftPlatform;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class SwiftLibraryDescription
    implements DescriptionWithTargetGraph<SwiftLibraryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            SwiftLibraryDescription.AbstractSwiftLibraryDescriptionArg> {

  static final Flavor SWIFT_COMPANION_FLAVOR = InternalFlavor.of("swift-companion");
  static final Flavor SWIFT_COMPILE_FLAVOR = InternalFlavor.of("swift-compile");

  private static final Set<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(
          SWIFT_COMPANION_FLAVOR, SWIFT_COMPILE_FLAVOR, LinkerMapMode.NO_LINKER_MAP.getFlavor());

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractSwiftLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    getSwiftPlatformsFlavorDomain(buildTarget.getTargetConfiguration())
        .getValues()
        .forEach(
            platform ->
                extraDepsBuilder.addAll(
                    platform.getParseTimeDeps(buildTarget.getTargetConfiguration())));
  }

  public enum Type implements FlavorConvertible {
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR),
    MACH_O_BUNDLE(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR),
    ;

    private final Flavor flavor;

    Type(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("Swift Library Type", Type.class);

  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;
  private final SwiftBuckConfig swiftBuckConfig;

  public SwiftLibraryDescription(
      ToolchainProvider toolchainProvider,
      CxxBuckConfig cxxBuckConfig,
      SwiftBuckConfig swiftBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
    this.swiftBuckConfig = swiftBuckConfig;
  }

  @Override
  public Class<SwiftLibraryDescriptionArg> getConstructorArgType() {
    return SwiftLibraryDescriptionArg.class;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(
        ImmutableSet.of(
            // Missing: swift-companion
            // Missing: swift-compile
            getCxxPlatforms(toolchainTargetConfiguration)));
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    ImmutableSet<Flavor> currentUnsupportedFlavors =
        ImmutableSet.copyOf(Sets.filter(flavors, Predicates.not(SUPPORTED_FLAVORS::contains)));
    if (currentUnsupportedFlavors.isEmpty()) {
      return true;
    }
    return getCxxPlatforms(toolchainTargetConfiguration).containsAnyOf(flavors);
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      SwiftLibraryDescriptionArg args) {

    Optional<LinkerMapMode> flavoredLinkerMapMode =
        LinkerMapMode.FLAVOR_DOMAIN.getValue(buildTarget);
    buildTarget =
        LinkerMapMode.removeLinkerMapModeFlavorInTarget(buildTarget, flavoredLinkerMapMode);
    UnflavoredBuildTarget unflavoredBuildTarget = buildTarget.getUnflavoredBuildTarget();

    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, UnresolvedCxxPlatform>> platform =
        getCxxPlatforms(buildTarget.getTargetConfiguration()).getFlavorAndValue(buildTarget);
    FlavorSet buildFlavors = buildTarget.getFlavors();
    ImmutableSortedSet<BuildRule> filteredExtraDeps =
        params.getExtraDeps().get().stream()
            .filter(
                input ->
                    !input
                        .getBuildTarget()
                        .getUnflavoredBuildTarget()
                        .equals(unflavoredBuildTarget))
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    params = params.withExtraDeps(filteredExtraDeps);

    FlavorDomain<UnresolvedSwiftPlatform> swiftPlatformFlavorDomain =
        getSwiftPlatformsFlavorDomain(buildTarget.getTargetConfiguration());

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    if (!buildFlavors.contains(SWIFT_COMPANION_FLAVOR) && platform.isPresent()) {
      // TODO(cjhopman): This doesn't properly handle parse time deps...
      CxxPlatform cxxPlatform =
          platform.get().getValue().resolve(graphBuilder, buildTarget.getTargetConfiguration());
      Optional<SwiftPlatform> swiftPlatform =
          swiftPlatformFlavorDomain.getRequiredValue(buildTarget).resolve(graphBuilder);
      if (!swiftPlatform.isPresent()) {
        throw new HumanReadableException("Platform %s is missing swift compiler", cxxPlatform);
      }

      // See if we're building a particular "type" and "platform" of this library, and if so,
      // extract them from the flavors attached to the build target.
      Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(buildTarget);
      if (!buildFlavors.contains(SWIFT_COMPILE_FLAVOR) && type.isPresent()) {
        Set<Flavor> flavors = Sets.newHashSet(buildTarget.getFlavors().getSet());
        flavors.remove(type.get().getKey());
        BuildTarget target = buildTarget.withFlavors(flavors);
        if (flavoredLinkerMapMode.isPresent()) {
          target = target.withAppendedFlavors(flavoredLinkerMapMode.get().getFlavor());
        }

        switch (type.get().getValue()) {
          case SHARED:
            return createSharedLibraryBuildRule(
                cellRoots,
                projectFilesystem,
                params,
                graphBuilder,
                target,
                swiftPlatform.get(),
                cxxPlatform,
                args.getSoname());
          case STATIC:
          case MACH_O_BUNDLE:
            // TODO(tho@uber.com) create build rule for other types.
        }
        throw new RuntimeException("unhandled library build type");
      }

      // All swift-compile rules of swift-lib deps are required since we need their swiftmodules
      // during compilation.

      // Direct swift dependencies.
      SortedSet<BuildRule> buildDeps = params.getBuildDeps();

      List<CxxPreprocessorDep> preprocessorDeps = new ArrayList<>();
      // Build up the map of all C/C++ preprocessable dependencies.
      new AbstractBreadthFirstTraversal<BuildRule>(buildDeps) {
        @Override
        public Iterable<BuildRule> visit(BuildRule rule) {
          if (rule instanceof CxxPreprocessorDep) {
            preprocessorDeps.add((CxxPreprocessorDep) rule);
          }
          return rule.getBuildDeps();
        }
      }.start();

      // Transitive C libraries whose headers might be visible to swift via bridging.
      CxxPreprocessorInput inputs =
          CxxPreprocessorInput.concat(
              CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                  cxxPlatform, graphBuilder, preprocessorDeps));

      PreprocessorFlags cxxDeps =
          PreprocessorFlags.of(
              Optional.empty(),
              CxxToolFlags.of(),
              RichStream.from(inputs.getIncludes())
                  .filter(
                      headers -> headers.getIncludeType() != CxxPreprocessables.IncludeType.SYSTEM)
                  .distinct()
                  .toImmutableList(),
              ImmutableList.copyOf(inputs.getFrameworks()));
      Preprocessor preprocessor =
          cxxPlatform.getCpp().resolve(graphBuilder, buildTarget.getTargetConfiguration());

      BuildTarget buildTargetCopy = buildTarget;
      return new SwiftCompile(
          swiftBuckConfig,
          buildTarget,
          args.getTargetSdkVersion()
              .map(version -> swiftPlatform.get().getSwiftTarget().withTargetSdkVersion(version))
              .orElse(swiftPlatform.get().getSwiftTarget()),
          projectFilesystem,
          graphBuilder,
          swiftPlatform.get().getSwiftc(),
          args.getFrameworks(),
          CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, graphBuilder.getSourcePathResolver()),
          cxxPlatform.getFlavor(),
          args.getModuleName().orElse(buildTarget.getShortName()),
          BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s"),
          args.getSrcs(),
          args.getVersion(),
          RichStream.from(args.getCompilerFlags())
              .map(
                  CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
                          buildTargetCopy, cellRoots, graphBuilder, cxxPlatform)
                      ::convert)
              .toImmutableList(),
          args.getEnableObjcInterop(),
          args.getBridgingHeader(),
          preprocessor,
          cxxDeps,
          false);
    }

    // Otherwise, we return the generic placeholder of this library.
    buildTarget =
        LinkerMapMode.restoreLinkerMapModeFlavorInTarget(buildTarget, flavoredLinkerMapMode);
    return new SwiftLibrary(
        buildTarget,
        projectFilesystem,
        params,
        graphBuilder,
        ImmutableSet.of(),
        swiftPlatformFlavorDomain,
        args.getBridgingHeader(),
        args.getFrameworks(),
        args.getLibraries(),
        args.getSupportedPlatformsRegex(),
        args.getPreferredLinkage().orElse(NativeLinkableGroup.Linkage.ANY));
  }

  private BuildRule createSharedLibraryBuildRule(
      CellPathResolver cellPathResolver,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      BuildTarget buildTarget,
      SwiftPlatform swiftPlatform,
      CxxPlatform cxxPlatform,
      Optional<String> soname) {

    String sharedLibrarySoname =
        CxxDescriptionEnhancer.getSharedLibrarySoname(
            soname, buildTarget.withoutFlavors(SUPPORTED_FLAVORS), cxxPlatform, projectFilesystem);
    Path sharedLibOutput =
        CxxDescriptionEnhancer.getSharedLibraryPath(
            projectFilesystem, buildTarget, sharedLibrarySoname);

    NativeLinkable swiftRuntimeLinkable =
        new SwiftRuntimeNativeLinkableGroup(swiftPlatform, buildTarget.getTargetConfiguration())
            .getNativeLinkable(cxxPlatform, graphBuilder);

    BuildTarget requiredBuildTarget =
        buildTarget
            .withoutFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .withoutFlavors(LinkerMapMode.FLAVOR_DOMAIN.getFlavors())
            .withAppendedFlavors(SWIFT_COMPILE_FLAVOR);
    SwiftCompile rule = (SwiftCompile) graphBuilder.requireRule(requiredBuildTarget);

    NativeLinkableInput.Builder inputBuilder =
        NativeLinkableInput.builder()
            .from(
                swiftRuntimeLinkable.getNativeLinkableInput(
                    Linker.LinkableDepType.SHARED,
                    graphBuilder,
                    buildTarget.getTargetConfiguration()))
            .addAllArgs(rule.getAstLinkArgs())
            .addAllArgs(rule.getFileListLinkArg());
    return graphBuilder.addToIndex(
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            cxxBuckConfig,
            cxxPlatform,
            projectFilesystem,
            graphBuilder,
            buildTarget,
            Linker.LinkType.SHARED,
            Optional.of(sharedLibrarySoname),
            sharedLibOutput,
            ImmutableList.of(),
            Linker.LinkableDepType.SHARED,
            Optional.empty(),
            CxxLinkOptions.of(),
            RichStream.from(params.getBuildDeps())
                .filter(NativeLinkableGroup.class)
                .map(g -> g.getNativeLinkable(cxxPlatform, graphBuilder))
                .concat(RichStream.of(swiftRuntimeLinkable))
                .collect(ImmutableSet.toImmutableSet()),
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            inputBuilder.build(),
            Optional.empty(),
            cellPathResolver));
  }

  private FlavorDomain<UnresolvedSwiftPlatform> getSwiftPlatformsFlavorDomain(
      TargetConfiguration toolchainTargetConfiguration) {
    SwiftPlatformsProvider switftPlatformsProvider =
        toolchainProvider.getByName(
            SwiftPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            SwiftPlatformsProvider.class);
    return switftPlatformsProvider.getUnresolvedSwiftPlatforms();
  }

  public Optional<BuildRule> createCompanionBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CxxLibraryDescription.CommonArg args,
      Optional<String> targetSdkVersion) {
    if (!isSwiftTarget(buildTarget)) {
      boolean hasSwiftSource =
          !SwiftDescriptions.filterSwiftSources(
                  graphBuilder.getSourcePathResolver(), args.getSrcs())
              .isEmpty();
      return hasSwiftSource
          ? Optional.of(
              graphBuilder.requireRule(buildTarget.withAppendedFlavors(SWIFT_COMPANION_FLAVOR)))
          : Optional.empty();
    }

    SwiftLibraryDescriptionArg.Builder delegateArgsBuilder = SwiftLibraryDescriptionArg.builder();
    SwiftDescriptions.populateSwiftLibraryDescriptionArg(
        swiftBuckConfig,
        graphBuilder.getSourcePathResolver(),
        delegateArgsBuilder,
        args,
        buildTarget);
    delegateArgsBuilder.setTargetSdkVersion(targetSdkVersion);
    SwiftLibraryDescriptionArg delegateArgs = delegateArgsBuilder.build();
    if (!delegateArgs.getSrcs().isEmpty()) {
      return Optional.of(
          graphBuilder.addToIndex(createBuildRule(context, buildTarget, params, delegateArgs)));
    } else {
      return Optional.empty();
    }
  }

  public static SwiftCompile createSwiftCompileRule(
      CxxPlatform cxxPlatform,
      SwiftPlatform swiftPlatform,
      SwiftBuckConfig swiftBuckConfig,
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      ProjectFilesystem projectFilesystem,
      SwiftLibraryDescriptionArg args,
      Preprocessor preprocessor,
      PreprocessorFlags preprocessFlags,
      boolean importUnderlyingModule,
      Optional<SwiftTargetTriple> swiftTarget) {

    DepsBuilder srcsDepsBuilder = new DepsBuilder(graphBuilder);
    args.getSrcs().forEach(src -> srcsDepsBuilder.add(src));

    return new SwiftCompile(
        swiftBuckConfig,
        buildTarget,
        swiftTarget.orElse(swiftPlatform.getSwiftTarget()),
        projectFilesystem,
        graphBuilder,
        swiftPlatform.getSwiftc(),
        args.getFrameworks(),
        CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, graphBuilder.getSourcePathResolver()),
        cxxPlatform.getFlavor(),
        args.getModuleName().orElse(buildTarget.getShortName()),
        BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s"),
        args.getSrcs(),
        args.getVersion(),
        RichStream.from(args.getCompilerFlags())
            .map(
                CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
                        buildTarget, cellRoots, graphBuilder, cxxPlatform)
                    ::convert)
            .toImmutableList(),
        args.getEnableObjcInterop(),
        args.getBridgingHeader(),
        preprocessor,
        preprocessFlags,
        importUnderlyingModule);
  }

  public static boolean isSwiftTarget(BuildTarget buildTarget) {
    return buildTarget.getFlavors().contains(SWIFT_COMPANION_FLAVOR)
        || buildTarget.getFlavors().contains(SWIFT_COMPILE_FLAVOR);
  }

  private FlavorDomain<UnresolvedCxxPlatform> getCxxPlatforms(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider
        .getByName(
            CxxPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            CxxPlatformsProvider.class)
        .getUnresolvedCxxPlatforms();
  }

  public ImmutableSet<Flavor> getSupportedFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    ImmutableSet<Flavor> currentSupportedFlavors =
        ImmutableSet.copyOf(Sets.filter(flavors, SUPPORTED_FLAVORS::contains));
    ImmutableSet<Flavor> supportedCxxPlatformsFlavors =
        ImmutableSet.copyOf(Sets.filter(flavors, getCxxPlatforms(toolchainTargetConfiguration)::contains));

    return ImmutableSet.copyOf(Sets.union(currentSupportedFlavors, supportedCxxPlatformsFlavors));
  }

  @RuleArg
  interface AbstractSwiftLibraryDescriptionArg extends BuildRuleArg, HasDeclaredDeps, HasSrcs {
    Optional<String> getModuleName();

    ImmutableList<StringWithMacros> getCompilerFlags();

    Optional<String> getVersion();

    @Value.NaturalOrder
    ImmutableSortedSet<FrameworkPath> getFrameworks();

    @Value.NaturalOrder
    ImmutableSortedSet<FrameworkPath> getLibraries();

    Optional<Boolean> getEnableObjcInterop();

    Optional<Pattern> getSupportedPlatformsRegex();

    Optional<String> getSoname();

    Optional<SourcePath> getBridgingHeader();

    Optional<NativeLinkableGroup.Linkage> getPreferredLinkage();

    /**
     * The minimum OS version for which this target should be built. If set, this will override the
     * config-level option.
     */
    Optional<String> getTargetSdkVersion();
  }
}
