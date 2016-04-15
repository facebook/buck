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

import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.ProvidesStaticLibraryDeps;
import com.facebook.buck.cxx.StripStyle;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

public class AppleBinaryDescription implements
    Description<AppleBinaryDescription.Arg>,
    Flavored,
    MetadataProvidingDescription<AppleBinaryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("apple_binary");
  public static final Flavor APP_FLAVOR = ImmutableFlavor.of("app");
  public static final Flavor LEGACY_WATCH_FLAVOR = ImmutableFlavor.of("legacy_watch");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      APP_FLAVOR,
      CxxCompilationDatabase.COMPILATION_DATABASE,
      CxxCompilationDatabase.UBER_COMPILATION_DATABASE,
      AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
      AppleDebugFormat.DWARF.getFlavor(),
      AppleDebugFormat.NONE.getFlavor());

  private static final Predicate<Flavor> IS_SUPPORTED_FLAVOR = new Predicate<Flavor>() {
    @Override
    public boolean apply(Flavor flavor) {
      return SUPPORTED_FLAVORS.contains(flavor);
    }
  };

  private final CxxBinaryDescription delegate;
  private final FlavorDomain<AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms;
  private final CodeSignIdentityStore codeSignIdentityStore;
  private final ProvisioningProfileStore provisioningProfileStore;
  private final AppleDebugFormat defaultDebugFormat;

  public AppleBinaryDescription(
      CxxBinaryDescription delegate,
      FlavorDomain<AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      AppleDebugFormat defaultDebugFormat) {
    this.delegate = delegate;
    this.platformFlavorsToAppleCxxPlatforms = platformFlavorsToAppleCxxPlatforms;
    this.codeSignIdentityStore = codeSignIdentityStore;
    this.provisioningProfileStore = provisioningProfileStore;
    this.defaultDebugFormat = defaultDebugFormat;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public AppleBinaryDescription.Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    if (FluentIterable.from(flavors).allMatch(IS_SUPPORTED_FLAVOR)) {
      return true;
    }
    final ImmutableSet<Flavor> delegateFlavors = ImmutableSet.copyOf(
        Sets.difference(
            flavors,
            Sets.union(
                AppleDebugFormat.FLAVOR_DOMAIN.getFlavors(),
                ImmutableSet.of(APP_FLAVOR))));
    Collection<ImmutableSortedSet<Flavor>> thinFlavorSets =
        FatBinaryInfos.generateThinFlavors(
            platformFlavorsToAppleCxxPlatforms.getFlavors(),
            ImmutableSortedSet.copyOf(delegateFlavors));
    if (thinFlavorSets.size() > 1) {
      return Iterables.all(
          thinFlavorSets,
          new Predicate<ImmutableSortedSet<Flavor>>() {
            @Override
            public boolean apply(ImmutableSortedSet<Flavor> input) {
              return delegate.hasFlavors(input);
            }
          });
    } else {
      return delegate.hasFlavors(delegateFlavors);
    }
  }

  @Override
  public <A extends AppleBinaryDescription.Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    if (params.getBuildTarget().getFlavors().contains(APP_FLAVOR)) {
      return createBundleBuildRule(targetGraph, params, resolver, args);
    } else {
      return createBinaryBuildRule(targetGraph, params, resolver, args);
    }
  }

  // We want to wrap only if we have explicit debug flavor. This is because we don't want to
  // force dSYM generation in case if its enabled by default in config. We just want the binary,
  // so unless flavor is explicitly set, lets just produce binary!
  private boolean shouldWrapIntoAppleDebuggableBinary(
      BuildTarget buildTarget,
      BuildRule binaryBuildRule) {
    Optional<AppleDebugFormat> explicitDebugInfoFormat =
        AppleDebugFormat.FLAVOR_DOMAIN.getValue(buildTarget);
    boolean binaryIsWrappable = AppleDebuggableBinary.canWrapBinaryBuildRule(binaryBuildRule);
    return explicitDebugInfoFormat.isPresent() && binaryIsWrappable;
  }

  private <A extends Arg> BuildRule createBinaryBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    // remove debug format flavors so binary will have the same output regardless of debug format
    BuildTarget unstrippedBinaryBuildTarget = params.getBuildTarget()
        .withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors())
        .withoutFlavors(StripStyle.FLAVOR_DOMAIN.getFlavors());

    BuildRule unstrippedBinaryRule = createBinary(
        targetGraph,
        params.copyWithBuildTarget(unstrippedBinaryBuildTarget),
        resolver,
        args);

    if (shouldWrapIntoAppleDebuggableBinary(params.getBuildTarget(), unstrippedBinaryRule)) {
      return createAppleDebuggableBinary(
          targetGraph,
          params,
          resolver,
          args,
          unstrippedBinaryBuildTarget,
          (ProvidesStaticLibraryDeps) unstrippedBinaryRule);
    } else {
      return unstrippedBinaryRule;
    }
  }

  private <A extends Arg> BuildRule createAppleDebuggableBinary(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      BuildTarget unstrippedBinaryBuildTarget,
      ProvidesStaticLibraryDeps unstrippedBinaryRule) throws NoSuchBuildTargetException {
    BuildTarget strippedBinaryBuildTarget = unstrippedBinaryBuildTarget
        .withAppendedFlavors(
            CxxStrip.RULE_FLAVOR,
            StripStyle.FLAVOR_DOMAIN
                .getFlavor(params.getBuildTarget().getFlavors())
                .or(StripStyle.NON_GLOBAL_SYMBOLS.getFlavor()));
    BuildRule strippedBinaryRule = createBinary(
        targetGraph,
        params.copyWithBuildTarget(strippedBinaryBuildTarget),
        resolver,
        args);
    return AppleDescriptions.createAppleDebuggableBinary(
        params.copyWithBuildTarget(unstrippedBinaryBuildTarget),
        resolver,
        strippedBinaryRule,
        unstrippedBinaryRule,
        AppleDebugFormat.FLAVOR_DOMAIN.getRequiredValue(params.getBuildTarget()),
        delegate.getCxxPlatforms(),
        delegate.getDefaultCxxPlatform(),
        platformFlavorsToAppleCxxPlatforms);
  }

  private <A extends Arg> BuildRule createBundleBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    if (!args.infoPlist.isPresent()) {
      throw new HumanReadableException(
          "Cannot create application for apple_binary '%s':\n",
          "No value specified for 'info_plist' attribute.",
          params.getBuildTarget().getUnflavoredBuildTarget());
    }
    AppleDebugFormat flavoredDebugFormat = AppleDebugFormat.FLAVOR_DOMAIN
        .getValue(params.getBuildTarget())
        .or(defaultDebugFormat);
    if (!params.getBuildTarget().getFlavors().contains(flavoredDebugFormat.getFlavor())) {
      return resolver.requireRule(
          params.getBuildTarget().withAppendedFlavor(flavoredDebugFormat.getFlavor()));
    }
    if (!AppleDescriptions.INCLUDE_FRAMEWORKS.getValue(params.getBuildTarget()).isPresent()) {
      CxxPlatform cxxPlatform =
          delegate.getCxxPlatforms().getValue(params.getBuildTarget())
              .or(delegate.getDefaultCxxPlatform());
      ApplePlatform applePlatform =
          platformFlavorsToAppleCxxPlatforms.getValue(cxxPlatform.getFlavor())
              .getAppleSdk()
              .getApplePlatform();
      if (applePlatform.getAppIncludesFrameworks()) {
        return resolver.requireRule(
            params.getBuildTarget().withAppendedFlavor(
                AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR));
      }
      return resolver.requireRule(
          params.getBuildTarget().withAppendedFlavor(
              AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR));
    }
    BuildTarget binaryTarget = params.withoutFlavor(APP_FLAVOR).getBuildTarget();
    return AppleDescriptions.createAppleBundle(
        delegate.getCxxPlatforms(),
        delegate.getDefaultCxxPlatform(),
        platformFlavorsToAppleCxxPlatforms,
        targetGraph,
        params,
        resolver,
        codeSignIdentityStore,
        provisioningProfileStore,
        binaryTarget,
        Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.APP),
        Optional.<String>absent(),
        args.infoPlist.get(),
        args.infoPlistSubstitutions,
        args.deps.get(),
        args.getTests(),
        flavoredDebugFormat);
  }

  private <A extends AppleBinaryDescription.Arg> BuildRule createBinary(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    Optional<FatBinaryInfo> fatBinaryInfo = FatBinaryInfos.create(
        platformFlavorsToAppleCxxPlatforms,
        params.getBuildTarget());
    if (fatBinaryInfo.isPresent()) {
      return createFatBinaryBuildRule(targetGraph, params, resolver, args, fatBinaryInfo.get());
    } else {
      return createThinBinary(targetGraph, params, resolver, args);
    }
  }

  private <A extends Arg> BuildRule createThinBinary(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    Optional<Path> stubBinaryPath = getStubBinaryPath(params, args);
    if (shouldUseStubBinary(params) && stubBinaryPath.isPresent()) {
      try {
        return new WriteFile(
            params,
            new SourcePathResolver(resolver),
            Files.readAllBytes(stubBinaryPath.get()),
            BuildTargets.getGenPath(params.getBuildTarget(), "%s"),
            true);
      } catch (IOException e) {
        throw new HumanReadableException("Could not read stub binary " + stubBinaryPath.get());
      }
    } else {
      Optional<BuildRule> existingRule = resolver.getRuleOptional(params.getBuildTarget());
      if (existingRule.isPresent()) {
        return existingRule.get();
      } else {
        CxxBinaryDescription.Arg delegateArg = delegate.createUnpopulatedConstructorArg();
        AppleDescriptions.populateCxxBinaryDescriptionArg(
            new SourcePathResolver(resolver),
            delegateArg,
            args,
            params.getBuildTarget());
        return delegate.createBuildRule(targetGraph, params, resolver, delegateArg);
      }
    }
  }

  private boolean shouldUseStubBinary(BuildRuleParams params) {
    ImmutableSortedSet<Flavor> flavors = params.getBuildTarget().getFlavors();
    return (flavors.contains(AppleBundleDescription.WATCH_OS_FLAVOR) ||
        flavors.contains(AppleBundleDescription.WATCH_SIMULATOR_FLAVOR) ||
        flavors.contains(LEGACY_WATCH_FLAVOR));
  }


  private <A extends Arg> Optional<Path> getStubBinaryPath(BuildRuleParams params, A args) {
    Optional<Path> stubBinaryPath = Optional.absent();
    Optional<AppleCxxPlatform> appleCxxPlatform = getAppleCxxPlatformFromParams(params);
    if (appleCxxPlatform.isPresent() && (!args.srcs.isPresent() || args.srcs.get().isEmpty())) {
      stubBinaryPath = appleCxxPlatform.get().getStubBinary();
    }
    return stubBinaryPath;
  }

  private Optional<AppleCxxPlatform> getAppleCxxPlatformFromParams(BuildRuleParams params) {
    return platformFlavorsToAppleCxxPlatforms.getValue(params.getBuildTarget());
  }

  /**
   * Create a fat binary rule.
   */
  private <A extends AppleBinaryDescription.Arg> BuildRule createFatBinaryBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      FatBinaryInfo fatBinaryInfo) throws NoSuchBuildTargetException {

    Optional<BuildRule> existingRule = resolver.getRuleOptional(params.getBuildTarget());
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    ImmutableSortedSet.Builder<BuildRule> thinRules = ImmutableSortedSet.naturalOrder();
    for (BuildTarget thinTarget : fatBinaryInfo.getThinTargets()) {
      Optional<BuildRule> existingThinRule = resolver.getRuleOptional(thinTarget);
      if (existingThinRule.isPresent()) {
        thinRules.add(existingThinRule.get());
        continue;
      }
      BuildRule thinRule = createThinBinary(
          targetGraph,
          params.copyWithBuildTarget(thinTarget),
          resolver,
          args);
      resolver.addToIndex(thinRule);
      thinRules.add(thinRule);
    }

    ImmutableSortedSet<SourcePath> inputs = FluentIterable
        .from(resolver.getAllRules(fatBinaryInfo.getThinTargets()))
        .transform(SourcePaths.getToBuildTargetSourcePath())
        .toSortedSet(Ordering.natural());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FatBinary fatBinary = new FatBinary(
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
            Suppliers.ofInstance(thinRules.build())),
        pathResolver,
        fatBinaryInfo.getRepresentativePlatform().getLipo(),
        inputs,
        BuildTargets.getGenPath(params.getBuildTarget(), "%s"));
    resolver.addToIndex(fatBinary);
    return fatBinary;
  }

  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      A args,
      Class<U> metadataClass) throws NoSuchBuildTargetException {
    CxxBinaryDescription.Arg delegateArg = delegate.createUnpopulatedConstructorArg();
    AppleDescriptions.populateCxxBinaryDescriptionArg(
        new SourcePathResolver(resolver),
        delegateArg,
        args,
        buildTarget);
    return delegate.createMetadata(buildTarget, resolver, delegateArg, metadataClass);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AppleNativeTargetDescriptionArg {
    public Optional<SourcePath> infoPlist;
    public Optional<ImmutableMap<String, String>> infoPlistSubstitutions;
  }

}
