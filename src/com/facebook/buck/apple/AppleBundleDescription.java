/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.js.ReactNativeFlavors;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;

import java.util.Map;
import java.util.Set;

public class AppleBundleDescription implements Description<AppleBundleDescription.Arg>, Flavored {
  public static final BuildRuleType TYPE = BuildRuleType.of("apple_bundle");

  private final AppleBinaryDescription appleBinaryDescription;
  private final AppleLibraryDescription appleLibraryDescription;
  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;
  private final ImmutableSet<CodeSignIdentity> allValidCodeSignIdentities;

  public AppleBundleDescription(
      AppleBinaryDescription appleBinaryDescription,
      AppleLibraryDescription appleLibraryDescription,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      Map<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      CxxPlatform defaultCxxPlatform,
      ImmutableSet<CodeSignIdentity> allValidCodeSignIdentities) {
    this.appleBinaryDescription = appleBinaryDescription;
    this.appleLibraryDescription = appleLibraryDescription;
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.platformFlavorsToAppleCxxPlatforms =
        ImmutableMap.copyOf(platformFlavorsToAppleCxxPlatforms);
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.allValidCodeSignIdentities = allValidCodeSignIdentities;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    if (appleLibraryDescription.hasFlavors(flavors)) {
      return true;
    }
    ImmutableSet.Builder<Flavor> flavorBuilder = ImmutableSet.builder();
    for (Flavor flavor : flavors) {
      if (flavor.equals(ReactNativeFlavors.DO_NOT_BUNDLE)) {
        continue;
      }
      flavorBuilder.add(flavor);
    }
    return appleBinaryDescription.hasFlavors(flavorBuilder.build());
  }

  @Override
  public <A extends Arg> AppleBundle createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

    CxxPlatform cxxPlatform;
    try {
      cxxPlatform = cxxPlatformFlavorDomain
          .getValue(params.getBuildTarget().getFlavors())
          .or(defaultCxxPlatform);
    } catch (FlavorDomainException e) {
      throw new HumanReadableException(e, "%s: %s", params.getBuildTarget(), e.getMessage());
    }
    AppleCxxPlatform appleCxxPlatform =
        platformFlavorsToAppleCxxPlatforms.get(cxxPlatform.getFlavor());
    if (appleCxxPlatform == null) {
      throw new HumanReadableException(
          "%s: Apple bundle requires an Apple platform, found '%s'",
          params.getBuildTarget(),
          cxxPlatform.getFlavor().getName());
    }

    AppleBundleDestinations destinations =
        AppleBundleDestinations.platformDestinations(
            appleCxxPlatform.getAppleSdk().getApplePlatform());

    ImmutableSet.Builder<SourcePath> bundleDirsBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<SourcePath> dirsContainingResourceDirsBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<SourcePath> bundleFilesBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<SourcePath> bundleVariantFilesBuilder = ImmutableSet.builder();
    AppleResources.collectResourceDirsAndFiles(
        targetGraph,
        Preconditions.checkNotNull(targetGraph.get(params.getBuildTarget())),
        bundleDirsBuilder,
        dirsContainingResourceDirsBuilder,
        bundleFilesBuilder,
        bundleVariantFilesBuilder);
    ImmutableSet<SourcePath> bundleDirs = bundleDirsBuilder.build();
    ImmutableSet<SourcePath> dirsContainingResourceDirs = dirsContainingResourceDirsBuilder.build();
    ImmutableSet<SourcePath> bundleFiles = bundleFilesBuilder.build();
    ImmutableSet<SourcePath> bundleVariantFiles = bundleVariantFilesBuilder.build();

    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);

    CollectedAssetCatalogs collectedAssetCatalogs =
        AppleDescriptions.createBuildRulesForTransitiveAssetCatalogDependencies(
            targetGraph,
            params,
            sourcePathResolver,
            appleCxxPlatform.getAppleSdk().getApplePlatform(),
            appleCxxPlatform.getActool());

    Optional<AppleAssetCatalog> mergedAssetCatalog = collectedAssetCatalogs.getMergedAssetCatalog();
    ImmutableSet<AppleAssetCatalog> bundledAssetCatalogs =
        collectedAssetCatalogs.getBundledAssetCatalogs();

    // TODO(user): Sort through the changes needed to make project generation work with
    // binary being optional.
    BuildRule flavoredBinaryRule = getFlavoredBinaryRule(targetGraph, params, resolver, args);
    BuildRuleParams bundleParamsWithFlavoredBinaryDep = getBundleParamsWithUpdatedDeps(
        params,
        args.binary,
        ImmutableSet.<BuildRule>builder()
            .add(flavoredBinaryRule)
            .addAll(mergedAssetCatalog.asSet())
            .addAll(bundledAssetCatalogs)
            .addAll(
                BuildRules.toBuildRulesFor(
                    params.getBuildTarget(),
                    resolver,
                    SourcePaths.filterBuildTargetSourcePaths(
                        Iterables.concat(
                            bundleFiles,
                            bundleDirs,
                            dirsContainingResourceDirs,
                            bundleVariantFiles))))
            .build());

    return new AppleBundle(
        bundleParamsWithFlavoredBinaryDep,
        sourcePathResolver,
        args.extension,
        args.infoPlist,
        args.infoPlistSubstitutions.get(),
        Optional.of(flavoredBinaryRule),
        destinations,
        bundleDirs,
        bundleFiles,
        dirsContainingResourceDirs,
        Optional.of(bundleVariantFiles),
        appleCxxPlatform.getIbtool(),
        appleCxxPlatform.getDsymutil(),
        appleCxxPlatform.getCxxPlatform().getStrip(),
        bundledAssetCatalogs,
        mergedAssetCatalog,
        args.getTests(),
        appleCxxPlatform.getAppleSdk(),
        allValidCodeSignIdentities,
        args.provisioningProfileSearchPath);
  }

  private static <A extends Arg> BuildRule getFlavoredBinaryRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) {
    final TargetNode<?> binaryTargetNode = Preconditions.checkNotNull(targetGraph.get(args.binary));
    BuildRuleParams binaryRuleParams = new BuildRuleParams(
        args.binary,
        Suppliers.ofInstance(
            BuildRules.toBuildRulesFor(
                params.getBuildTarget(),
                resolver,
                binaryTargetNode.getDeclaredDeps())),
        Suppliers.ofInstance(
            BuildRules.toBuildRulesFor(
                params.getBuildTarget(),
                resolver,
                binaryTargetNode.getExtraDeps())),
        params.getProjectFilesystem(),
        params.getRuleKeyBuilderFactory());
    return CxxDescriptionEnhancer.requireBuildRule(
        targetGraph,
        binaryRuleParams,
        resolver,
        params
            .getBuildTarget()
            .withoutFlavors(ImmutableSet.of(ReactNativeFlavors.DO_NOT_BUNDLE))
            .getFlavors()
            .toArray(new Flavor[0]));
  }

  private static BuildRuleParams getBundleParamsWithUpdatedDeps(
      final BuildRuleParams params,
      final BuildTarget originalBinaryTarget,
      final Set<BuildRule> newDeps) {
    // Remove the unflavored binary rule and add the flavored one instead.
    final Predicate<BuildRule> notOriginalBinaryRule = Predicates.not(
        BuildRules.isBuildRuleWithTarget(originalBinaryTarget));
    return params.copyWithDeps(
        Suppliers.ofInstance(
            FluentIterable
                .from(params.getDeclaredDeps())
                .filter(notOriginalBinaryRule)
                .append(newDeps)
                .toSortedSet(Ordering.natural())),
        Suppliers.ofInstance(
            FluentIterable
                .from(params.getExtraDeps())
                .filter(notOriginalBinaryRule)
                .toSortedSet(Ordering.natural())));
  }

  @SuppressFieldNotInitialized
  public static class Arg implements HasAppleBundleFields, HasTests {
    public Either<AppleBundleExtension, String> extension;
    public BuildTarget binary;
    public Optional<SourcePath> infoPlist;
    public Optional<ImmutableMap<String, String>> infoPlistSubstitutions;
    public Optional<ImmutableMap<String, SourcePath>> headers;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<SourcePath> provisioningProfileSearchPath;
    @Hint(isDep = false) public Optional<ImmutableSortedSet<BuildTarget>> tests;
    public Optional<String> xcodeProductType;

    @Override
    public Either<AppleBundleExtension, String> getExtension() {
      return extension;
    }

    @Override
    public Optional<SourcePath> getInfoPlist() {
      return infoPlist;
    }

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests.get();
    }

    @Override
    public Optional<String> getXcodeProductType() {
      return xcodeProductType;
    }
  }
}
