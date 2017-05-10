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

import static com.facebook.buck.cxx.NativeLinkable.Linkage;
import static com.facebook.buck.swift.SwiftLibraryDescription.isSwiftTarget;

import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxLibraryDescriptionArg;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.ProvidesLinkedBinaryDeps;
import com.facebook.buck.cxx.StripStyle;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.ImplicitFlavorsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.Version;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Function;
import org.immutables.value.Value;

public class AppleLibraryDescription
    implements Description<AppleLibraryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            AppleLibraryDescription.AbstractAppleLibraryDescriptionArg>,
        ImplicitFlavorsInferringDescription,
        MetadataProvidingDescription<AppleLibraryDescriptionArg> {

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

  private enum Type implements FlavorConvertible {
    HEADERS(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
    EXPORTED_HEADERS(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
    SANDBOX(CxxDescriptionEnhancer.SANDBOX_TREE_FLAVOR),
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    STATIC_PIC(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR),
    MACH_O_BUNDLE(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR),
    FRAMEWORK(AppleDescriptions.FRAMEWORK_FLAVOR),
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

  public static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("C/C++ Library Type", Type.class);

  private final CxxLibraryDescription delegate;
  private final SwiftLibraryDescription swiftDelegate;
  private final FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain;
  private final CxxPlatform defaultCxxPlatform;
  private final CodeSignIdentityStore codeSignIdentityStore;
  private final ProvisioningProfileStore provisioningProfileStore;
  private final AppleConfig appleConfig;

  public AppleLibraryDescription(
      CxxLibraryDescription delegate,
      SwiftLibraryDescription swiftDelegate,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      AppleConfig appleConfig) {
    this.delegate = delegate;
    this.swiftDelegate = swiftDelegate;
    this.appleCxxPlatformFlavorDomain = appleCxxPlatformFlavorDomain;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.codeSignIdentityStore = codeSignIdentityStore;
    this.provisioningProfileStore = provisioningProfileStore;
    this.appleConfig = appleConfig;
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
    delegate.flavorDomains().ifPresent(domains -> builder.addAll(domains));
    swiftDelegate.flavorDomains().ifPresent(domains -> builder.addAll(domains));

    ImmutableSet<FlavorDomain<?>> result = builder.build();

    // Drop StripStyle because it's overridden by AppleDebugFormat
    result =
        result
            .stream()
            .filter(domain -> !domain.equals(StripStyle.FLAVOR_DOMAIN))
            .collect(MoreCollectors.toImmutableSet());

    return Optional.of(result);
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return FluentIterable.from(flavors).allMatch(SUPPORTED_FLAVORS::contains)
        || delegate.hasFlavors(flavors)
        || swiftDelegate.hasFlavors(flavors);
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    Optional<Map.Entry<Flavor, Type>> type =
        LIBRARY_TYPE.getFlavorAndValue(params.getBuildTarget());
    if (type.isPresent() && type.get().getValue().equals(Type.FRAMEWORK)) {
      return createFrameworkBundleBuildRule(targetGraph, params, resolver, args);
    } else {
      return createLibraryBuildRule(
          targetGraph,
          params,
          resolver,
          cellRoots,
          args,
          args::withExportedDeps,
          args.getLinkStyle(),
          Optional.empty(),
          ImmutableSet.of(),
          ImmutableSortedSet.of());
    }
  }

  private <A extends AbstractAppleLibraryDescriptionArg> BuildRule createFrameworkBundleBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      AppleLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    if (!args.getInfoPlist().isPresent()) {
      throw new HumanReadableException(
          "Cannot create framework for apple_library '%s':\n"
              + "No value specified for 'info_plist' attribute.",
          params.getBuildTarget().getUnflavoredBuildTarget());
    }
    if (!AppleDescriptions.INCLUDE_FRAMEWORKS.getValue(params.getBuildTarget()).isPresent()) {
      return resolver.requireRule(
          params.getBuildTarget().withAppendedFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR));
    }
    AppleDebugFormat debugFormat =
        AppleDebugFormat.FLAVOR_DOMAIN
            .getValue(params.getBuildTarget())
            .orElse(appleConfig.getDefaultDebugInfoFormatForLibraries());
    if (!params.getBuildTarget().getFlavors().contains(debugFormat.getFlavor())) {
      return resolver.requireRule(
          params.getBuildTarget().withAppendedFlavors(debugFormat.getFlavor()));
    }

    return AppleDescriptions.createAppleBundle(
        delegate.getCxxPlatforms(),
        defaultCxxPlatform,
        appleCxxPlatformFlavorDomain,
        targetGraph,
        params,
        resolver,
        codeSignIdentityStore,
        provisioningProfileStore,
        params.getBuildTarget(),
        Either.ofLeft(AppleBundleExtension.FRAMEWORK),
        Optional.empty(),
        args.getInfoPlist().get(),
        args.getInfoPlistSubstitutions(),
        args.getDeps(),
        args.getTests(),
        debugFormat,
        appleConfig.useDryRunCodeSigning(),
        appleConfig.cacheBundlesAndPackages());
  }

  /**
   * @param targetGraph The target graph.
   * @param cellRoots The roots of known cells.
   * @param bundleLoader The binary in which the current library will be (dynamically) loaded into.
   */
  public <A extends AppleNativeTargetDescriptionArg> BuildRule createLibraryBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args,
      Function<ImmutableSortedSet<BuildTarget>, A> copyArgsWithNewExportedDeps,
      Optional<Linker.LinkableDepType> linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      ImmutableSortedSet<BuildTarget> extraCxxDeps)
      throws NoSuchBuildTargetException {
    // We explicitly remove flavors from params to make sure rule
    // has the same output regardless if we will strip or not.
    Optional<StripStyle> flavoredStripStyle =
        StripStyle.FLAVOR_DOMAIN.getValue(params.getBuildTarget());
    params = CxxStrip.removeStripStyleFlavorInParams(params, flavoredStripStyle);

    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));

    BuildRule unstrippedBinaryRule =
        requireUnstrippedBuildRule(
            params,
            resolver,
            cellRoots,
            targetGraph,
            args,
            copyArgsWithNewExportedDeps,
            linkableDepType,
            bundleLoader,
            blacklist,
            pathResolver,
            extraCxxDeps);

    if (!shouldWrapIntoDebuggableBinary(params.getBuildTarget(), unstrippedBinaryRule)) {
      return unstrippedBinaryRule;
    }

    // If we built a multiarch binary, we can just use the strip tool from any platform.
    // We pick the platform in this odd way due to FlavorDomain's restriction of allowing only one
    // matching flavor in the build target.
    CxxPlatform representativePlatform =
        delegate
            .getCxxPlatforms()
            .getValue(
                Iterables.getFirst(
                    Sets.intersection(
                        delegate.getCxxPlatforms().getFlavors(),
                        params.getBuildTarget().getFlavors()),
                    defaultCxxPlatform.getFlavor()));

    params = CxxStrip.restoreStripStyleFlavorInParams(params, flavoredStripStyle);

    BuildRule strippedBinaryRule =
        CxxDescriptionEnhancer.createCxxStripRule(
            params,
            resolver,
            flavoredStripStyle.orElse(StripStyle.NON_GLOBAL_SYMBOLS),
            unstrippedBinaryRule,
            representativePlatform);

    return AppleDescriptions.createAppleDebuggableBinary(
        params,
        resolver,
        strippedBinaryRule,
        (ProvidesLinkedBinaryDeps) unstrippedBinaryRule,
        AppleDebugFormat.FLAVOR_DOMAIN
            .getValue(params.getBuildTarget())
            .orElse(appleConfig.getDefaultDebugInfoFormatForLibraries()),
        delegate.getCxxPlatforms(),
        delegate.getDefaultCxxPlatform(),
        appleCxxPlatformFlavorDomain);
  }

  private <A extends AppleNativeTargetDescriptionArg> BuildRule requireUnstrippedBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      TargetGraph targetGraph,
      A args,
      Function<ImmutableSortedSet<BuildTarget>, A> copyArgsWithNewExportedDeps,
      Optional<Linker.LinkableDepType> linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      SourcePathResolver pathResolver,
      ImmutableSortedSet<BuildTarget> extraCxxDeps)
      throws NoSuchBuildTargetException {
    Optional<MultiarchFileInfo> multiarchFileInfo =
        MultiarchFileInfos.create(appleCxxPlatformFlavorDomain, params.getBuildTarget());
    if (multiarchFileInfo.isPresent()) {
      ImmutableSortedSet.Builder<BuildRule> thinRules = ImmutableSortedSet.naturalOrder();
      for (BuildTarget thinTarget : multiarchFileInfo.get().getThinTargets()) {
        thinRules.add(
            requireSingleArchUnstrippedBuildRule(
                params.withBuildTarget(thinTarget),
                resolver,
                cellRoots,
                targetGraph,
                args,
                copyArgsWithNewExportedDeps,
                linkableDepType,
                bundleLoader,
                blacklist,
                pathResolver,
                extraCxxDeps));
      }
      return MultiarchFileInfos.requireMultiarchRule(
          // In the same manner that debug flavors are omitted from single-arch constituents, they
          // are omitted here as well.
          params.withBuildTarget(
              params.getBuildTarget().withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors())),
          resolver,
          multiarchFileInfo.get(),
          thinRules.build());
    } else {
      return requireSingleArchUnstrippedBuildRule(
          params,
          resolver,
          cellRoots,
          targetGraph,
          args,
          copyArgsWithNewExportedDeps,
          linkableDepType,
          bundleLoader,
          blacklist,
          pathResolver,
          extraCxxDeps);
    }
  }

  private <A extends AppleNativeTargetDescriptionArg>
      BuildRule requireSingleArchUnstrippedBuildRule(
          BuildRuleParams params,
          BuildRuleResolver resolver,
          CellPathResolver cellRoots,
          TargetGraph targetGraph,
          A args,
          Function<ImmutableSortedSet<BuildTarget>, A> copyArgsWithNewExportedDeps,
          Optional<Linker.LinkableDepType> linkableDepType,
          Optional<SourcePath> bundleLoader,
          ImmutableSet<BuildTarget> blacklist,
          SourcePathResolver pathResolver,
          ImmutableSortedSet<BuildTarget> extraCxxDeps)
          throws NoSuchBuildTargetException {

    Optional<BuildRule> swiftCompanionBuildRule =
        swiftDelegate.createCompanionBuildRule(targetGraph, params, resolver, cellRoots, args);
    if (swiftCompanionBuildRule.isPresent()) {
      // when creating a swift target, there is no need to proceed with apple binary rules,
      // otherwise, add this swift rule as a dependency.
      if (isSwiftTarget(params.getBuildTarget())) {
        return swiftCompanionBuildRule.get();
      } else {
        args =
            copyArgsWithNewExportedDeps.apply(
                ImmutableSortedSet.<BuildTarget>naturalOrder()
                    .addAll(args.getExportedDeps())
                    .add(swiftCompanionBuildRule.get().getBuildTarget())
                    .build());
        params = params.copyAppendingExtraDeps(ImmutableSet.of(swiftCompanionBuildRule.get()));
        extraCxxDeps =
            ImmutableSortedSet.<BuildTarget>naturalOrder()
                .addAll(extraCxxDeps)
                .add(swiftCompanionBuildRule.get().getBuildTarget())
                .build();
      }
    }

    CxxLibraryDescriptionArg.Builder delegateArg = CxxLibraryDescriptionArg.builder().from(args);
    AppleDescriptions.populateCxxLibraryDescriptionArg(
        pathResolver, delegateArg, args, params.getBuildTarget());

    // remove some flavors from cxx rule that don't affect the rule output
    BuildTarget unstrippedTarget =
        params.getBuildTarget().withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors());
    if (AppleDescriptions.flavorsDoNotAllowLinkerMapMode(params)) {
      unstrippedTarget = unstrippedTarget.withoutFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor());
    }

    Optional<BuildRule> existingRule = resolver.getRuleOptional(unstrippedTarget);
    if (existingRule.isPresent()) {
      return existingRule.get();
    } else {
      BuildRule rule =
          delegate.createBuildRule(
              params.withBuildTarget(unstrippedTarget),
              resolver,
              cellRoots,
              delegateArg.build(),
              linkableDepType,
              bundleLoader,
              blacklist,
              extraCxxDeps);
      return resolver.addToIndex(rule);
    }
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
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      AppleNativeTargetDescriptionArg args,
      Class<U> metadataClass)
      throws NoSuchBuildTargetException {

    // Forward to C/C++ library description.
    if (CxxLibraryDescription.METADATA_TYPE.containsAnyOf(buildTarget.getFlavors())) {
      CxxLibraryDescriptionArg.Builder delegateArg = CxxLibraryDescriptionArg.builder().from(args);
      AppleDescriptions.populateCxxLibraryDescriptionArg(
          new SourcePathResolver(new SourcePathRuleFinder(resolver)),
          delegateArg,
          args,
          buildTarget);
      return delegate.createMetadata(
          buildTarget, resolver, delegateArg.build(), selectedVersions, metadataClass);
    }

    if (metadataClass.isAssignableFrom(FrameworkDependencies.class)
        && buildTarget.getFlavors().contains(AppleDescriptions.FRAMEWORK_FLAVOR)) {
      Optional<Flavor> cxxPlatformFlavor = delegate.getCxxPlatforms().getFlavor(buildTarget);
      Preconditions.checkState(
          cxxPlatformFlavor.isPresent(),
          "Could not find cxx platform in:\n%s",
          Joiner.on(", ").join(buildTarget.getFlavors()));
      ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
      for (BuildTarget dep : args.getDeps()) {
        Optional<FrameworkDependencies> frameworks =
            resolver.requireMetadata(
                BuildTarget.builder(dep)
                    .addFlavors(AppleDescriptions.FRAMEWORK_FLAVOR)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .addFlavors(cxxPlatformFlavor.get())
                    .build(),
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

    return Optional.empty();
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      AppleLibraryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass)
      throws NoSuchBuildTargetException {
    return createMetadataForLibrary(buildTarget, resolver, selectedVersions, args, metadataClass);
  }

  @Override
  public ImmutableSortedSet<Flavor> addImplicitFlavors(
      ImmutableSortedSet<Flavor> argDefaultFlavors) {
    // Use defaults.apple_library if present, but fall back to defaults.cxx_library otherwise.
    return delegate.addImplicitFlavorsForRuleTypes(
        argDefaultFlavors,
        Description.getBuildRuleType(this),
        Description.getBuildRuleType(CxxLibraryDescription.class));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      final BuildTarget buildTarget,
      final CellPathResolver cellRoots,
      final AbstractAppleLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    findDepsForTargetFromConstructorArgs(
        buildTarget,
        cellRoots,
        (AppleNativeTargetDescriptionArg) constructorArg,
        extraDepsBuilder,
        targetGraphOnlyDepsBuilder);
  }

  public void findDepsForTargetFromConstructorArgs(
      final BuildTarget buildTarget,
      final CellPathResolver cellRoots,
      final AppleNativeTargetDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    delegate.findDepsForTargetFromConstructorArgs(
        buildTarget, cellRoots, constructorArg, extraDepsBuilder, targetGraphOnlyDepsBuilder);
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

    @Value.Default
    default boolean isModular() {
      return false;
    }
  }
}
