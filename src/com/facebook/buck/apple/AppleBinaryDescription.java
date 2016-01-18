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

import com.facebook.buck.cxx.CxxBinary;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
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

public class AppleBinaryDescription
    implements Description<AppleBinaryDescription.Arg>, Flavored {

  public static final BuildRuleType TYPE = BuildRuleType.of("apple_binary");
  public static final Flavor APP_FLAVOR = ImmutableFlavor.of("app");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      APP_FLAVOR,
      CxxCompilationDatabase.COMPILATION_DATABASE,
      AppleDebugFormat.DWARF_AND_DSYM_FLAVOR,
      AppleDebugFormat.NO_DEBUG_FLAVOR);

  private static final Predicate<Flavor> IS_SUPPORTED_FLAVOR = new Predicate<Flavor>() {
    @Override
    public boolean apply(Flavor flavor) {
      return SUPPORTED_FLAVORS.contains(flavor);
    }
  };

  private final CxxBinaryDescription delegate;
  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;
  private final CodeSignIdentityStore codeSignIdentityStore;
  private final ProvisioningProfileStore provisioningProfileStore;
  private final AppleDebugFormat defaultDebugFormat;

  public AppleBinaryDescription(
      CxxBinaryDescription delegate,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      CxxPlatform defaultCxxPlatform,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      AppleDebugFormat defaultDebugFormat) {
    this.delegate = delegate;
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.platformFlavorsToAppleCxxPlatforms = platformFlavorsToAppleCxxPlatforms;
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
  public AppleBinaryDescription.Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    if (FluentIterable.from(flavors).allMatch(IS_SUPPORTED_FLAVOR)) {
      return true;
    }
    Collection<ImmutableSortedSet<Flavor>> thinFlavorSets =
        FatBinaryInfo.generateThinFlavors(
            platformFlavorsToAppleCxxPlatforms.keySet(),
            ImmutableSortedSet.copyOf(flavors));
    if (thinFlavorSets.size() > 1) {
      return Iterables.all(
          thinFlavorSets,
          new Predicate<ImmutableSortedSet<Flavor>>() {
            @Override
            public boolean apply(ImmutableSortedSet<Flavor> input) {
              Set<Flavor> flavors = Sets.newHashSet(input);
              flavors.removeAll(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors());
              return delegate.hasFlavors(ImmutableSet.copyOf(flavors));
            }
          });
    } else {
      return delegate.hasFlavors(flavors);
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

  private <A extends Arg> BuildRule createBinaryBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    Optional<AppleDebugFormat> flavoredDebugInfoFormat = AppleDebugFormat.FLAVOR_DOMAIN
        .getValue(params.getBuildTarget());
    BuildTarget binaryBuildTarget = params.getBuildTarget().withoutFlavors(
        AppleDebugFormat.FLAVOR_DOMAIN.getFlavors());

    BuildRule binaryBuildRule = createBinary(
        targetGraph,
        params.copyWithBuildTarget(binaryBuildTarget),
        resolver,
        args);
    if (flavoredDebugInfoFormat.isPresent() &&
        (binaryBuildRule instanceof CxxBinary || binaryBuildRule instanceof FatBinary)) {
      return AppleDescriptions.createAppleDebuggableBinary(
          cxxPlatformFlavorDomain,
          defaultCxxPlatform,
          platformFlavorsToAppleCxxPlatforms,
          params.copyWithBuildTarget(binaryBuildTarget),
          targetGraph,
          resolver,
          binaryBuildRule,
          args.linkStyle.or(Linker.LinkableDepType.STATIC),
          flavoredDebugInfoFormat.get());
    }
    return binaryBuildRule;
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
          BuildTarget.builder(params.getBuildTarget())
              .addFlavors(flavoredDebugFormat.getFlavor())
              .build());
    }
    if (!AppleDescriptions.INCLUDE_FRAMEWORKS.getValue(params.getBuildTarget()).isPresent()) {
      CxxPlatform cxxPlatform =
          cxxPlatformFlavorDomain.getValue(params.getBuildTarget()).or(defaultCxxPlatform);
      ApplePlatform applePlatform =
          Preconditions
              .checkNotNull(platformFlavorsToAppleCxxPlatforms.get(cxxPlatform.getFlavor()))
              .getAppleSdk()
              .getApplePlatform();
      if (applePlatform.getAppIncludesFrameworks()) {
        return resolver.requireRule(
            BuildTarget.builder(params.getBuildTarget())
                .addFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR)
                .build());
      }
      return resolver.requireRule(
          BuildTarget.builder(params.getBuildTarget())
              .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
              .build());
    }
    BuildTarget binaryTarget = params.withoutFlavor(APP_FLAVOR).getBuildTarget();
    return AppleDescriptions.createAppleBundle(
        cxxPlatformFlavorDomain,
        defaultCxxPlatform,
        platformFlavorsToAppleCxxPlatforms,
        targetGraph,
        params,
        resolver,
        codeSignIdentityStore,
        provisioningProfileStore,
        binaryTarget,
        args.linkStyle,
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
    Optional<FatBinaryInfo> fatBinaryInfo = FatBinaryInfo.create(
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
    if (stubBinaryPath.isPresent()) {
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
      CxxBinaryDescription.Arg delegateArg = delegate.createUnpopulatedConstructorArg();
      AppleDescriptions.populateCxxBinaryDescriptionArg(
          new SourcePathResolver(resolver),
          delegateArg,
          args,
          params.getBuildTarget());
      return delegate.createBuildRule(targetGraph, params, resolver, delegateArg);
    }
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
    Optional<CxxPlatform> cxxPlatform = delegate.getCxxPlatforms()
        .getValue(params.getBuildTarget());

    AppleCxxPlatform appleCxxPlatform = null;
    if (cxxPlatform.isPresent()) {
      appleCxxPlatform = platformFlavorsToAppleCxxPlatforms.get(cxxPlatform.get().getFlavor());
    }

    return Optional.fromNullable(appleCxxPlatform);
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
    return new FatBinary(
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
            Suppliers.ofInstance(thinRules.build())),
        pathResolver,
        fatBinaryInfo.getRepresentativePlatform().getLipo(),
        inputs,
        BuildTargets.getGenPath(params.getBuildTarget(), "%s"));
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AppleNativeTargetDescriptionArg {
    public Optional<SourcePath> infoPlist;
    public Optional<ImmutableMap<String, String>> infoPlistSubstitutions;
  }

}
