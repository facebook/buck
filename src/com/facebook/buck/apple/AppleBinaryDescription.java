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

import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxBinaryDescriptionArg;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.cxx.HasAppleDebugSymbolDeps;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.ImplicitFlavorsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.Version;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

public class AppleBinaryDescription
    implements Description<AppleBinaryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<AppleBinaryDescription.AbstractAppleBinaryDescriptionArg>,
        ImplicitFlavorsInferringDescription,
        MetadataProvidingDescription<AppleBinaryDescriptionArg> {

  public static final Flavor APP_FLAVOR = InternalFlavor.of("app");
  public static final Sets.SetView<Flavor> NON_DELEGATE_FLAVORS =
      Sets.union(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors(), ImmutableSet.of(APP_FLAVOR));
  public static final Flavor LEGACY_WATCH_FLAVOR = InternalFlavor.of("legacy_watch");

  @SuppressWarnings("PMD") // PMD doesn't understand method references
  private static final Set<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(
          APP_FLAVOR,
          CxxCompilationDatabase.COMPILATION_DATABASE,
          CxxCompilationDatabase.UBER_COMPILATION_DATABASE,
          AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
          AppleDebugFormat.DWARF.getFlavor(),
          AppleDebugFormat.NONE.getFlavor(),
          LinkerMapMode.NO_LINKER_MAP.getFlavor());

  private final CxxBinaryDescription delegate;
  private final Optional<SwiftLibraryDescription> swiftDelegate;
  private final FlavorDomain<AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms;
  private final CodeSignIdentityStore codeSignIdentityStore;
  private final ProvisioningProfileStore provisioningProfileStore;
  private final AppleConfig appleConfig;

  public AppleBinaryDescription(
      CxxBinaryDescription delegate,
      SwiftLibraryDescription swiftDelegate,
      FlavorDomain<AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      AppleConfig appleConfig) {
    this.delegate = delegate;
    this.swiftDelegate =
        appleConfig.shouldUseSwiftDelegate() ? Optional.of(swiftDelegate) : Optional.empty();
    this.platformFlavorsToAppleCxxPlatforms = platformFlavorsToAppleCxxPlatforms;
    this.codeSignIdentityStore = codeSignIdentityStore;
    this.provisioningProfileStore = provisioningProfileStore;
    this.appleConfig = appleConfig;
  }

  @Override
  public Class<AppleBinaryDescriptionArg> getConstructorArgType() {
    return AppleBinaryDescriptionArg.class;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    ImmutableSet.Builder<FlavorDomain<?>> builder = ImmutableSet.builder();

    ImmutableSet<FlavorDomain<?>> localDomains = ImmutableSet.of(AppleDebugFormat.FLAVOR_DOMAIN);

    builder.addAll(localDomains);
    delegate.flavorDomains().ifPresent(domains -> builder.addAll(domains));
    swiftDelegate
        .flatMap(swift -> swift.flavorDomains())
        .ifPresent(domains -> builder.addAll(domains));

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
    if (FluentIterable.from(flavors).allMatch(SUPPORTED_FLAVORS::contains)) {
      return true;
    }
    ImmutableSet<Flavor> delegateFlavors =
        ImmutableSet.copyOf(Sets.difference(flavors, NON_DELEGATE_FLAVORS));
    if (swiftDelegate.map(swift -> swift.hasFlavors(delegateFlavors)).orElse(false)) {
      return true;
    }
    ImmutableList<ImmutableSortedSet<Flavor>> thinFlavorSets =
        generateThinDelegateFlavors(delegateFlavors);
    if (thinFlavorSets.size() > 0) {
      return Iterables.all(thinFlavorSets, delegate::hasFlavors);
    } else {
      return delegate.hasFlavors(delegateFlavors);
    }
  }

  private ImmutableList<ImmutableSortedSet<Flavor>> generateThinDelegateFlavors(
      ImmutableSet<Flavor> delegateFlavors) {
    return MultiarchFileInfos.generateThinFlavors(
        platformFlavorsToAppleCxxPlatforms.getFlavors(),
        ImmutableSortedSet.copyOf(delegateFlavors));
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleBinaryDescriptionArg args) {
    if (buildTarget.getFlavors().contains(APP_FLAVOR)) {
      return createBundleBuildRule(
          targetGraph, buildTarget, projectFilesystem, params, resolver, args);
    } else {
      return createBinaryBuildRule(
          targetGraph, buildTarget, projectFilesystem, params, resolver, cellRoots, args);
    }
  }

  // We want to wrap only if we have explicit debug flavor. This is because we don't want to
  // force dSYM generation in case if its enabled by default in config. We just want the binary,
  // so unless flavor is explicitly set, lets just produce binary!
  private boolean shouldWrapIntoAppleDebuggableBinary(
      BuildTarget buildTarget, BuildRule binaryBuildRule) {
    Optional<AppleDebugFormat> explicitDebugInfoFormat =
        AppleDebugFormat.FLAVOR_DOMAIN.getValue(buildTarget);
    boolean binaryIsWrappable = AppleDebuggableBinary.canWrapBinaryBuildRule(binaryBuildRule);
    return explicitDebugInfoFormat.isPresent() && binaryIsWrappable;
  }

  private BuildRule createBinaryBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleBinaryDescriptionArg args) {
    // remove some flavors so binary will have the same output regardless their values
    BuildTarget unstrippedBinaryBuildTarget =
        buildTarget
            .withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors())
            .withoutFlavors(StripStyle.FLAVOR_DOMAIN.getFlavors());

    BuildRule unstrippedBinaryRule =
        createBinary(
            targetGraph,
            unstrippedBinaryBuildTarget,
            projectFilesystem,
            params,
            resolver,
            cellRoots,
            args);

    if (shouldWrapIntoAppleDebuggableBinary(buildTarget, unstrippedBinaryRule)) {
      return createAppleDebuggableBinary(
          targetGraph,
          buildTarget,
          projectFilesystem,
          params,
          resolver,
          cellRoots,
          args,
          unstrippedBinaryBuildTarget,
          (HasAppleDebugSymbolDeps) unstrippedBinaryRule);
    } else {
      return unstrippedBinaryRule;
    }
  }

  private BuildRule createAppleDebuggableBinary(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleBinaryDescriptionArg args,
      BuildTarget unstrippedBinaryBuildTarget,
      HasAppleDebugSymbolDeps unstrippedBinaryRule) {
    BuildTarget strippedBinaryBuildTarget =
        unstrippedBinaryBuildTarget.withAppendedFlavors(
            CxxStrip.RULE_FLAVOR,
            StripStyle.FLAVOR_DOMAIN
                .getFlavor(buildTarget.getFlavors())
                .orElse(StripStyle.NON_GLOBAL_SYMBOLS.getFlavor()));
    BuildRule strippedBinaryRule =
        createBinary(
            targetGraph,
            strippedBinaryBuildTarget,
            projectFilesystem,
            params,
            resolver,
            cellRoots,
            args);
    return AppleDescriptions.createAppleDebuggableBinary(
        unstrippedBinaryBuildTarget,
        projectFilesystem,
        resolver,
        strippedBinaryRule,
        unstrippedBinaryRule,
        AppleDebugFormat.FLAVOR_DOMAIN.getRequiredValue(buildTarget),
        delegate.getCxxPlatforms(),
        delegate.getDefaultCxxFlavor(),
        platformFlavorsToAppleCxxPlatforms);
  }

  private BuildRule createBundleBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      AppleBinaryDescriptionArg args) {
    if (!args.getInfoPlist().isPresent()) {
      throw new HumanReadableException(
          "Cannot create application for apple_binary '%s':\n",
          "No value specified for 'info_plist' attribute.", buildTarget.getUnflavoredBuildTarget());
    }
    AppleDebugFormat flavoredDebugFormat =
        AppleDebugFormat.FLAVOR_DOMAIN
            .getValue(buildTarget)
            .orElse(appleConfig.getDefaultDebugInfoFormatForBinaries());
    if (!buildTarget.getFlavors().contains(flavoredDebugFormat.getFlavor())) {
      return resolver.requireRule(buildTarget.withAppendedFlavors(flavoredDebugFormat.getFlavor()));
    }
    if (!AppleDescriptions.INCLUDE_FRAMEWORKS.getValue(buildTarget).isPresent()) {
      CxxPlatform cxxPlatform =
          delegate
              .getCxxPlatforms()
              .getValue(buildTarget)
              .orElse(delegate.getCxxPlatforms().getValue(delegate.getDefaultCxxFlavor()));
      ApplePlatform applePlatform =
          platformFlavorsToAppleCxxPlatforms
              .getValue(cxxPlatform.getFlavor())
              .getAppleSdk()
              .getApplePlatform();
      if (applePlatform.getAppIncludesFrameworks()) {
        return resolver.requireRule(
            buildTarget.withAppendedFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR));
      }
      return resolver.requireRule(
          buildTarget.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR));
    }
    BuildTarget binaryTarget = buildTarget.withoutFlavors(APP_FLAVOR);
    return AppleDescriptions.createAppleBundle(
        delegate.getCxxPlatforms(),
        delegate.getDefaultCxxFlavor(),
        platformFlavorsToAppleCxxPlatforms,
        targetGraph,
        buildTarget,
        projectFilesystem,
        params,
        resolver,
        codeSignIdentityStore,
        provisioningProfileStore,
        binaryTarget,
        Either.ofLeft(AppleBundleExtension.APP),
        Optional.empty(),
        args.getInfoPlist().get(),
        args.getInfoPlistSubstitutions(),
        args.getDeps(),
        args.getTests(),
        flavoredDebugFormat,
        appleConfig.useDryRunCodeSigning(),
        appleConfig.cacheBundlesAndPackages(),
        appleConfig.assetCatalogValidation());
  }

  private BuildRule createBinary(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleBinaryDescriptionArg args) {

    if (AppleDescriptions.flavorsDoNotAllowLinkerMapMode(buildTarget)) {
      buildTarget = buildTarget.withoutFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor());
    }

    Optional<MultiarchFileInfo> fatBinaryInfo =
        MultiarchFileInfos.create(platformFlavorsToAppleCxxPlatforms, buildTarget);
    if (fatBinaryInfo.isPresent()) {
      if (shouldUseStubBinary(buildTarget)) {
        BuildTarget thinTarget = Iterables.getFirst(fatBinaryInfo.get().getThinTargets(), null);
        return requireThinBinary(
            targetGraph, thinTarget, projectFilesystem, params, resolver, cellRoots, args);
      }

      ImmutableSortedSet.Builder<BuildRule> thinRules = ImmutableSortedSet.naturalOrder();
      for (BuildTarget thinTarget : fatBinaryInfo.get().getThinTargets()) {
        thinRules.add(
            requireThinBinary(
                targetGraph, thinTarget, projectFilesystem, params, resolver, cellRoots, args));
      }
      return MultiarchFileInfos.requireMultiarchRule(
          buildTarget, projectFilesystem, params, resolver, fatBinaryInfo.get(), thinRules.build());
    } else {
      return requireThinBinary(
          targetGraph, buildTarget, projectFilesystem, params, resolver, cellRoots, args);
    }
  }

  private BuildRule requireThinBinary(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleBinaryDescriptionArg args) {
    Optional<BuildRule> existingThinRule = resolver.getRuleOptional(buildTarget);
    if (existingThinRule.isPresent()) {
      return existingThinRule.get();
    }

    ImmutableSortedSet.Builder<BuildTarget> extraCxxDepsBuilder = ImmutableSortedSet.naturalOrder();
    final BuildRuleParams inputParams = params;
    Optional<BuildRule> swiftCompanionBuildRule =
        swiftDelegate.flatMap(
            swift ->
                swift.createCompanionBuildRule(
                    targetGraph,
                    buildTarget,
                    projectFilesystem,
                    inputParams,
                    resolver,
                    cellRoots,
                    args));
    if (swiftCompanionBuildRule.isPresent()) {
      // when creating a swift target, there is no need to proceed with apple binary rules,
      // otherwise, add this swift rule as a dependency.
      if (isSwiftTarget(buildTarget)) {
        return swiftCompanionBuildRule.get();
      } else {
        extraCxxDepsBuilder.add(swiftCompanionBuildRule.get().getBuildTarget());
        params = params.copyAppendingExtraDeps(ImmutableSet.of(swiftCompanionBuildRule.get()));
      }
    }
    ImmutableSortedSet<BuildTarget> extraCxxDeps = extraCxxDepsBuilder.build();

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    Optional<Path> stubBinaryPath = getStubBinaryPath(buildTarget, args);
    if (shouldUseStubBinary(buildTarget) && stubBinaryPath.isPresent()) {
      try {
        return resolver.addToIndex(
            new WriteFile(
                buildTarget,
                projectFilesystem,
                params,
                Files.readAllBytes(stubBinaryPath.get()),
                BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s"),
                true));
      } catch (IOException e) {
        throw new HumanReadableException("Could not read stub binary " + stubBinaryPath.get());
      }
    } else {
      CxxBinaryDescriptionArg.Builder delegateArg = CxxBinaryDescriptionArg.builder().from(args);
      AppleDescriptions.populateCxxBinaryDescriptionArg(
          pathResolver, delegateArg, args, buildTarget);
      return resolver.addToIndex(
          delegate.createBuildRule(
              buildTarget,
              projectFilesystem,
              params.getExtraDeps(),
              resolver,
              cellRoots,
              delegateArg.build(),
              extraCxxDeps));
    }
  }

  private boolean shouldUseStubBinary(BuildTarget buildTarget) {
    ImmutableSortedSet<Flavor> flavors = buildTarget.getFlavors();
    return (flavors.contains(AppleBundleDescription.WATCH_OS_FLAVOR)
        || flavors.contains(AppleBundleDescription.WATCH_SIMULATOR_FLAVOR)
        || flavors.contains(LEGACY_WATCH_FLAVOR));
  }

  private Optional<Path> getStubBinaryPath(
      BuildTarget buildTarget, AppleBinaryDescriptionArg args) {
    Optional<Path> stubBinaryPath = Optional.empty();
    Optional<AppleCxxPlatform> appleCxxPlatform = getAppleCxxPlatformFromParams(buildTarget);
    if (appleCxxPlatform.isPresent() && args.getSrcs().isEmpty()) {
      stubBinaryPath = appleCxxPlatform.get().getStubBinary();
    }
    return stubBinaryPath;
  }

  private Optional<AppleCxxPlatform> getAppleCxxPlatformFromParams(BuildTarget buildTarget) {
    return platformFlavorsToAppleCxxPlatforms.getValue(buildTarget);
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleBinaryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    if (!metadataClass.isAssignableFrom(FrameworkDependencies.class)) {
      CxxBinaryDescriptionArg.Builder delegateArg = CxxBinaryDescriptionArg.builder().from(args);
      AppleDescriptions.populateCxxBinaryDescriptionArg(
          DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver)),
          delegateArg,
          args,
          buildTarget);
      return delegate.createMetadata(
          buildTarget, resolver, cellRoots, delegateArg.build(), selectedVersions, metadataClass);
    }

    Optional<Flavor> cxxPlatformFlavor = delegate.getCxxPlatforms().getFlavor(buildTarget);
    Preconditions.checkState(
        cxxPlatformFlavor.isPresent(),
        "Could not find cxx platform in:\n%s",
        Joiner.on(", ").join(buildTarget.getFlavors()));
    ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
    for (BuildTarget dep : args.getDeps()) {
      Optional<FrameworkDependencies> frameworks =
          resolver.requireMetadata(
              dep.withAppendedFlavors(
                  AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR, cxxPlatformFlavor.get()),
              FrameworkDependencies.class);
      if (frameworks.isPresent()) {
        sourcePaths.addAll(frameworks.get().getSourcePaths());
      }
    }

    return Optional.of(metadataClass.cast(FrameworkDependencies.of(sourcePaths.build())));
  }

  @Override
  public ImmutableSortedSet<Flavor> addImplicitFlavors(
      ImmutableSortedSet<Flavor> argDefaultFlavors) {
    // Use defaults.apple_binary if present, but fall back to defaults.cxx_binary otherwise.
    return delegate.addImplicitFlavorsForRuleTypes(
        argDefaultFlavors,
        Description.getBuildRuleType(this),
        Description.getBuildRuleType(CxxBinaryDescription.class));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      final BuildTarget buildTarget,
      final CellPathResolver cellRoots,
      final AbstractAppleBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    ImmutableList<ImmutableSortedSet<Flavor>> thinFlavorSets =
        generateThinDelegateFlavors(buildTarget.getFlavors());
    if (thinFlavorSets.size() > 0) {
      for (ImmutableSortedSet<Flavor> flavors : thinFlavorSets) {
        extraDepsBuilder.addAll(
            delegate.findDepsForTargetFromConstructorArgs(
                buildTarget.withFlavors(flavors), Optional.empty()));
      }
    } else {
      extraDepsBuilder.addAll(
          delegate.findDepsForTargetFromConstructorArgs(buildTarget, Optional.empty()));
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAppleBinaryDescriptionArg extends AppleNativeTargetDescriptionArg {
    Optional<SourcePath> getInfoPlist();

    ImmutableMap<String, String> getInfoPlistSubstitutions();
  }
}
