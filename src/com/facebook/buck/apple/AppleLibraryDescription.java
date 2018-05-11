/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple;

import static com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable.Linkage;
import static com.facebook.buck.swift.SwiftLibraryDescription.isSwiftTarget;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
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
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.DescriptionCache;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.ImplicitFlavorsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftCompile;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.swift.SwiftRuntimeNativeLinkable;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.SwiftPlatformsProvider;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.versions.Version;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import org.immutables.value.Value;

public class AppleLibraryDescription
    implements Description<AppleLibraryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            AppleLibraryDescription.AbstractAppleLibraryDescriptionArg>,
        ImplicitFlavorsInferringDescription,
        MetadataProvidingDescription<AppleLibraryDescriptionArg>,
        CxxLibraryDescriptionDelegate {

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
    SANDBOX(CxxDescriptionEnhancer.SANDBOX_TREE_FLAVOR),
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    STATIC_PIC(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR),
    MACH_O_BUNDLE(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR),
    FRAMEWORK(AppleDescriptions.FRAMEWORK_FLAVOR),
    SWIFT_COMPILE(AppleDescriptions.SWIFT_COMPILE_FLAVOR),
    SWIFT_OBJC_GENERATED_HEADER(AppleDescriptions.SWIFT_OBJC_GENERATED_HEADER_SYMLINK_TREE_FLAVOR),
    SWIFT_EXPORTED_OBJC_GENERATED_HEADER(
        AppleDescriptions.SWIFT_EXPORTED_OBJC_GENERATED_HEADER_SYMLINK_TREE_FLAVOR),
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
    APPLE_SWIFT_EXPORTED_OBJC_CXX_HEADERS(InternalFlavor.of("swift-objc-cxx-headers")),
    APPLE_SWIFT_OBJC_CXX_HEADERS(InternalFlavor.of("swift-private-objc-cxx-headers")),
    APPLE_SWIFT_MODULE_CXX_HEADERS(InternalFlavor.of("swift-module-cxx-headers")),
    APPLE_SWIFT_PREPROCESSOR_INPUT(InternalFlavor.of("swift-preprocessor-input")),
    APPLE_SWIFT_PRIVATE_PREPROCESSOR_INPUT(InternalFlavor.of("swift-private-preprocessor-input")),
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

  public static final FlavorDomain<MetadataType> METADATA_TYPE =
      FlavorDomain.from("Apple Library Metadata Type", AppleLibraryDescription.MetadataType.class);

  public static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("C/C++ Library Type", Type.class);

  private final ToolchainProvider toolchainProvider;
  private final Optional<SwiftLibraryDescription> swiftDelegate;
  private final AppleConfig appleConfig;
  private final SwiftBuckConfig swiftBuckConfig;
  private final CxxLibraryImplicitFlavors cxxLibraryImplicitFlavors;
  private final CxxLibraryFlavored cxxLibraryFlavored;
  private final CxxLibraryFactory cxxLibraryFactory;
  private final CxxLibraryMetadataFactory cxxLibraryMetadataFactory;

  public AppleLibraryDescription(
      ToolchainProvider toolchainProvider,
      SwiftLibraryDescription swiftDelegate,
      AppleConfig appleConfig,
      SwiftBuckConfig swiftBuckConfig,
      CxxLibraryImplicitFlavors cxxLibraryImplicitFlavors,
      CxxLibraryFlavored cxxLibraryFlavored,
      CxxLibraryFactory cxxLibraryFactory,
      CxxLibraryMetadataFactory cxxLibraryMetadataFactory) {
    this.toolchainProvider = toolchainProvider;
    this.cxxLibraryImplicitFlavors = cxxLibraryImplicitFlavors;
    this.cxxLibraryFlavored = cxxLibraryFlavored;
    this.cxxLibraryFactory = cxxLibraryFactory;
    this.cxxLibraryMetadataFactory = cxxLibraryMetadataFactory;
    this.swiftDelegate =
        appleConfig.shouldUseSwiftDelegate() ? Optional.of(swiftDelegate) : Optional.empty();
    this.appleConfig = appleConfig;
    this.swiftBuckConfig = swiftBuckConfig;
  }

  @Override
  public Class<AppleLibraryDescriptionArg> getConstructorArgType() {
    return AppleLibraryDescriptionArg.class;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    ImmutableSet.Builder<FlavorDomain<?>> builder = ImmutableSet.builder();

    ImmutableSet<FlavorDomain<?>> localDomains = ImmutableSet.of(AppleDebugFormat.FLAVOR_DOMAIN);

    builder.addAll(localDomains);
    cxxLibraryFlavored.flavorDomains().ifPresent(domains -> builder.addAll(domains));
    swiftDelegate.flatMap(s -> s.flavorDomains()).ifPresent(domains -> builder.addAll(domains));

    ImmutableSet<FlavorDomain<?>> result = builder.build();

    // Drop StripStyle because it's overridden by AppleDebugFormat
    result =
        result
            .stream()
            .filter(domain -> !domain.equals(StripStyle.FLAVOR_DOMAIN))
            .collect(ImmutableSet.toImmutableSet());

    return Optional.of(result);
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return SUPPORTED_FLAVORS.containsAll(flavors)
        || cxxLibraryFlavored.hasFlavors(flavors)
        || swiftDelegate.map(swift -> swift.hasFlavors(flavors)).orElse(false);
  }

  public Optional<BuildRule> createSwiftBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      AppleNativeTargetDescriptionArg args,
      Optional<AppleLibrarySwiftDelegate> swiftDelegate) {
    Optional<Map.Entry<Flavor, Type>> maybeType = LIBRARY_TYPE.getFlavorAndValue(buildTarget);
    return maybeType.flatMap(
        type -> {
          FlavorDomain<CxxPlatform> cxxPlatforms = getCxxPlatformsProvider().getCxxPlatforms();
          if (type.getValue().equals(Type.SWIFT_EXPORTED_OBJC_GENERATED_HEADER)) {
            CxxPlatform cxxPlatform =
                cxxPlatforms.getValue(buildTarget).orElseThrow(IllegalArgumentException::new);

            return Optional.of(
                AppleLibraryDescriptionSwiftEnhancer.createObjCGeneratedHeaderBuildRule(
                    buildTarget,
                    projectFilesystem,
                    ruleFinder,
                    resolver,
                    cxxPlatform,
                    HeaderVisibility.PUBLIC));
          } else if (type.getValue().equals(Type.SWIFT_OBJC_GENERATED_HEADER)) {
            CxxPlatform cxxPlatform =
                cxxPlatforms.getValue(buildTarget).orElseThrow(IllegalArgumentException::new);

            return Optional.of(
                AppleLibraryDescriptionSwiftEnhancer.createObjCGeneratedHeaderBuildRule(
                    buildTarget,
                    projectFilesystem,
                    ruleFinder,
                    resolver,
                    cxxPlatform,
                    HeaderVisibility.PRIVATE));
          } else if (type.getValue().equals(Type.SWIFT_COMPILE)) {
            CxxPlatform cxxPlatform =
                cxxPlatforms.getValue(buildTarget).orElseThrow(IllegalArgumentException::new);

            // TODO(mgd): Must handle 'default' platform
            AppleCxxPlatform applePlatform =
                getAppleCxxPlatformDomain()
                    .getValue(buildTarget)
                    .orElseThrow(IllegalArgumentException::new);

            ImmutableSet<CxxPreprocessorInput> preprocessorInputs =
                swiftDelegate
                    .map(
                        d ->
                            d.getPreprocessorInputForSwift(
                                buildTarget, resolver, cxxPlatform, args))
                    .orElseGet(
                        () ->
                            AppleLibraryDescriptionSwiftEnhancer
                                .getPreprocessorInputsForAppleLibrary(
                                    buildTarget, resolver, cxxPlatform));

            return Optional.of(
                AppleLibraryDescriptionSwiftEnhancer.createSwiftCompileRule(
                    buildTarget,
                    cellRoots,
                    resolver,
                    ruleFinder,
                    params,
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
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AppleLibraryDescriptionArg args) {
    TargetGraph targetGraph = context.getTargetGraph();
    BuildRuleResolver resolver = context.getBuildRuleResolver();
    Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(buildTarget);
    if (type.isPresent() && type.get().getValue().equals(Type.FRAMEWORK)) {
      return createFrameworkBundleBuildRule(
          targetGraph, buildTarget, context.getProjectFilesystem(), params, resolver, args);
    }

    Optional<BuildRule> swiftRule =
        createSwiftBuildRule(
            buildTarget,
            context.getProjectFilesystem(),
            params,
            resolver,
            new SourcePathRuleFinder(resolver),
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
        resolver,
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
      BuildRuleResolver resolver,
      AppleLibraryDescriptionArg args) {
    if (!args.getInfoPlist().isPresent()) {
      throw new HumanReadableException(
          "Cannot create framework for apple_library '%s':\n"
              + "No value specified for 'info_plist' attribute.",
          buildTarget.getUnflavoredBuildTarget());
    }
    if (!AppleDescriptions.INCLUDE_FRAMEWORKS.getValue(buildTarget).isPresent()) {
      return resolver.requireRule(
          buildTarget.withAppendedFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR));
    }
    AppleDebugFormat debugFormat =
        AppleDebugFormat.FLAVOR_DOMAIN
            .getValue(buildTarget)
            .orElse(appleConfig.getDefaultDebugInfoFormatForLibraries());
    if (!buildTarget.getFlavors().contains(debugFormat.getFlavor())) {
      return resolver.requireRule(buildTarget.withAppendedFlavors(debugFormat.getFlavor()));
    }

    CxxPlatformsProvider cxxPlatformsProvider = getCxxPlatformsProvider();

    return AppleDescriptions.createAppleBundle(
        cxxPlatformsProvider.getCxxPlatforms(),
        cxxPlatformsProvider.getDefaultCxxPlatform().getFlavor(),
        getAppleCxxPlatformDomain(),
        targetGraph,
        buildTarget,
        projectFilesystem,
        params,
        resolver,
        toolchainProvider.getByName(
            CodeSignIdentityStore.DEFAULT_NAME, CodeSignIdentityStore.class),
        toolchainProvider.getByName(
            ProvisioningProfileStore.DEFAULT_NAME, ProvisioningProfileStore.class),
        Optional.of(buildTarget),
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
        Optional.empty());
  }

  /**
   * @param bundleLoader The binary in which the current library will be (dynamically) loaded into.
   */
  public <A extends AppleNativeTargetDescriptionArg> BuildRule createLibraryBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      BuildRuleResolver resolver,
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

    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));

    BuildRule unstrippedBinaryRule =
        requireUnstrippedBuildRule(
            context,
            unstrippedBuildTarget,
            params,
            resolver,
            args,
            linkableDepType,
            bundleLoader,
            blacklist,
            pathResolver,
            extraCxxDeps,
            transitiveCxxPreprocessorInput);

    if (!shouldWrapIntoDebuggableBinary(unstrippedBuildTarget, unstrippedBinaryRule)) {
      return unstrippedBinaryRule;
    }

    CxxPlatformsProvider cxxPlatformsProvider = getCxxPlatformsProvider();
    FlavorDomain<CxxPlatform> cxxPlatforms = cxxPlatformsProvider.getCxxPlatforms();
    Flavor defaultCxxFlavor = cxxPlatformsProvider.getDefaultCxxPlatform().getFlavor();

    // If we built a multiarch binary, we can just use the strip tool from any platform.
    // We pick the platform in this odd way due to FlavorDomain's restriction of allowing only one
    // matching flavor in the build target.
    CxxPlatform representativePlatform =
        cxxPlatforms.getValue(
            Iterables.getFirst(
                Sets.intersection(cxxPlatforms.getFlavors(), unstrippedBuildTarget.getFlavors()),
                defaultCxxFlavor));

    BuildTarget strippedBuildTarget =
        CxxStrip.restoreStripStyleFlavorInTarget(unstrippedBuildTarget, flavoredStripStyle);

    BuildRule strippedBinaryRule =
        CxxDescriptionEnhancer.createCxxStripRule(
            strippedBuildTarget,
            context.getProjectFilesystem(),
            resolver,
            flavoredStripStyle.orElse(StripStyle.NON_GLOBAL_SYMBOLS),
            unstrippedBinaryRule,
            representativePlatform);

    return AppleDescriptions.createAppleDebuggableBinary(
        unstrippedBuildTarget,
        context.getProjectFilesystem(),
        resolver,
        strippedBinaryRule,
        (HasAppleDebugSymbolDeps) unstrippedBinaryRule,
        AppleDebugFormat.FLAVOR_DOMAIN
            .getValue(buildTarget)
            .orElse(appleConfig.getDefaultDebugInfoFormatForLibraries()),
        cxxPlatforms,
        defaultCxxFlavor,
        getAppleCxxPlatformDomain());
  }

  private <A extends AppleNativeTargetDescriptionArg> BuildRule requireUnstrippedBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      Optional<Linker.LinkableDepType> linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      SourcePathResolver pathResolver,
      ImmutableSortedSet<BuildTarget> extraCxxDeps,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction transitiveCxxPreprocessorInput) {
    Optional<MultiarchFileInfo> multiarchFileInfo =
        MultiarchFileInfos.create(getAppleCxxPlatformDomain(), buildTarget);
    if (multiarchFileInfo.isPresent()) {
      ImmutableSortedSet.Builder<BuildRule> thinRules = ImmutableSortedSet.naturalOrder();
      for (BuildTarget thinTarget : multiarchFileInfo.get().getThinTargets()) {
        thinRules.add(
            requireSingleArchUnstrippedBuildRule(
                context,
                thinTarget,
                params,
                resolver,
                args,
                linkableDepType,
                bundleLoader,
                blacklist,
                pathResolver,
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
          resolver,
          multiarchFileInfo.get(),
          thinRules.build());
    } else {
      return requireSingleArchUnstrippedBuildRule(
          context,
          buildTarget,
          params,
          resolver,
          args,
          linkableDepType,
          bundleLoader,
          blacklist,
          pathResolver,
          extraCxxDeps,
          transitiveCxxPreprocessorInput);
    }
  }

  private <A extends AppleNativeTargetDescriptionArg>
      BuildRule requireSingleArchUnstrippedBuildRule(
          BuildRuleCreationContext context,
          BuildTarget buildTarget,
          BuildRuleParams params,
          BuildRuleResolver resolver,
          A args,
          Optional<Linker.LinkableDepType> linkableDepType,
          Optional<SourcePath> bundleLoader,
          ImmutableSet<BuildTarget> blacklist,
          SourcePathResolver pathResolver,
          ImmutableSortedSet<BuildTarget> extraCxxDeps,
          CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction transitiveCxxDeps) {

    CxxLibraryDescriptionArg.Builder delegateArg = CxxLibraryDescriptionArg.builder().from(args);
    AppleDescriptions.populateCxxLibraryDescriptionArg(
        pathResolver, delegateArg, args, buildTarget);

    BuildRuleParams newParams;
    Optional<BuildRule> swiftCompanionBuildRule =
        swiftDelegate.flatMap(
            swift -> swift.createCompanionBuildRule(context, buildTarget, params, resolver, args));
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

    return resolver.computeIfAbsent(
        unstrippedTarget,
        unstrippedTarget1 -> {
          Optional<CxxLibraryDescriptionDelegate> cxxDelegate =
              swiftDelegate.isPresent() ? Optional.empty() : Optional.of(this);
          return cxxLibraryFactory.createBuildRule(
              unstrippedTarget1,
              context.getProjectFilesystem(),
              newParams,
              resolver,
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

  <U> Optional<U> createMetadataForLibrary(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleNativeTargetDescriptionArg args,
      Class<U> metadataClass) {

    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));

    // Forward to C/C++ library description.
    if (CxxLibraryDescription.METADATA_TYPE.containsAnyOf(buildTarget.getFlavors())) {
      CxxLibraryDescriptionArg.Builder delegateArg = CxxLibraryDescriptionArg.builder().from(args);
      AppleDescriptions.populateCxxLibraryDescriptionArg(
          pathResolver, delegateArg, args, buildTarget);
      return cxxLibraryMetadataFactory.createMetadata(
          buildTarget, resolver, cellRoots, delegateArg.build(), metadataClass);
    }

    if (metadataClass.isAssignableFrom(FrameworkDependencies.class)
        && buildTarget.getFlavors().contains(AppleDescriptions.FRAMEWORK_FLAVOR)) {
      Optional<Flavor> cxxPlatformFlavor =
          getCxxPlatformsProvider().getCxxPlatforms().getFlavor(buildTarget);
      Preconditions.checkState(
          cxxPlatformFlavor.isPresent(),
          "Could not find cxx platform in:\n%s",
          Joiner.on(", ").join(buildTarget.getFlavors()));
      ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
      for (BuildTarget dep : args.getDeps()) {
        Optional<FrameworkDependencies> frameworks =
            resolver.requireMetadata(
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
      // resolver for the parts that don't.
      BuildRule buildRule = resolver.requireRule(buildTarget);
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
                AppleLibrarySwiftMetadata.from(args.getSrcs(), pathResolver);
            return Optional.of(metadata).map(metadataClass::cast);
          }

        case APPLE_SWIFT_EXPORTED_OBJC_CXX_HEADERS:
          {
            BuildTarget swiftHeadersTarget =
                baseTarget.withAppendedFlavors(
                    Type.SWIFT_EXPORTED_OBJC_GENERATED_HEADER.getFlavor());
            HeaderSymlinkTreeWithHeaderMap headersRule =
                (HeaderSymlinkTreeWithHeaderMap) resolver.requireRule(swiftHeadersTarget);

            CxxHeaders headers =
                CxxSymlinkTreeHeaders.from(headersRule, CxxPreprocessables.IncludeType.LOCAL);
            return Optional.of(headers).map(metadataClass::cast);
          }

        case APPLE_SWIFT_OBJC_CXX_HEADERS:
          {
            BuildTarget swiftHeadersTarget =
                baseTarget.withAppendedFlavors(Type.SWIFT_OBJC_GENERATED_HEADER.getFlavor());
            HeaderSymlinkTreeWithHeaderMap headersRule =
                (HeaderSymlinkTreeWithHeaderMap) resolver.requireRule(swiftHeadersTarget);

            CxxHeaders headers =
                CxxSymlinkTreeHeaders.from(headersRule, CxxPreprocessables.IncludeType.LOCAL);
            return Optional.of(headers).map(metadataClass::cast);
          }

        case APPLE_SWIFT_MODULE_CXX_HEADERS:
          {
            BuildTarget swiftCompileTarget =
                baseTarget.withAppendedFlavors(Type.SWIFT_COMPILE.getFlavor());
            SwiftCompile compile = (SwiftCompile) resolver.requireRule(swiftCompileTarget);

            CxxHeaders headers =
                CxxHeadersDir.of(CxxPreprocessables.IncludeType.LOCAL, compile.getOutputPath());
            return Optional.of(headers).map(metadataClass::cast);
          }

        case APPLE_SWIFT_PREPROCESSOR_INPUT:
          {
            BuildTarget moduleHeadersTarget =
                baseTarget.withAppendedFlavors(
                    MetadataType.APPLE_SWIFT_MODULE_CXX_HEADERS.getFlavor());
            Optional<CxxHeaders> moduleHeaders =
                resolver.requireMetadata(moduleHeadersTarget, CxxHeaders.class);

            BuildTarget objcHeadersTarget =
                baseTarget.withAppendedFlavors(
                    MetadataType.APPLE_SWIFT_EXPORTED_OBJC_CXX_HEADERS.getFlavor());
            Optional<CxxHeaders> objcHeaders =
                resolver.requireMetadata(objcHeadersTarget, CxxHeaders.class);

            CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
            moduleHeaders.ifPresent(s -> builder.addIncludes(s));
            objcHeaders.ifPresent(s -> builder.addIncludes(s));

            CxxPreprocessorInput input = builder.build();
            return Optional.of(input).map(metadataClass::cast);
          }

        case APPLE_SWIFT_PRIVATE_PREPROCESSOR_INPUT:
          {
            BuildTarget objcHeadersTarget =
                baseTarget.withAppendedFlavors(
                    MetadataType.APPLE_SWIFT_OBJC_CXX_HEADERS.getFlavor());
            Optional<CxxHeaders> objcHeaders =
                resolver.requireMetadata(objcHeadersTarget, CxxHeaders.class);

            CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
            objcHeaders.ifPresent(s -> builder.addIncludes(s));

            CxxPreprocessorInput input = builder.build();
            return Optional.of(input).map(metadataClass::cast);
          }
      }
    }

    return Optional.empty();
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleLibraryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    return createMetadataForLibrary(buildTarget, resolver, cellRoots, args, metadataClass);
  }

  @Override
  public ImmutableSortedSet<Flavor> addImplicitFlavors(
      ImmutableSortedSet<Flavor> argDefaultFlavors) {
    // Use defaults.apple_library if present, but fall back to defaults.cxx_library otherwise.
    return cxxLibraryImplicitFlavors.addImplicitFlavorsForRuleTypes(
        argDefaultFlavors,
        DescriptionCache.getBuildRuleType(this),
        DescriptionCache.getBuildRuleType(CxxLibraryDescription.class));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractAppleLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    MultiarchFileInfos.checkTargetSupportsMultiarch(getAppleCxxPlatformDomain(), buildTarget);
    extraDepsBuilder.addAll(
        cxxLibraryFactory.getPlatformParseTimeDeps(buildTarget, constructorArg));
  }

  public static boolean isNotStaticallyLinkedLibraryNode(
      TargetNode<CxxLibraryDescription.CommonArg, ?> node) {
    SortedSet<Flavor> flavors = node.getBuildTarget().getFlavors();
    if (LIBRARY_TYPE.getFlavor(flavors).isPresent()) {
      return flavors.contains(CxxDescriptionEnhancer.SHARED_FLAVOR)
          || flavors.contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);
    } else {
      return node.getConstructorArg().getPreferredLinkage().equals(Optional.of(Linkage.SHARED));
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAppleLibraryDescriptionArg extends AppleNativeTargetDescriptionArg {
    Optional<SourcePath> getInfoPlist();

    ImmutableMap<String, String> getInfoPlistSubstitutions();
  }

  // CxxLibraryDescriptionDelegate

  private static boolean targetContainsSwift(BuildTarget target, BuildRuleResolver resolver) {
    BuildTarget metadataTarget = target.withFlavors(MetadataType.APPLE_SWIFT_METADATA.getFlavor());
    Optional<AppleLibrarySwiftMetadata> metadata =
        resolver.requireMetadata(metadataTarget, AppleLibrarySwiftMetadata.class);
    return metadata.map(m -> !m.getSwiftSources().isEmpty()).orElse(false);
  }

  public static Optional<CxxPreprocessorInput> queryMetadataCxxSwiftPreprocessorInput(
      BuildRuleResolver resolver,
      BuildTarget baseTarget,
      CxxPlatform platform,
      HeaderVisibility headerVisibility) {
    if (!targetContainsSwift(baseTarget, resolver)) {
      return Optional.empty();
    }

    MetadataType metadataType = null;
    switch (headerVisibility) {
      case PUBLIC:
        metadataType = MetadataType.APPLE_SWIFT_PREPROCESSOR_INPUT;
        break;
      case PRIVATE:
        metadataType = MetadataType.APPLE_SWIFT_PRIVATE_PREPROCESSOR_INPUT;
        break;
    }

    Preconditions.checkNotNull(metadataType);

    return resolver.requireMetadata(
        baseTarget.withAppendedFlavors(metadataType.getFlavor(), platform.getFlavor()),
        CxxPreprocessorInput.class);
  }

  @Override
  public Optional<CxxPreprocessorInput> getPreprocessorInput(
      BuildTarget target, BuildRuleResolver resolver, CxxPlatform platform) {
    if (!targetContainsSwift(target, resolver)) {
      return Optional.empty();
    }

    return queryMetadataCxxSwiftPreprocessorInput(
        resolver, target, platform, HeaderVisibility.PUBLIC);
  }

  @Override
  public Optional<CxxPreprocessorInput> getPrivatePreprocessorInput(
      BuildTarget target, BuildRuleResolver resolver, CxxPlatform platform) {
    if (!targetContainsSwift(target, resolver)) {
      return Optional.empty();
    }

    return queryMetadataCxxSwiftPreprocessorInput(
        resolver, target, platform, HeaderVisibility.PRIVATE);
  }

  @Override
  public Optional<HeaderSymlinkTree> getPrivateHeaderSymlinkTree(
      BuildTarget buildTarget, BuildRuleResolver ruleResolver, CxxPlatform cxxPlatform) {
    if (!targetContainsSwift(buildTarget, ruleResolver)) {
      return Optional.empty();
    }

    BuildTarget ruleTarget =
        AppleLibraryDescriptionSwiftEnhancer.createBuildTargetForObjCGeneratedHeaderBuildRule(
            buildTarget, HeaderVisibility.PRIVATE, cxxPlatform);
    BuildRule headerRule = ruleResolver.requireRule(ruleTarget);
    if (headerRule instanceof HeaderSymlinkTree) {
      return Optional.of((HeaderSymlinkTree) headerRule);
    }

    return Optional.empty();
  }

  @Override
  public Optional<ImmutableList<SourcePath>> getObjectFilePaths(
      BuildTarget target, BuildRuleResolver resolver, CxxPlatform cxxPlatform) {
    if (!targetContainsSwift(target, resolver)) {
      return Optional.empty();
    }

    BuildTarget swiftTarget =
        AppleLibraryDescriptionSwiftEnhancer.createBuildTargetForSwiftCompile(target, cxxPlatform);
    SwiftCompile compile = (SwiftCompile) resolver.requireRule(swiftTarget);
    return Optional.of(compile.getObjectPaths());
  }

  @Override
  public Optional<ImmutableList<NativeLinkable>> getNativeLinkableExportedDeps(
      BuildTarget target, BuildRuleResolver resolver, CxxPlatform platform) {
    if (!targetContainsSwift(target, resolver)) {
      return Optional.empty();
    }

    SwiftPlatformsProvider swiftPlatformsProvider =
        toolchainProvider.getByName(
            SwiftPlatformsProvider.DEFAULT_NAME, SwiftPlatformsProvider.class);
    FlavorDomain<SwiftPlatform> swiftPlatformFlavorDomain =
        swiftPlatformsProvider.getSwiftCxxPlatforms();

    BuildTarget targetWithPlatform = target.withAppendedFlavors(platform.getFlavor());
    Optional<SwiftPlatform> swiftPlatform = swiftPlatformFlavorDomain.getValue(targetWithPlatform);
    if (swiftPlatform.isPresent()) {
      return Optional.of(ImmutableList.of(new SwiftRuntimeNativeLinkable(swiftPlatform.get())));
    }

    return Optional.empty();
  }

  @Override
  public ImmutableList<Arg> getAdditionalExportedLinkerFlags(
      BuildTarget target, BuildRuleResolver resolver, CxxPlatform cxxPlatform) {
    if (!targetContainsSwift(target, resolver)) {
      return ImmutableList.of();
    }

    BuildTarget swiftTarget =
        AppleLibraryDescriptionSwiftEnhancer.createBuildTargetForSwiftCompile(target, cxxPlatform);
    SwiftCompile compile = (SwiftCompile) resolver.requireRule(swiftTarget);

    return compile.getAstLinkArgs();
  }

  @Override
  public boolean getShouldProduceLibraryArtifact(
      BuildTarget target,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole) {
    return targetContainsSwift(target, resolver);
  }

  private FlavorDomain<AppleCxxPlatform> getAppleCxxPlatformDomain() {
    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        toolchainProvider.getByName(
            AppleCxxPlatformsProvider.DEFAULT_NAME, AppleCxxPlatformsProvider.class);

    return appleCxxPlatformsProvider.getAppleCxxPlatforms();
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }
}
