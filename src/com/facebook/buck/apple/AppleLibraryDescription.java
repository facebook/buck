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

import static com.facebook.buck.swift.SwiftLibraryDescription.isSwiftTarget;

import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.ProvidesLinkedBinaryDeps;
import com.facebook.buck.cxx.StripStyle;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.ImplicitFlavorsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.util.Map;
import java.util.Set;

public class AppleLibraryDescription implements
    Description<AppleLibraryDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<AppleLibraryDescription.Arg>,
    ImplicitFlavorsInferringDescription,
    MetadataProvidingDescription<AppleLibraryDescription.Arg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("apple_library");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
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
      ImmutableFlavor.of("default"));

  private static final Predicate<Flavor> IS_SUPPORTED_FLAVOR = new Predicate<Flavor>() {
    @Override
    public boolean apply(Flavor flavor) {
      return SUPPORTED_FLAVORS.contains(flavor);
    }
  };

  private enum Type implements FlavorConvertible {
    HEADERS(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
    EXPORTED_HEADERS(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
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
  private final AppleDebugFormat defaultDebugFormat;

  public AppleLibraryDescription(
      CxxLibraryDescription delegate,
      SwiftLibraryDescription swiftDelegate,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      AppleDebugFormat defaultDebugFormat) {
    this.delegate = delegate;
    this.swiftDelegate = swiftDelegate;
    this.appleCxxPlatformFlavorDomain = appleCxxPlatformFlavorDomain;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.codeSignIdentityStore = codeSignIdentityStore;
    this.provisioningProfileStore = provisioningProfileStore;
    this.defaultDebugFormat = defaultDebugFormat;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public AppleLibraryDescription.Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return FluentIterable.from(flavors).allMatch(IS_SUPPORTED_FLAVOR) ||
        delegate.hasFlavors(flavors) ||
        swiftDelegate.hasFlavors(flavors);
  }

  @Override
  public <A extends AppleLibraryDescription.Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(
        params.getBuildTarget());
    if (type.isPresent() && type.get().getValue().equals(Type.FRAMEWORK)) {
      return createFrameworkBundleBuildRule(targetGraph, params, resolver, args);
    } else {
      return createLibraryBuildRule(
          targetGraph,
          params,
          resolver,
          args,
          args.linkStyle,
          Optional.<SourcePath>absent(),
          ImmutableSet.<BuildTarget>of());
    }
  }

  private <A extends Arg> BuildRule createFrameworkBundleBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    if (!args.infoPlist.isPresent()) {
      throw new HumanReadableException(
          "Cannot create framework for apple_library '%s':\n" +
          "No value specified for 'info_plist' attribute.",
          params.getBuildTarget().getUnflavoredBuildTarget());
    }
    if (!AppleDescriptions.INCLUDE_FRAMEWORKS.getValue(params.getBuildTarget()).isPresent()) {
      return resolver.requireRule(
          params.getBuildTarget().withAppendedFlavors(
              AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR));
    }
    AppleDebugFormat debugFormat = AppleDebugFormat.FLAVOR_DOMAIN
        .getValue(params.getBuildTarget())
        .or(defaultDebugFormat);
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
        Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.FRAMEWORK),
        args.productName,
        args.infoPlist.get(),
        args.infoPlistSubstitutions,
        args.deps.get(),
        args.tests.get(),
        debugFormat);
  }

  /**
   * @param targetGraph The target graph.
   * @param bundleLoader The binary in which the current library will be (dynamically) loaded into.
   *                     Only valid when building a shared library with MACH_O_BUNDLE link type.
   */
  public <A extends AppleNativeTargetDescriptionArg> BuildRule createLibraryBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      Optional<Linker.LinkableDepType> linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist) throws NoSuchBuildTargetException {
    Optional<BuildRule> swiftCompanionBuildRule = swiftDelegate.createCompanionBuildRule(
        targetGraph, params, resolver, args);
    if (swiftCompanionBuildRule.isPresent()) {
      // when creating a swift target, there is no need to proceed with apple binary rules,
      // otherwise, add this swift rule as a dependency.
      if (isSwiftTarget(params.getBuildTarget())) {
        return swiftCompanionBuildRule.get();
      } else {
        params = params.appendExtraDeps(ImmutableSet.of(swiftCompanionBuildRule.get()));
      }
    }

    // We explicitly remove strip flavor from params to make sure rule
    // has the same output regardless if we will strip or not.
    Optional<StripStyle> flavoredStripStyle =
        StripStyle.FLAVOR_DOMAIN.getValue(params.getBuildTarget());
    params = CxxStrip.removeStripStyleFlavorInParams(params, flavoredStripStyle);

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    BuildRule unstrippedBinaryRule = requireUnstrippedBuildRule(
        params,
        resolver,
        args,
        linkableDepType,
        bundleLoader,
        blacklist,
        pathResolver);

    if (!shouldWrapIntoDebuggableBinary(params.getBuildTarget(), unstrippedBinaryRule)) {
        return unstrippedBinaryRule;
    }

    // If we built a multiarch binary, we can just use the strip tool from any platform.
    // We pick the platform in this odd way due to FlavorDomain's restriction of allowing only one
    // matching flavor in the build target.
    CxxPlatform representativePlatform =
        delegate.getCxxPlatforms().getValue(
            Iterables.getFirst(
                Sets.intersection(
                    delegate.getCxxPlatforms().getFlavors(),
                    params.getBuildTarget().getFlavors()),
                defaultCxxPlatform.getFlavor()));
    BuildRule strippedBinaryRule = CxxDescriptionEnhancer.createCxxStripRule(
        CxxStrip.restoreStripStyleFlavorInParams(params, flavoredStripStyle),
        resolver,
        representativePlatform.getStrip(),
        flavoredStripStyle.or(StripStyle.NON_GLOBAL_SYMBOLS),
        pathResolver,
        unstrippedBinaryRule);

    return AppleDescriptions.createAppleDebuggableBinary(
        CxxStrip.restoreStripStyleFlavorInParams(params, flavoredStripStyle),
        resolver,
        strippedBinaryRule,
        (ProvidesLinkedBinaryDeps) unstrippedBinaryRule,
        AppleDebugFormat.FLAVOR_DOMAIN.getValue(params.getBuildTarget()).or(defaultDebugFormat),
        delegate.getCxxPlatforms(),
        delegate.getDefaultCxxPlatform(),
        appleCxxPlatformFlavorDomain);
  }

  private <A extends AppleNativeTargetDescriptionArg> BuildRule requireUnstrippedBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      Optional<Linker.LinkableDepType> linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      SourcePathResolver pathResolver) throws NoSuchBuildTargetException {
    Optional<MultiarchFileInfo> multiarchFileInfo =
        MultiarchFileInfos.create(appleCxxPlatformFlavorDomain, params.getBuildTarget());
    if (multiarchFileInfo.isPresent()) {
      ImmutableSortedSet.Builder<BuildRule> thinRules = ImmutableSortedSet.naturalOrder();
      for (BuildTarget thinTarget : multiarchFileInfo.get().getThinTargets()) {
        thinRules.add(
            requireSingleArchUnstrippedBuildRule(
                params.copyWithBuildTarget(thinTarget),
                resolver,
                args,
                linkableDepType,
                bundleLoader,
                blacklist,
                pathResolver));
      }
      return MultiarchFileInfos.requireMultiarchRule(
          // In the same manner that debug flavors are omitted from single-arch constituents, they
          // are omitted here as well.
          params.copyWithBuildTarget(
              params.getBuildTarget().withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors())),
          resolver,
          multiarchFileInfo.get(),
          thinRules.build());
    } else {
      return requireSingleArchUnstrippedBuildRule(
          params,
          resolver,
          args,
          linkableDepType,
          bundleLoader,
          blacklist,
          pathResolver);
    }
  }

  private <A extends AppleNativeTargetDescriptionArg> BuildRule
  requireSingleArchUnstrippedBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      Optional<Linker.LinkableDepType> linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      SourcePathResolver pathResolver) throws NoSuchBuildTargetException {

    CxxLibraryDescription.Arg delegateArg = delegate.createUnpopulatedConstructorArg();
    AppleDescriptions.populateCxxLibraryDescriptionArg(
        pathResolver,
        delegateArg,
        args,
        params.getBuildTarget());

    // remove all debug format related flavors from cxx rule so it always ends up in the same output
    BuildTarget unstrippedTarget = params.getBuildTarget()
        .withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors());

    Optional<BuildRule> existingRule = resolver.getRuleOptional(unstrippedTarget);
    if (existingRule.isPresent()) {
      return existingRule.get();
    } else {
      BuildRule rule = delegate.createBuildRule(
          params.copyWithBuildTarget(unstrippedTarget),
          resolver,
          delegateArg,
          linkableDepType,
          bundleLoader,
          blacklist);
      return resolver.addToIndex(rule);
    }
  }

  private boolean shouldWrapIntoDebuggableBinary(BuildTarget buildTarget, BuildRule buildRule) {
    if (!AppleDebugFormat.FLAVOR_DOMAIN.getValue(buildTarget).isPresent()) {
      return false;
    }
    if (!buildTarget.getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR) &&
        !buildTarget.getFlavors().contains(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR)) {
      return false;
    }

    return AppleDebuggableBinary.isBuildRuleDebuggable(buildRule);
  }


  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      A args,
      Class<U> metadataClass) throws NoSuchBuildTargetException {
    if (!metadataClass.isAssignableFrom(FrameworkDependencies.class)) {
      CxxLibraryDescription.Arg delegateArg = delegate.createUnpopulatedConstructorArg();
      AppleDescriptions.populateCxxLibraryDescriptionArg(
          new SourcePathResolver(resolver),
          delegateArg,
          args,
          buildTarget);
      return delegate.createMetadata(buildTarget, resolver, delegateArg, metadataClass);
    }
    Optional<Flavor> cxxPlatformFlavor = delegate.getCxxPlatforms().getFlavor(buildTarget);
    Preconditions.checkState(
        cxxPlatformFlavor.isPresent(),
        "Could not find cxx platform in:\n%s",
        Joiner.on(", ").join(buildTarget.getFlavors()));
    ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
    for (BuildTarget dep : args.deps.get()) {
      Optional<FrameworkDependencies> frameworks =
          resolver.requireMetadata(
              BuildTarget.builder(dep)
                  .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                  .addFlavors(cxxPlatformFlavor.get())
                  .build(),
              FrameworkDependencies.class);
      if (frameworks.isPresent()) {
        sourcePaths.addAll(frameworks.get().getSourcePaths());
      }
    }

    if (buildTarget.getFlavors().contains(AppleDescriptions.FRAMEWORK_FLAVOR)) {
      // Not all parts of Buck use require yet, so require the rule here so it's available in the
      // resolver for the parts that don't.
      resolver.requireRule(buildTarget);
      sourcePaths.add(new BuildTargetSourcePath(buildTarget));

    }
    return Optional.of(metadataClass.cast(FrameworkDependencies.of(sourcePaths.build())));
  }

  @Override
  public ImmutableSortedSet<Flavor> addImplicitFlavors(
      ImmutableSortedSet<Flavor> argDefaultFlavors) {
    // Use defaults.apple_library if present, but fall back to defaults.cxx_library otherwise.
    return delegate.addImplicitFlavorsForRuleTypes(
        argDefaultFlavors,
        TYPE,
        CxxLibraryDescription.TYPE);
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      final BuildTarget buildTarget,
      final CellPathResolver cellRoots,
      final AppleLibraryDescription.Arg constructorArg) {
    return findDepsForTargetFromConstructorArgs(
        buildTarget,
        cellRoots,
        (AppleNativeTargetDescriptionArg) constructorArg);
  }

  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      final BuildTarget buildTarget,
      final CellPathResolver cellRoots,
      final AppleNativeTargetDescriptionArg constructorArg) {
    return delegate.findDepsForTargetFromConstructorArgs(
            buildTarget,
            cellRoots,
            constructorArg);
  }

  public static boolean isSharedLibraryTarget(BuildTarget target) {
    return target.getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AppleNativeTargetDescriptionArg {
    public Optional<String> productName;
    public Optional<SourcePath> infoPlist;
    public Optional<ImmutableMap<String, String>> infoPlistSubstitutions;
  }

}
