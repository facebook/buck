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

  public AppleBundleDescription(
      AppleBinaryDescription appleBinaryDescription,
      AppleLibraryDescription appleLibraryDescription,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      Map<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      CxxPlatform defaultCxxPlatform) {
    this.appleBinaryDescription = appleBinaryDescription;
    this.appleLibraryDescription = appleLibraryDescription;
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.platformFlavorsToAppleCxxPlatforms =
        ImmutableMap.copyOf(platformFlavorsToAppleCxxPlatforms);
    this.defaultCxxPlatform = defaultCxxPlatform;
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
    return appleLibraryDescription.hasFlavors(flavors) ||
        appleBinaryDescription.hasFlavors(flavors);
  }

  @Override
  public <A extends Arg> AppleBundle createBuildRule(
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
    AppleResources.collectResourceDirsAndFiles(
        params.getTargetGraph(),
        Preconditions.checkNotNull(params.getTargetGraph().get(params.getBuildTarget())),
        bundleDirsBuilder,
        dirsContainingResourceDirsBuilder,
        bundleFilesBuilder);
    ImmutableSet<SourcePath> bundleDirs = bundleDirsBuilder.build();
    ImmutableSet<SourcePath> dirsContainingResourceDirs = dirsContainingResourceDirsBuilder.build();
    ImmutableSet<SourcePath> bundleFiles = bundleFilesBuilder.build();

    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);

    CollectedAssetCatalogs collectedAssetCatalogs =
        AppleDescriptions.createBuildRulesForTransitiveAssetCatalogDependencies(
            params,
            sourcePathResolver,
            appleCxxPlatform.getAppleSdk().getApplePlatform(),
            appleCxxPlatform.getActool());

    Optional<AppleAssetCatalog> mergedAssetCatalog = collectedAssetCatalogs.getMergedAssetCatalog();
    ImmutableSet<AppleAssetCatalog> bundledAssetCatalogs =
        collectedAssetCatalogs.getBundledAssetCatalogs();

    // TODO(user): Sort through the changes needed to make project generation work with
    // binary being optional.
    BuildRule flavoredBinaryRule = getFlavoredBinaryRule(params, resolver, args);
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
                            dirsContainingResourceDirs))))
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
        appleCxxPlatform.getIbtool(),
        appleCxxPlatform.getDsymutil(),
        bundledAssetCatalogs,
        mergedAssetCatalog,
        args.getTests(),
        appleCxxPlatform.getAppleSdk().getApplePlatform().getName(),
        appleCxxPlatform.getAppleSdk().getName());
  }

  private static <A extends Arg> BuildRule getFlavoredBinaryRule(
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args) {
    final TargetNode<?> binaryTargetNode = Preconditions.checkNotNull(
        params.getTargetGraph().get(args.binary));
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
        params.getRuleKeyBuilderFactory(),
        params.getTargetGraph());
    return CxxDescriptionEnhancer.requireBuildRule(
        binaryRuleParams,
        resolver,
        params.getBuildTarget().getFlavors().toArray(new Flavor[0]));
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
