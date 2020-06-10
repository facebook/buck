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

package com.facebook.buck.apple;

import static com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup.Linkage;
import static com.facebook.buck.swift.SwiftLibraryDescription.isSwiftTarget;

import com.facebook.buck.apple.clang.ModuleMapMode;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.apple.toolchain.UnresolvedAppleCxxPlatform;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.HasContacts;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.description.attr.ImplicitFlavorsInferringDescription;
import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.core.description.metadata.MetadataProvidingDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxHeaders;
import com.facebook.buck.cxx.CxxHeadersDir;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxLibraryDescriptionArg;
import com.facebook.buck.cxx.CxxLibraryDescriptionDelegate;
import com.facebook.buck.cxx.CxxLibraryFactory;
import com.facebook.buck.cxx.CxxLibraryFlavored;
import com.facebook.buck.cxx.CxxLibraryImplicitFlavors;
import com.facebook.buck.cxx.CxxLibraryMetadataFactory;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.CxxSymlinkTreeHeaders;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.cxx.HasAppleDebugSymbolDeps;
import com.facebook.buck.cxx.HeaderSymlinkTreeWithHeaderMap;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTreeWithModuleMap;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftCompile;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.swift.SwiftRuntimeNativeLinkableGroup;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.SwiftPlatformsProvider;
import com.facebook.buck.swift.toolchain.UnresolvedSwiftPlatform;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

public class AppleLibraryDescription
    implements DescriptionWithTargetGraph<AppleLibraryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            AppleLibraryDescription.AbstractAppleLibraryDescriptionArg>,
        ImplicitFlavorsInferringDescription,
        MetadataProvidingDescription<AppleLibraryDescriptionArg>,
        VersionPropagator<AppleLibraryDescriptionArg> {

  @SuppressWarnings("PMD") // PMD doesn't understand method references
  private static final Set<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(
          CxxCompilationDatabase.COMPILATION_DATABASE,
          CxxCompilationDatabase.UBER_COMPILATION_DATABASE,
          CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
          CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
          CxxDescriptionEnhancer.STATIC_FLAVOR,
          CxxDescriptionEnhancer.SHARED_FLAVOR,
          AppleDescriptions.FRAMEWORK_FLAVOR,
          AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
          AppleDebugFormat.DWARF.getFlavor(),
          AppleDebugFormat.NONE.getFlavor(),
          StripStyle.NON_GLOBAL_SYMBOLS.getFlavor(),
          StripStyle.ALL_SYMBOLS.getFlavor(),
          StripStyle.DEBUGGING_SYMBOLS.getFlavor(),
          LinkerMapMode.NO_LINKER_MAP.getFlavor(),
          InternalFlavor.of("default"));

  public enum Type implements FlavorConvertible {
    HEADERS(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
    EXPORTED_HEADERS(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    STATIC_PIC(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR),
    MACH_O_BUNDLE(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR),
    FRAMEWORK(AppleDescriptions.FRAMEWORK_FLAVOR),
    SWIFT_COMPILE(AppleDescriptions.SWIFT_COMPILE_FLAVOR),
    SWIFT_OBJC_GENERATED_HEADER(AppleDescriptions.SWIFT_OBJC_GENERATED_HEADER_SYMLINK_TREE_FLAVOR),
    SWIFT_EXPORTED_OBJC_GENERATED_HEADER(
        AppleDescriptions.SWIFT_EXPORTED_OBJC_GENERATED_HEADER_SYMLINK_TREE_FLAVOR),
    SWIFT_UNDERLYING_MODULE(AppleDescriptions.SWIFT_UNDERLYING_MODULE_FLAVOR),
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

  enum MetadataType implements FlavorConvertible {
    APPLE_SWIFT_METADATA(InternalFlavor.of("swift-metadata")),
    APPLE_SWIFT_UNDERLYING_MODULE_INPUT(InternalFlavor.of("swift-underlying-module-input"));

    private final Flavor flavor;

    MetadataType(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  public static final FlavorDomain<MetadataType> METADATA_TYPE =
      FlavorDomain.from("Apple Library Metadata Type", AppleLibraryDescription.MetadataType.class);

  public static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("C/C++ Library Type", Type.class);

  private final ToolchainProvider toolchainProvider;
  private final XCodeDescriptions xcodeDescriptions;
  private final Optional<SwiftLibraryDescription> swiftDelegate;
  private final AppleConfig appleConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final SwiftBuckConfig swiftBuckConfig;
  private final CxxLibraryImplicitFlavors cxxLibraryImplicitFlavors;
  private final CxxLibraryFlavored cxxLibraryFlavored;
  private final CxxLibraryFactory cxxLibraryFactory;
  private final CxxLibraryMetadataFactory cxxLibraryMetadataFactory;

  private final CxxLibraryDescriptionDelegate cxxDescriptionDelegate =
      this::createCxxLibraryDelegateForSwiftTargets;

  public AppleLibraryDescription(
      ToolchainProvider toolchainProvider,
      XCodeDescriptions xcodeDescriptions,
      SwiftLibraryDescription swiftDelegate,
      AppleConfig appleConfig,
      CxxBuckConfig cxxBuckConfig,
      SwiftBuckConfig swiftBuckConfig,
      CxxLibraryImplicitFlavors cxxLibraryImplicitFlavors,
      CxxLibraryFlavored cxxLibraryFlavored,
      CxxLibraryFactory cxxLibraryFactory,
      CxxLibraryMetadataFactory cxxLibraryMetadataFactory) {
    this.toolchainProvider = toolchainProvider;
    this.xcodeDescriptions = xcodeDescriptions;
    this.cxxLibraryImplicitFlavors = cxxLibraryImplicitFlavors;
    this.cxxLibraryFlavored = cxxLibraryFlavored;
    this.cxxLibraryFactory = cxxLibraryFactory;
    this.cxxLibraryMetadataFactory = cxxLibraryMetadataFactory;
    this.swiftDelegate =
        appleConfig.shouldUseSwiftDelegate() ? Optional.of(swiftDelegate) : Optional.empty();
    this.appleConfig = appleConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.swiftBuckConfig = swiftBuckConfig;
  }

  @Override
  public Class<AppleLibraryDescriptionArg> getConstructorArgType() {
    return AppleLibraryDescriptionArg.class;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    ImmutableSet.Builder<FlavorDomain<?>> builder = ImmutableSet.builder();

    ImmutableSet<FlavorDomain<?>> localDomains = ImmutableSet.of(AppleDebugFormat.FLAVOR_DOMAIN);

    builder.addAll(localDomains);
    cxxLibraryFlavored
        .flavorDomains(toolchainTargetConfiguration)
        .ifPresent(domains -> builder.addAll(domains));
    swiftDelegate
        .flatMap(s -> s.flavorDomains(toolchainTargetConfiguration))
        .ifPresent(domains -> builder.addAll(domains));

    ImmutableSet<FlavorDomain<?>> result = builder.build();

    // Drop StripStyle because it's overridden by AppleDebugFormat
    result =
        result.stream()
            .filter(domain -> !domain.equals(StripStyle.FLAVOR_DOMAIN))
            .collect(ImmutableSet.toImmutableSet());

    return Optional.of(result);
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return SUPPORTED_FLAVORS.containsAll(flavors)
        || cxxLibraryFlavored.hasFlavors(flavors, toolchainTargetConfiguration)
        || swiftDelegate
            .map(swift -> swift.hasFlavors(flavors, toolchainTargetConfiguration))
            .orElse(false);
  }

  public Optional<BuildRule> createSwiftBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      AppleNativeTargetDescriptionArg args,
      Optional<AppleLibrarySwiftDelegate> swiftDelegate) {
    Optional<Map.Entry<Flavor, Type>> maybeType = LIBRARY_TYPE.getFlavorAndValue(buildTarget);
    return maybeType.flatMap(
        type -> {
          FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
              getCxxPlatformsProvider(buildTarget.getTargetConfiguration())
                  .getUnresolvedCxxPlatforms();
          if (type.getValue().equals(Type.SWIFT_UNDERLYING_MODULE)) {
            return Optional.of(
                createUnderlyingModuleSymlinkTreeBuildRule(
                    buildTarget, projectFilesystem, graphBuilder, args));
          } else if (type.getValue().equals(Type.SWIFT_EXPORTED_OBJC_GENERATED_HEADER)) {
            CxxPlatform cxxPlatform =
                cxxPlatforms
                    .getValue(buildTarget)
                    .orElseThrow(IllegalArgumentException::new)
                    .resolve(graphBuilder, buildTarget.getTargetConfiguration());

            return Optional.of(
                AppleLibraryDescriptionSwiftEnhancer.createObjCGeneratedHeaderBuildRule(
                    buildTarget,
                    projectFilesystem,
                    graphBuilder,
                    cxxPlatform,
                    HeaderVisibility.PUBLIC));
          } else if (type.getValue().equals(Type.SWIFT_OBJC_GENERATED_HEADER)) {
            CxxPlatform cxxPlatform =
                cxxPlatforms
                    .getValue(buildTarget)
                    .orElseThrow(IllegalArgumentException::new)
                    .resolve(graphBuilder, buildTarget.getTargetConfiguration());

            return Optional.of(
                AppleLibraryDescriptionSwiftEnhancer.createObjCGeneratedHeaderBuildRule(
                    buildTarget,
                    projectFilesystem,
                    graphBuilder,
                    cxxPlatform,
                    HeaderVisibility.PRIVATE));
          } else if (type.getValue().equals(Type.SWIFT_COMPILE)) {
            CxxPlatform cxxPlatform =
                cxxPlatforms
                    .getValue(buildTarget)
                    .orElseThrow(IllegalArgumentException::new)
                    .resolve(graphBuilder, buildTarget.getTargetConfiguration());

            // TODO(mgd): Must handle 'default' platform
            AppleCxxPlatform applePlatform =
                getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration())
                    .getValue(buildTarget)
                    .map(unresolved -> unresolved.resolve(graphBuilder))
                    .orElseThrow(IllegalArgumentException::new);

            ImmutableSet<CxxPreprocessorInput> preprocessorInputs =
                swiftDelegate
                    .map(
                        d ->
                            d.getPreprocessorInputForSwift(
                                buildTarget, graphBuilder, cxxPlatform, args))
                    .orElseGet(
                        () ->
                            AppleLibraryDescriptionSwiftEnhancer
                                .getPreprocessorInputsForAppleLibrary(
                                    buildTarget, graphBuilder, cxxPlatform, args));

            return Optional.of(
                AppleLibraryDescriptionSwiftEnhancer.createSwiftCompileRule(
                    buildTarget,
                    cellRoots,
                    graphBuilder,
                    args,
                    projectFilesystem,
                    cxxPlatform,
                    applePlatform,
                    swiftBuckConfig,
                    preprocessorInputs));
          }

          return Optional.empty();
        });
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AppleLibraryDescriptionArg args) {
    TargetGraph targetGraph = context.getTargetGraph();
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    args.checkDuplicateSources(graphBuilder.getSourcePathResolver());
    Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(buildTarget);
    if (type.isPresent() && type.get().getValue().equals(Type.FRAMEWORK)) {
      return createFrameworkBundleBuildRule(
          targetGraph, buildTarget, context.getProjectFilesystem(), params, graphBuilder, args);
    }

    Optional<BuildRule> swiftRule =
        createSwiftBuildRule(
            buildTarget,
            context.getProjectFilesystem(),
            graphBuilder,
            context.getCellPathResolver(),
            args,
            Optional.empty());
    if (swiftRule.isPresent()) {
      return swiftRule.get();
    }

    return createLibraryBuildRule(
        context,
        buildTarget,
        params,
        graphBuilder,
        args,
        args.getLinkStyle(),
        Optional.empty(),
        ImmutableSet.of(),
        ImmutableSortedSet.of(),
        CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction.fromLibraryRule());
  }

  private <A extends AbstractAppleLibraryDescriptionArg> BuildRule createFrameworkBundleBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      AppleLibraryDescriptionArg args) {
    if (!args.getInfoPlist().isPresent()) {
      throw new HumanReadableException(
          "Cannot create framework for apple_library '%s':\n"
              + "No value specified for 'info_plist' attribute.",
          buildTarget.getUnflavoredBuildTarget());
    }
    args.checkDuplicateSources(graphBuilder.getSourcePathResolver());
    if (!AppleDescriptions.INCLUDE_FRAMEWORKS.getValue(buildTarget).isPresent()) {
      return graphBuilder.requireRule(
          buildTarget.withAppendedFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR));
    }
    AppleDebugFormat debugFormat =
        AppleDebugFormat.FLAVOR_DOMAIN
            .getValue(buildTarget)
            .orElse(appleConfig.getDefaultDebugInfoFormatForLibraries());
    if (!buildTarget.getFlavors().contains(debugFormat.getFlavor())) {
      return graphBuilder.requireRule(buildTarget.withAppendedFlavors(debugFormat.getFlavor()));
    }

    CxxPlatformsProvider cxxPlatformsProvider =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration());

    return AppleDescriptions.createAppleBundle(
        xcodeDescriptions,
        cxxPlatformsProvider,
        getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration()),
        targetGraph,
        buildTarget,
        projectFilesystem,
        params,
        graphBuilder,
        toolchainProvider.getByName(
            CodeSignIdentityStore.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            CodeSignIdentityStore.class),
        toolchainProvider.getByName(
            ProvisioningProfileStore.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            ProvisioningProfileStore.class),
        Optional.of(buildTarget),
        Optional.empty(),
        Optional.empty(),
        Either.ofLeft(AppleBundleExtension.FRAMEWORK),
        Optional.empty(),
        args.getInfoPlist().get(),
        args.getInfoPlistSubstitutions(),
        args.getDeps(),
        args.getTests(),
        debugFormat,
        appleConfig.useDryRunCodeSigning(),
        appleConfig.cacheBundlesAndPackages(),
        appleConfig.shouldVerifyBundleResources(),
        appleConfig.assetCatalogValidation(),
        AppleAssetCatalogsCompilationOptions.builder().build(),
        ImmutableList.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        appleConfig.getCodesignTimeout(),
        swiftBuckConfig.getCopyStdlibToFrameworks(),
        swiftBuckConfig.getUseLipoThin(),
        cxxBuckConfig.shouldCacheStrip(),
        appleConfig.useEntitlementsWhenAdhocCodeSigning(),
        Predicates.alwaysTrue());
  }

  /**
   * @param bundleLoader The binary in which the current library will be (dynamically) loaded into.
   */
  public <A extends AppleNativeTargetDescriptionArg> BuildRule createLibraryBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      A args,
      Optional<Linker.LinkableDepType> linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      ImmutableSortedSet<BuildTarget> extraCxxDeps,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction transitiveCxxPreprocessorInput) {
    // We explicitly remove flavors from params to make sure rule
    // has the same output regardless if we will strip or not.
    Optional<StripStyle> flavoredStripStyle = StripStyle.FLAVOR_DOMAIN.getValue(buildTarget);
    BuildTarget unstrippedBuildTarget =
        CxxStrip.removeStripStyleFlavorInTarget(buildTarget, flavoredStripStyle);

    BuildRule unstrippedBinaryRule =
        requireUnstrippedBuildRule(
            context,
            unstrippedBuildTarget,
            params,
            graphBuilder,
            args,
            linkableDepType,
            bundleLoader,
            blacklist,
            extraCxxDeps,
            transitiveCxxPreprocessorInput);

    if (!shouldWrapIntoDebuggableBinary(unstrippedBuildTarget, unstrippedBinaryRule)) {
      return unstrippedBinaryRule;
    }

    CxxPlatformsProvider cxxPlatformsProvider =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration());
    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        cxxPlatformsProvider.getUnresolvedCxxPlatforms();
    Flavor defaultCxxFlavor = cxxPlatformsProvider.getDefaultUnresolvedCxxPlatform().getFlavor();

    // If we built a multiarch binary, we can just use the strip tool from any platform.
    // We pick the platform in this odd way due to FlavorDomain's restriction of allowing only one
    // matching flavor in the build target.
    CxxPlatform representativePlatform =
        cxxPlatforms
            .getValue(
                Iterables.getFirst(
                    Sets.intersection(
                        cxxPlatforms.getFlavors(), unstrippedBuildTarget.getFlavors().getSet()),
                    defaultCxxFlavor))
            .resolve(graphBuilder, buildTarget.getTargetConfiguration());

    BuildTarget strippedBuildTarget =
        CxxStrip.restoreStripStyleFlavorInTarget(unstrippedBuildTarget, flavoredStripStyle);

    BuildRule strippedBinaryRule =
        CxxDescriptionEnhancer.createCxxStripRule(
            strippedBuildTarget,
            context.getProjectFilesystem(),
            graphBuilder,
            flavoredStripStyle.orElse(StripStyle.NON_GLOBAL_SYMBOLS),
            cxxBuckConfig.shouldCacheStrip(),
            unstrippedBinaryRule,
            representativePlatform,
            Optional.empty());

    return AppleDescriptions.createAppleDebuggableBinary(
        unstrippedBuildTarget,
        context.getProjectFilesystem(),
        graphBuilder,
        strippedBinaryRule,
        (HasAppleDebugSymbolDeps) unstrippedBinaryRule,
        AppleDebugFormat.FLAVOR_DOMAIN
            .getValue(buildTarget)
            .orElse(appleConfig.getDefaultDebugInfoFormatForLibraries()),
        cxxPlatformsProvider,
        getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration()),
        cxxBuckConfig.shouldCacheStrip());
  }

  private <A extends AppleNativeTargetDescriptionArg> BuildRule requireUnstrippedBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      A args,
      Optional<Linker.LinkableDepType> linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      ImmutableSortedSet<BuildTarget> extraCxxDeps,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction transitiveCxxPreprocessorInput) {
    Optional<MultiarchFileInfo> multiarchFileInfo =
        MultiarchFileInfos.create(
            getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration()), buildTarget);
    if (multiarchFileInfo.isPresent()) {
      ImmutableSortedSet.Builder<BuildRule> thinRules = ImmutableSortedSet.naturalOrder();
      for (BuildTarget thinTarget : multiarchFileInfo.get().getThinTargets()) {
        thinRules.add(
            requireSingleArchUnstrippedBuildRule(
                context,
                thinTarget,
                params,
                graphBuilder,
                args,
                linkableDepType,
                bundleLoader,
                blacklist,
                extraCxxDeps,
                transitiveCxxPreprocessorInput));
      }
      BuildTarget multiarchBuildTarget =
          buildTarget.withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors());
      return MultiarchFileInfos.requireMultiarchRule(
          multiarchBuildTarget,
          context.getProjectFilesystem(),
          // In the same manner that debug flavors are omitted from single-arch constituents, they
          // are omitted here as well.
          params,
          graphBuilder,
          multiarchFileInfo.get(),
          thinRules.build(),
          cxxBuckConfig,
          getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration()));
    } else {
      return requireSingleArchUnstrippedBuildRule(
          context,
          buildTarget,
          params,
          graphBuilder,
          args,
          linkableDepType,
          bundleLoader,
          blacklist,
          extraCxxDeps,
          transitiveCxxPreprocessorInput);
    }
  }

  private <A extends AppleNativeTargetDescriptionArg>
      BuildRule requireSingleArchUnstrippedBuildRule(
          BuildRuleCreationContextWithTargetGraph context,
          BuildTarget buildTarget,
          BuildRuleParams params,
          ActionGraphBuilder graphBuilder,
          A args,
          Optional<Linker.LinkableDepType> linkableDepType,
          Optional<SourcePath> bundleLoader,
          ImmutableSet<BuildTarget> blacklist,
          ImmutableSortedSet<BuildTarget> extraCxxDeps,
          CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction transitiveCxxDeps) {

    Optional<UnresolvedAppleCxxPlatform> appleCxxPlatform =
        getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration())
            .getValue(buildTarget);

    CxxLibraryDescriptionArg.Builder delegateArg = CxxLibraryDescriptionArg.builder().from(args);
    AppleDescriptions.populateCxxLibraryDescriptionArg(
        graphBuilder, delegateArg, appleCxxPlatform, args, buildTarget);

    BuildRuleParams newParams;
    Optional<BuildRule> swiftCompanionBuildRule =
        swiftDelegate.flatMap(
            swift ->
                swift.createCompanionBuildRule(
                    context, buildTarget, params, graphBuilder, args, args.getTargetSdkVersion()));
    if (swiftCompanionBuildRule.isPresent() && isSwiftTarget(buildTarget)) {
      // when creating a swift target, there is no need to proceed with apple library rules
      return swiftCompanionBuildRule.get();
    } else if (swiftCompanionBuildRule.isPresent()) {
      delegateArg.addExportedDeps(swiftCompanionBuildRule.get().getBuildTarget());
      newParams = params.copyAppendingExtraDeps(ImmutableSet.of(swiftCompanionBuildRule.get()));
    } else {
      newParams = params;
    }

    // remove some flavors from cxx rule that don't affect the rule output
    BuildTarget unstrippedTarget =
        buildTarget.withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors());
    if (AppleDescriptions.flavorsDoNotAllowLinkerMapMode(buildTarget)) {
      unstrippedTarget = unstrippedTarget.withoutFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor());
    }

    Optional<UnresolvedCxxPlatform> platform =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration())
            .getUnresolvedCxxPlatforms()
            .getValue(buildTarget);
    Optional<Type> libType = LIBRARY_TYPE.getValue(buildTarget);
    Optional<HeaderMode> headerMode = CxxLibraryDescription.HEADER_MODE.getValue(buildTarget);
    if (platform.isPresent()
        && libType.isPresent()
        && libType.get().equals(Type.EXPORTED_HEADERS)
        && headerMode.isPresent()
        && headerMode.get().includesModuleMap()) {
      return createExportedModuleSymlinkTreeBuildRule(
          buildTarget,
          context.getProjectFilesystem(),
          graphBuilder,
          platform.get().resolve(graphBuilder, buildTarget.getTargetConfiguration()),
          args);
    } else if (platform.isPresent()
        && libType.isPresent()
        && libType.get().equals(Type.SWIFT_UNDERLYING_MODULE)) {
      return createUnderlyingModuleSymlinkTreeBuildRule(
          buildTarget, context.getProjectFilesystem(), graphBuilder, args);
    }

    return graphBuilder.computeIfAbsent(
        unstrippedTarget,
        unstrippedTarget1 -> {
          CxxLibraryDescriptionDelegate cxxDelegate =
              swiftDelegate.isPresent()
                  ? CxxLibraryDescriptionDelegate.noop()
                  : this.cxxDescriptionDelegate;
          return cxxLibraryFactory.createBuildRule(
              context.getTargetGraph(),
              unstrippedTarget1,
              context.getProjectFilesystem(),
              newParams,
              graphBuilder,
              context.getCellPathResolver(),
              delegateArg.build(),
              linkableDepType,
              bundleLoader,
              blacklist,
              extraCxxDeps,
              transitiveCxxDeps,
              cxxDelegate);
        });
  }

  private boolean shouldWrapIntoDebuggableBinary(BuildTarget buildTarget, BuildRule buildRule) {
    if (!AppleDebugFormat.FLAVOR_DOMAIN.getValue(buildTarget).isPresent()) {
      return false;
    }
    if (!buildTarget.getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR)
        && !buildTarget.getFlavors().contains(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR)) {
      return false;
    }

    return AppleDebuggableBinary.isBuildRuleDebuggable(buildRule);
  }

  /** @return a {@link HeaderSymlinkTree} for the exported headers of this C/C++ library. */
  private HeaderSymlinkTree createExportedModuleSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      AppleNativeTargetDescriptionArg args) {
    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(args, buildTarget);
    ImmutableSortedMap.Builder<Path, SourcePath> headers = ImmutableSortedMap.naturalOrder();
    headers.putAll(
        CxxPreprocessables.resolveHeaderMap(
            Paths.get(""),
            AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
                buildTarget,
                graphBuilder.getSourcePathResolver()::getRelativePath,
                headerPathPrefix,
                args.getExportedHeaders())));
    if (targetContainsSwift(buildTarget, graphBuilder)) {
      headers.putAll(
          AppleLibraryDescriptionSwiftEnhancer.getObjCGeneratedHeader(
              buildTarget, graphBuilder, cxxPlatform, HeaderVisibility.PUBLIC));
    }

    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        getModularHeaderMode(args),
        headers.build(),
        HeaderVisibility.PUBLIC);
  }

  private HeaderSymlinkTree createUnderlyingModuleSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      AppleNativeTargetDescriptionArg args) {
    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(args, buildTarget);
    ImmutableMap<Path, SourcePath> headers =
        CxxPreprocessables.resolveHeaderMap(
            Paths.get(""),
            AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
                buildTarget,
                graphBuilder.getSourcePathResolver()::getRelativePath,
                headerPathPrefix,
                args.getExportedHeaders()));

    Path root = BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s");
    return CxxPreprocessables.createHeaderSymlinkTreeBuildRule(
        buildTarget, projectFilesystem, root, headers, getModularHeaderMode(args));
  }

  <U> Optional<U> createMetadataForLibrary(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      AppleNativeTargetDescriptionArg args,
      Class<U> metadataClass) {

    if (CxxLibraryDescription.METADATA_TYPE.containsAnyOf(buildTarget.getFlavors().getSet())) {
      // Modules are always platform specific so we need to only have one platform specific
      // headersymlinktree with a modulemap. We cannot forward the metadata to a cxxlibrary
      // description as it makes an optimization of having multiple header symlinktrees (platform
      // specific and general). This also gives us more control over exposing the correct swift
      // header modularly for mixed targets
      if (args.isModular()) {
        Map.Entry<Flavor, CxxLibraryDescription.MetadataType> cxxMetaDataType =
            CxxLibraryDescription.METADATA_TYPE.getFlavorAndValue(buildTarget).get();
        switch (cxxMetaDataType.getValue()) {
          case CXX_PREPROCESSOR_INPUT:
            return createCxxPreprocessorInputMetadata(
                buildTarget, graphBuilder, cellRoots, args, metadataClass, cxxMetaDataType);
          case CXX_HEADERS:
            throw new IllegalStateException(
                "Modular apple_library should provide a unified modular CXX_PREPROCESSOR_INPUT and not pass individual CXX_HEADERS");
        }
      } else {
        return forwardMetadataToCxxLibraryDescription(
            buildTarget, graphBuilder, cellRoots, args, metadataClass);
      }
    }

    if (metadataClass.isAssignableFrom(FrameworkDependencies.class)
        && buildTarget.getFlavors().contains(AppleDescriptions.FRAMEWORK_FLAVOR)) {
      Optional<Flavor> cxxPlatformFlavor =
          getCxxPlatformsProvider(buildTarget.getTargetConfiguration())
              .getUnresolvedCxxPlatforms()
              .getFlavor(buildTarget);
      Preconditions.checkState(
          cxxPlatformFlavor.isPresent(),
          "Could not find cxx platform in:\n%s",
          Joiner.on(", ").join(buildTarget.getFlavors().getSet()));
      ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
      for (BuildTarget dep : args.getDeps()) {
        Optional<FrameworkDependencies> frameworks =
            graphBuilder.requireMetadata(
                dep.withAppendedFlavors(
                    AppleDescriptions.FRAMEWORK_FLAVOR,
                    AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR,
                    cxxPlatformFlavor.get()),
                FrameworkDependencies.class);
        if (frameworks.isPresent()) {
          sourcePaths.addAll(frameworks.get().getSourcePaths());
        }
      }
      // Not all parts of Buck use require yet, so require the rule here so it's available in the
      // graphBuilder for the parts that don't.
      BuildRule buildRule = graphBuilder.requireRule(buildTarget);
      sourcePaths.add(buildRule.getSourcePathToOutput());
      return Optional.of(metadataClass.cast(FrameworkDependencies.of(sourcePaths.build())));
    }

    Optional<Map.Entry<Flavor, MetadataType>> metaType =
        METADATA_TYPE.getFlavorAndValue(buildTarget);
    if (metaType.isPresent()) {
      BuildTarget baseTarget = buildTarget.withoutFlavors(metaType.get().getKey());
      switch (metaType.get().getValue()) {
        case APPLE_SWIFT_METADATA:
          {
            AppleLibrarySwiftMetadata metadata =
                AppleLibrarySwiftMetadata.from(
                    args.getSrcs(), graphBuilder.getSourcePathResolver());
            return Optional.of(metadata).map(metadataClass::cast);
          }
        case APPLE_SWIFT_UNDERLYING_MODULE_INPUT:
          {
            if (!args.isModular()) {
              return Optional.empty();
            }
            BuildTarget swiftCompileTarget =
                baseTarget.withAppendedFlavors(Type.SWIFT_UNDERLYING_MODULE.getFlavor());
            HeaderSymlinkTreeWithModuleMap modulemap =
                (HeaderSymlinkTreeWithModuleMap) graphBuilder.requireRule(swiftCompileTarget);
            if (modulemap.getLinks().size() > 0) {
              CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
              builder.addIncludes(
                  CxxSymlinkTreeHeaders.from(modulemap, CxxPreprocessables.IncludeType.LOCAL));
              return Optional.of(builder.build()).map(metadataClass::cast);
            }
            return Optional.empty();
          }
      }
    }

    return Optional.empty();
  }

  private static CxxPreprocessorInput createSwiftPrivateCxxPreprocessorInput(
      ActionGraphBuilder graphBuilder, BuildTarget baseTarget) {
    CxxHeaders headers =
        createSwiftObjcHeaders(graphBuilder, baseTarget, Type.SWIFT_OBJC_GENERATED_HEADER);
    CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
    builder.addIncludes(headers);
    return builder.build();
  }

  private static CxxPreprocessorInput createSwiftPreprocessorInput(
      ActionGraphBuilder graphBuilder, BuildTarget baseTarget) {
    CxxHeaders swiftCompileHeaders = createSwiftModuleHeaders(graphBuilder, baseTarget);
    CxxHeaders headers =
        createSwiftObjcHeaders(graphBuilder, baseTarget, Type.SWIFT_EXPORTED_OBJC_GENERATED_HEADER);

    CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
    builder.addIncludes(swiftCompileHeaders);
    builder.addIncludes(headers);
    CxxPreprocessorInput input = builder.build();
    return input;
  }

  private static CxxHeaders createSwiftModuleHeaders(
      ActionGraphBuilder graphBuilder, BuildTarget baseTarget) {
    BuildTarget swiftCompileTarget = baseTarget.withAppendedFlavors(Type.SWIFT_COMPILE.getFlavor());
    SwiftCompile compile = (SwiftCompile) graphBuilder.requireRule(swiftCompileTarget);

    return CxxHeadersDir.of(CxxPreprocessables.IncludeType.LOCAL, compile.getSwiftModuleOutputPath());
  }

  private static CxxHeaders createSwiftObjcHeaders(
      ActionGraphBuilder graphBuilder,
      BuildTarget baseTarget,
      Type swiftExportedObjcGeneratedHeader) {
    BuildTarget swiftHeadersTarget =
        baseTarget.withAppendedFlavors(swiftExportedObjcGeneratedHeader.getFlavor());
    HeaderSymlinkTreeWithHeaderMap headersRule =
        (HeaderSymlinkTreeWithHeaderMap) graphBuilder.requireRule(swiftHeadersTarget);

    return CxxSymlinkTreeHeaders.from(headersRule, CxxPreprocessables.IncludeType.LOCAL);
  }

  private <U> Optional<U> createCxxPreprocessorInputMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      AppleNativeTargetDescriptionArg args,
      Class<U> metadataClass,
      Entry<Flavor, CxxLibraryDescription.MetadataType> cxxMetaDataType) {
    Entry<Flavor, UnresolvedCxxPlatform> platformEntry =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration())
            .getUnresolvedCxxPlatforms()
            .getFlavorAndValue(buildTarget)
            .orElseThrow(IllegalArgumentException::new);
    Entry<Flavor, HeaderVisibility> visibility =
        CxxLibraryDescription.HEADER_VISIBILITY
            .getFlavorAndValue(buildTarget)
            .orElseThrow(IllegalArgumentException::new);
    BuildTarget baseTarget =
        buildTarget.withoutFlavors(
            cxxMetaDataType.getKey(), platformEntry.getKey(), visibility.getKey());
    CxxPlatform cxxPlatform =
        platformEntry.getValue().resolve(graphBuilder, buildTarget.getTargetConfiguration());

    CxxPreprocessorInput.Builder cxxPreprocessorInputBuilder = CxxPreprocessorInput.builder();
    CxxLibraryMetadataFactory.addCxxPreprocessorInputFromArgs(
        cxxPreprocessorInputBuilder,
        args,
        cxxPlatform,
        CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
                buildTarget, cellRoots, graphBuilder, cxxPlatform)
            ::convert);

    HeaderSymlinkTree symlinkTree =
        (HeaderSymlinkTree)
            graphBuilder.requireRule(
                baseTarget
                    .withoutFlavors(LIBRARY_TYPE.getFlavors())
                    .withAppendedFlavors(
                        CxxLibraryDescription.Type.EXPORTED_HEADERS.getFlavor(),
                        platformEntry.getKey(),
                        getModularHeaderMode(args).getFlavor()));
    cxxPreprocessorInputBuilder.addIncludes(
        CxxSymlinkTreeHeaders.from(symlinkTree, CxxPreprocessables.IncludeType.LOCAL));
    CxxPreprocessorInput cxxPreprocessorInput = cxxPreprocessorInputBuilder.build();
    return Optional.of(cxxPreprocessorInput).map(metadataClass::cast);
  }

  private <U> Optional<U> forwardMetadataToCxxLibraryDescription(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      AppleNativeTargetDescriptionArg args,
      Class<U> metadataClass) {
    Optional<UnresolvedAppleCxxPlatform> appleCxxPlatform =
        getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration())
            .getValue(buildTarget);

    CxxLibraryDescriptionArg.Builder delegateArg = CxxLibraryDescriptionArg.builder().from(args);
    AppleDescriptions.populateCxxLibraryDescriptionArg(
        graphBuilder, delegateArg, appleCxxPlatform, args, buildTarget);
    return cxxLibraryMetadataFactory.createMetadata(
        buildTarget, graphBuilder, cellRoots, delegateArg.build(), metadataClass);
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      AppleLibraryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    return createMetadataForLibrary(buildTarget, graphBuilder, cellRoots, args, metadataClass);
  }

  @Override
  public ImmutableSortedSet<Flavor> addImplicitFlavors(
      ImmutableSortedSet<Flavor> argDefaultFlavors,
      TargetConfiguration toolchainTargetConfiguration) {
    // Use defaults.apple_library if present, but fall back to defaults.cxx_library otherwise.
    return cxxLibraryImplicitFlavors.addImplicitFlavorsForRuleTypes(
        argDefaultFlavors,
        toolchainTargetConfiguration,
        DescriptionCache.getRuleType(this),
        DescriptionCache.getRuleType(CxxLibraryDescription.class));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractAppleLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    MultiarchFileInfos.checkTargetSupportsMultiarch(buildTarget);
    getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration())
        .getValues()
        .forEach(
            platform ->
                targetGraphOnlyDepsBuilder.addAll(
                    platform.getParseTimeDeps(buildTarget.getTargetConfiguration())));
  }

  public static boolean isNotStaticallyLinkedLibraryNode(
      TargetNode<CxxLibraryDescription.CommonArg> node) {
    FlavorSet flavors = node.getBuildTarget().getFlavors();
    if (LIBRARY_TYPE.getFlavor(flavors).isPresent()) {
      return flavors.contains(CxxDescriptionEnhancer.SHARED_FLAVOR)
          || flavors.contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);
    } else {
      return node.getConstructorArg().getPreferredLinkage().equals(Optional.of(Linkage.SHARED));
    }
  }

  @RuleArg
  interface AbstractAppleLibraryDescriptionArg
      extends AppleNativeTargetDescriptionArg, HasContacts {
    Optional<SourcePath> getInfoPlist();

    ImmutableMap<String, String> getInfoPlistSubstitutions();
  }

  private static boolean targetContainsSwift(BuildTarget target, ActionGraphBuilder graphBuilder) {
    BuildTarget metadataTarget =
        target.withAppendedFlavors(MetadataType.APPLE_SWIFT_METADATA.getFlavor());
    Optional<AppleLibrarySwiftMetadata> metadata =
        graphBuilder.requireMetadata(metadataTarget, AppleLibrarySwiftMetadata.class);
    return metadata.map(m -> !m.getSwiftSources().isEmpty()).orElse(false);
  }

  public static Optional<CxxPreprocessorInput> underlyingModuleCxxPreprocessorInput(
      BuildTarget target, ActionGraphBuilder graphBuilder, CxxPlatform platform) {
    return graphBuilder.requireMetadata(
        target.withFlavors(
            platform.getFlavor(),
            AppleLibraryDescription.MetadataType.APPLE_SWIFT_UNDERLYING_MODULE_INPUT.getFlavor()),
        CxxPreprocessorInput.class);
  }

  private Optional<CxxLibraryDescriptionDelegate.ConfiguredDelegate>
      createCxxLibraryDelegateForSwiftTargets(
          BuildTarget target, CxxPlatform platform, ActionGraphBuilder graphBuilder) {
    if (!targetContainsSwift(target, graphBuilder)) {
      return Optional.empty();
    }

    target = target.withFlavors(platform.getFlavor());

    CxxPreprocessorInput publicPreprocessorInput =
        createSwiftPreprocessorInput(graphBuilder, target);
    CxxPreprocessorInput privatePreprocessorInput =
        createSwiftPrivateCxxPreprocessorInput(graphBuilder, target);

    BuildTarget generatedHeaderTarget =
        AppleLibraryDescriptionSwiftEnhancer.createBuildTargetForObjCGeneratedHeaderBuildRule(
            target, HeaderVisibility.PRIVATE, platform);
    BuildRule generatedHeaderRule = graphBuilder.requireRule(generatedHeaderTarget);

    BuildTarget swiftCompileTarget =
        AppleLibraryDescriptionSwiftEnhancer.createBuildTargetForSwiftCompile(target, platform);
    SwiftCompile swiftCompileRule = (SwiftCompile) graphBuilder.requireRule(swiftCompileTarget);

    SwiftPlatformsProvider swiftPlatformsProvider =
        toolchainProvider.getByName(
            SwiftPlatformsProvider.DEFAULT_NAME,
            target.getTargetConfiguration(),
            SwiftPlatformsProvider.class);
    FlavorDomain<UnresolvedSwiftPlatform> swiftPlatformFlavorDomain =
        swiftPlatformsProvider.getUnresolvedSwiftPlatforms();

    BuildTarget targetWithPlatform = target.withAppendedFlavors(platform.getFlavor());
    Optional<SwiftPlatform> swiftPlatform =
        swiftPlatformFlavorDomain.getRequiredValue(targetWithPlatform).resolve(graphBuilder);
    TargetConfiguration targetConfiguration = target.getTargetConfiguration();
    Optional<ImmutableList<NativeLinkableGroup>> swiftRuntimeNativeLinkables =
        swiftPlatform.map(
            theSwiftPlatform ->
                ImmutableList.of(
                    new SwiftRuntimeNativeLinkableGroup(theSwiftPlatform, targetConfiguration)));

    return Optional.of(
        new CxxLibraryDescriptionDelegate.ConfiguredDelegate() {
          @Override
          public Optional<CxxPreprocessorInput> getPreprocessorInput() {
            return Optional.of(publicPreprocessorInput);
          }

          @Override
          public Optional<CxxPreprocessorInput> getPrivatePreprocessorInput() {
            return Optional.of(privatePreprocessorInput);
          }

          @Override
          public Optional<HeaderSymlinkTree> getPrivateHeaderSymlinkTree() {
            if (generatedHeaderRule instanceof HeaderSymlinkTree) {
              return Optional.of((HeaderSymlinkTree) generatedHeaderRule);
            }
            return Optional.empty();
          }

          @Override
          public ImmutableList<SourcePath> getObjectFilePaths() {
            return swiftCompileRule.getObjectPaths();
          }

          @Override
          public Optional<ImmutableList<NativeLinkableGroup>> getNativeLinkableExportedDeps() {
            return swiftRuntimeNativeLinkables;
          }

          @Override
          public ImmutableList<Arg> getAdditionalExportedLinkerFlags() {
            return swiftCompileRule.getAstLinkArgs();
          }

          @Override
          public boolean getShouldProduceLibraryArtifact() {
            return true;
          }
        });
  }

  private FlavorDomain<UnresolvedAppleCxxPlatform> getAppleCxxPlatformsFlavorDomain(
      TargetConfiguration toolchainTargetConfiguration) {
    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        toolchainProvider.getByName(
            AppleCxxPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            AppleCxxPlatformsProvider.class);

    return appleCxxPlatformsProvider.getUnresolvedAppleCxxPlatforms();
  }

  private CxxPlatformsProvider getCxxPlatformsProvider(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME,
        toolchainTargetConfiguration,
        CxxPlatformsProvider.class);
  }

  private ModuleMapMode getModuleMapMode(AppleNativeTargetDescriptionArg args) {
    return args.getModulemapMode().orElse(appleConfig.moduleMapMode());
  }

  private HeaderMode getModularHeaderMode(AppleNativeTargetDescriptionArg args) {
    return HeaderMode.forModuleMapMode(getModuleMapMode(args));
  }
}
