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
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
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
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public class AppleBundleDescription implements Description<AppleBundleDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<AppleBundleDescription.Arg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("apple_bundle");

  private static final Flavor WATCH = ImmutableFlavor.of("watch");

  private static final ImmutableSet<Flavor> SUPPORTED_LIBRARY_FLAVORS = ImmutableSet.of(
      CxxDescriptionEnhancer.STATIC_FLAVOR,
      CxxDescriptionEnhancer.SHARED_FLAVOR);

  private final AppleBinaryDescription appleBinaryDescription;
  private final AppleLibraryDescription appleLibraryDescription;
  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;
  private final CodeSignIdentityStore codeSignIdentityStore;
  private final ProvisioningProfileStore provisioningProfileStore;

  public AppleBundleDescription(
      AppleBinaryDescription appleBinaryDescription,
      AppleLibraryDescription appleLibraryDescription,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      Map<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      CxxPlatform defaultCxxPlatform,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore) {
    this.appleBinaryDescription = appleBinaryDescription;
    this.appleLibraryDescription = appleLibraryDescription;
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.platformFlavorsToAppleCxxPlatforms =
        ImmutableMap.copyOf(platformFlavorsToAppleCxxPlatforms);
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.codeSignIdentityStore = codeSignIdentityStore;
    this.provisioningProfileStore = provisioningProfileStore;
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
  public boolean hasFlavors(final ImmutableSet<Flavor> flavors) {
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

  /** Only works with thin binaries. */
  private CxxPlatform getCxxPlatformForBuildTarget(BuildTarget target) {
    CxxPlatform cxxPlatform;
    try {
      cxxPlatform = cxxPlatformFlavorDomain
          .getValue(target.getFlavors())
          .or(defaultCxxPlatform);
    } catch (FlavorDomainException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }

    return cxxPlatform;
  }

  private AppleCxxPlatform getAppleCxxPlatformForBuildTarget(BuildTarget target) {
    Optional<FatBinaryInfo> fatBinaryInfo =
        FatBinaryInfo.create(platformFlavorsToAppleCxxPlatforms, target);
    AppleCxxPlatform appleCxxPlatform;
    if (fatBinaryInfo.isPresent()) {
      appleCxxPlatform = fatBinaryInfo.get().getRepresentativePlatform();
    } else {
      CxxPlatform cxxPlatform = getCxxPlatformForBuildTarget(target);
      appleCxxPlatform =
          platformFlavorsToAppleCxxPlatforms.get(cxxPlatform.getFlavor());
      if (appleCxxPlatform == null) {
        throw new HumanReadableException(
            "%s: Apple bundle requires an Apple platform, found '%s'",
            target,
            cxxPlatform.getFlavor().getName());
      }
    }

    return appleCxxPlatform;
  }


  @Override
  public <A extends Arg> AppleBundle createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    AppleCxxPlatform appleCxxPlatform = getAppleCxxPlatformForBuildTarget(params.getBuildTarget());
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

    Optional<AppleAssetCatalog> assetCatalog =
        AppleDescriptions.createBuildRuleForTransitiveAssetCatalogDependencies(
            targetGraph,
            params,
            sourcePathResolver,
            appleCxxPlatform.getAppleSdk().getApplePlatform(),
            appleCxxPlatform.getActool());

    // TODO(bhamiltoncx): Sort through the changes needed to make project generation work with
    // binary being optional.
    BuildRule flavoredBinaryRule = getFlavoredBinaryRule(targetGraph, params, resolver, args);
    BuildRuleParams bundleParamsWithFlavoredBinaryDep = getBundleParamsWithUpdatedDeps(
        params,
        args.binary,
        ImmutableSet.<BuildRule>builder()
            .add(flavoredBinaryRule)
            .addAll(assetCatalog.asSet())
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

    ImmutableMap<SourcePath, String> extensionBundlePaths = collectFirstLevelAppleDependencyBundles(
        params.getDeps(),
        destinations);

    return new AppleBundle(
        bundleParamsWithFlavoredBinaryDep,
        sourcePathResolver,
        args.extension,
        args.productName,
        args.infoPlist,
        args.infoPlistSubstitutions.get(),
        Optional.of(flavoredBinaryRule),
        destinations,
        bundleDirs,
        bundleFiles,
        dirsContainingResourceDirs,
        extensionBundlePaths,
        Optional.of(bundleVariantFiles),
        appleCxxPlatform.getIbtool(),
        appleCxxPlatform.getDsymutil(),
        appleCxxPlatform.getCxxPlatform().getStrip(),
        appleCxxPlatform.getLldb(),
        assetCatalog,
        args.getTests(),
        appleCxxPlatform.getAppleSdk(),
        codeSignIdentityStore,
        appleCxxPlatform.getCodesignAllocatePath(),
        provisioningProfileStore,
        AppleBundle.DebugInfoFormat.DSYM);
  }

  private <A extends Arg> BuildRule getFlavoredBinaryRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) {
    // Cxx targets must have one Platform Flavor set otherwise nothing gets compiled.
    ImmutableSet<Flavor> flavors = params.getBuildTarget()
        .withoutFlavors(ImmutableSet.of(ReactNativeFlavors.DO_NOT_BUNDLE))
        .getFlavors();
    if (!cxxPlatformFlavorDomain.containsAnyOf(flavors)) {
      flavors = new ImmutableSet.Builder<Flavor>()
          .addAll(flavors)
          .add(defaultCxxPlatform.getFlavor())
          .build();
    }

    final TargetNode<?> binaryTargetNode = Preconditions.checkNotNull(targetGraph.get(args.binary));
    // If the binary target of the AppleBundle is an AppleLibrary then the build flavor
    // must be specified.
    if (binaryTargetNode.getDescription() instanceof AppleLibraryDescription &&
        (Sets.intersection(
            SUPPORTED_LIBRARY_FLAVORS,
            binaryTargetNode.getBuildTarget().getFlavors()).size() != 1)) {
      throw new HumanReadableException(
          "AppleExtension bundle [%s] must have exactly one of these flavors: [%s].",
          binaryTargetNode.getBuildTarget().toString(),
          Joiner.on(", ").join(SUPPORTED_LIBRARY_FLAVORS));
    }

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
        params.getCellRoots(),
        params.getRuleKeyBuilderFactory());

    return CxxDescriptionEnhancer.requireBuildRule(
        targetGraph,
        binaryRuleParams,
        resolver,
        flavors.toArray(new Flavor[0]));
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
                .from(params.getDeclaredDeps().get())
                .filter(notOriginalBinaryRule)
                .append(newDeps)
                .toSortedSet(Ordering.natural())),
        Suppliers.ofInstance(
            FluentIterable
                .from(params.getExtraDeps().get())
                .filter(notOriginalBinaryRule)
                .toSortedSet(Ordering.natural())));
  }

  private ImmutableMap<SourcePath, String> collectFirstLevelAppleDependencyBundles(
      ImmutableSortedSet<BuildRule> deps,
      AppleBundleDestinations destinations) {
    ImmutableMap.Builder<SourcePath, String> extensionBundlePaths = ImmutableMap.builder();
    // We only care about the direct layer of dependencies. ExtensionBundles inside ExtensionBundles
    // do not get pulled in to the top-level Bundle.
    for (BuildRule rule : deps) {
      if (rule instanceof AppleBundle) {
        AppleBundle appleBundle = (AppleBundle) rule;
        if (AppleBundleExtension.APPEX.toFileExtension().equals(appleBundle.getExtension()) ||
            AppleBundleExtension.APP.toFileExtension().equals(appleBundle.getExtension())) {
          Path outputPath = Preconditions.checkNotNull(
              appleBundle.getPathToOutput(),
              "Path cannot be null for AppleBundle [%s].",
              appleBundle);
          SourcePath sourcePath = new BuildTargetSourcePath(
              appleBundle.getBuildTarget(),
              outputPath);

          Path destinationPath;

          String platformName = appleBundle.getPlatformName();

          if ((platformName.equals(ApplePlatform.Name.WATCHOS) ||
              platformName.equals(ApplePlatform.Name.WATCHSIMULATOR)) &&
              appleBundle.getExtension().equals(AppleBundleExtension.APP.toFileExtension())) {
            destinationPath = destinations.getWatchAppPath();
          } else {
            destinationPath = destinations.getPlugInsPath();
          }

          extensionBundlePaths.put(sourcePath, destinationPath.toString());
        }
      }
    }

    return extensionBundlePaths.build();
  }

  /**
   * Propagate the bundle's platform flavors to its dependents.
   */

  @Override
  public ImmutableSet<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Function<Optional<String>, Path> cellRoots,
      AppleBundleDescription.Arg constructorArg) {
    if (!constructorArg.deps.isPresent()) {
      return ImmutableSet.of();
    }

    if (!cxxPlatformFlavorDomain.containsAnyOf(buildTarget.getFlavors())) {
      buildTarget = BuildTarget.builder(buildTarget).addAllFlavors(
          ImmutableSet.of(defaultCxxPlatform.getFlavor())).build();
    }

    Optional<FatBinaryInfo> fatBinaryInfo =
        FatBinaryInfo.create(platformFlavorsToAppleCxxPlatforms, buildTarget);
    CxxPlatform cxxPlatform;
    if (fatBinaryInfo.isPresent()) {
      AppleCxxPlatform appleCxxPlatform = fatBinaryInfo.get().getRepresentativePlatform();
      cxxPlatform = appleCxxPlatform.getCxxPlatform();
    } else {
      cxxPlatform = getCxxPlatformForBuildTarget(buildTarget);
    }

    String platformName = cxxPlatform.getFlavor().getName();
    final Flavor actualWatchFlavor;
    if (ApplePlatform.isSimulator(platformName)) {
      actualWatchFlavor = ImmutableFlavor.builder().name("watchsimulator-i386").build();
    } else if (platformName.startsWith(ApplePlatform.Name.IPHONEOS) ||
        platformName.startsWith(ApplePlatform.Name.WATCHOS)) {
      actualWatchFlavor = ImmutableFlavor.builder().name("watchos-armv7k").build();
    } else {
      actualWatchFlavor = ImmutableFlavor.builder().name(platformName).build();
    }

    FluentIterable<BuildTarget> depsExcludingBinary = FluentIterable.from(constructorArg.deps.get())
        .filter(Predicates.not(Predicates.equalTo(constructorArg.binary)));

    FluentIterable<BuildTarget> targetsWithPlatformFlavors = depsExcludingBinary.filter(
        BuildTargets.containsFlavors(cxxPlatformFlavorDomain));

    FluentIterable<BuildTarget> targetsWithoutPlatformFlavors = depsExcludingBinary.filter(
        Predicates.not(BuildTargets.containsFlavors(cxxPlatformFlavorDomain)));

    FluentIterable<BuildTarget> watchTargets = targetsWithoutPlatformFlavors
        .filter(BuildTargets.containsFlavor(WATCH))
        .transform(
            new Function<BuildTarget, BuildTarget>() {
              @Override
              public BuildTarget apply(BuildTarget input) {
                return BuildTarget.builder(
                    input.withoutFlavors(ImmutableSet.of(WATCH)))
                    .addFlavors(actualWatchFlavor)
                    .build();
              }
            });

    targetsWithoutPlatformFlavors = targetsWithoutPlatformFlavors
        .filter(Predicates.not(BuildTargets.containsFlavor(WATCH)));

    return ImmutableSet.<BuildTarget>builder()
        .addAll(targetsWithPlatformFlavors)
        .addAll(watchTargets)
        .addAll(
            BuildTargets.propagateFlavorDomains(
                buildTarget,
                ImmutableSet.<FlavorDomain<?>>of(cxxPlatformFlavorDomain),
                targetsWithoutPlatformFlavors))
        .build();
  }


  @SuppressFieldNotInitialized
  public static class Arg implements HasAppleBundleFields, HasTests {
    public Either<AppleBundleExtension, String> extension;
    public BuildTarget binary;
    public SourcePath infoPlist;
    public Optional<ImmutableMap<String, String>> infoPlistSubstitutions;
    @Hint(isDep = false) public Optional<ImmutableSortedSet<BuildTarget>> deps;
    @Hint(isDep = false) public Optional<ImmutableSortedSet<BuildTarget>> tests;
    public Optional<String> xcodeProductType;
    public Optional<String> productName;

    @Override
    public Either<AppleBundleExtension, String> getExtension() {
      return extension;
    }

    @Override
    public SourcePath getInfoPlist() {
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
