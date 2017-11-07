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

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasDefaultPlatform;
import com.facebook.buck.rules.HasTests;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.Version;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public class AppleBundleDescription
    implements Description<AppleBundleDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<AppleBundleDescription.AbstractAppleBundleDescriptionArg>,
        MetadataProvidingDescription<AppleBundleDescriptionArg> {

  public static final ImmutableSet<Flavor> SUPPORTED_LIBRARY_FLAVORS =
      ImmutableSet.of(CxxDescriptionEnhancer.STATIC_FLAVOR, CxxDescriptionEnhancer.SHARED_FLAVOR);

  public static final Flavor WATCH_OS_FLAVOR = InternalFlavor.of("watchos-armv7k");
  public static final Flavor WATCH_SIMULATOR_FLAVOR = InternalFlavor.of("watchsimulator-i386");

  private static final Flavor WATCH = InternalFlavor.of("watch");

  private final AppleBinaryDescription appleBinaryDescription;
  private final AppleLibraryDescription appleLibraryDescription;
  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final FlavorDomain<AppleCxxPlatform> appleCxxPlatformsFlavorDomain;
  private final Flavor defaultCxxFlavor;
  private final CodeSignIdentityStore codeSignIdentityStore;
  private final ProvisioningProfileStore provisioningProfileStore;
  private final AppleConfig appleConfig;

  public AppleBundleDescription(
      AppleBinaryDescription appleBinaryDescription,
      AppleLibraryDescription appleLibraryDescription,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformsFlavorDomain,
      Flavor defaultCxxFlavor,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      AppleConfig appleConfig) {
    this.appleBinaryDescription = appleBinaryDescription;
    this.appleLibraryDescription = appleLibraryDescription;
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.appleCxxPlatformsFlavorDomain = appleCxxPlatformsFlavorDomain;
    this.defaultCxxFlavor = defaultCxxFlavor;
    this.codeSignIdentityStore = codeSignIdentityStore;
    this.provisioningProfileStore = provisioningProfileStore;
    this.appleConfig = appleConfig;
  }

  @Override
  public Class<AppleBundleDescriptionArg> getConstructorArgType() {
    return AppleBundleDescriptionArg.class;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    ImmutableSet.Builder<FlavorDomain<?>> builder = ImmutableSet.builder();

    ImmutableSet<FlavorDomain<?>> localDomains =
        ImmutableSet.of(AppleDebugFormat.FLAVOR_DOMAIN, AppleDescriptions.INCLUDE_FRAMEWORKS);

    builder.addAll(localDomains);
    appleLibraryDescription.flavorDomains().ifPresent(domains -> builder.addAll(domains));
    appleBinaryDescription.flavorDomains().ifPresent(domains -> builder.addAll(domains));

    return Optional.of(builder.build());
  }

  @Override
  public boolean hasFlavors(final ImmutableSet<Flavor> flavors) {
    if (appleLibraryDescription.hasFlavors(flavors)) {
      return true;
    }
    ImmutableSet.Builder<Flavor> flavorBuilder = ImmutableSet.builder();
    for (Flavor flavor : flavors) {
      if (AppleDebugFormat.FLAVOR_DOMAIN.getFlavors().contains(flavor)) {
        continue;
      }
      if (AppleDescriptions.INCLUDE_FRAMEWORKS.getFlavors().contains(flavor)) {
        continue;
      }
      flavorBuilder.add(flavor);
    }
    return appleBinaryDescription.hasFlavors(flavorBuilder.build());
  }

  @Override
  public AppleBundle createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleBundleDescriptionArg args) {
    AppleDebugFormat flavoredDebugFormat =
        AppleDebugFormat.FLAVOR_DOMAIN
            .getValue(buildTarget)
            .orElse(appleConfig.getDefaultDebugInfoFormatForBinaries());
    if (!buildTarget.getFlavors().contains(flavoredDebugFormat.getFlavor())) {
      return (AppleBundle)
          resolver.requireRule(buildTarget.withAppendedFlavors(flavoredDebugFormat.getFlavor()));
    }
    if (!AppleDescriptions.INCLUDE_FRAMEWORKS.getValue(buildTarget).isPresent()) {
      return (AppleBundle)
          resolver.requireRule(
              buildTarget.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR));
    }
    return AppleDescriptions.createAppleBundle(
        cxxPlatformFlavorDomain,
        defaultCxxFlavor,
        appleCxxPlatformsFlavorDomain,
        targetGraph,
        buildTarget,
        projectFilesystem,
        params,
        resolver,
        codeSignIdentityStore,
        provisioningProfileStore,
        args.getBinary(),
        args.getExtension(),
        args.getProductName(),
        args.getInfoPlist(),
        args.getInfoPlistSubstitutions(),
        args.getDeps(),
        args.getTests(),
        flavoredDebugFormat,
        appleConfig.useDryRunCodeSigning(),
        appleConfig.cacheBundlesAndPackages(),
        appleConfig.assetCatalogValidation(),
        args.getCodesignFlags(),
        args.getCodesignIdentity(),
        args.getIbtoolModuleFlag());
  }

  /**
   * Propagate the bundle's platform, debug symbol and strip flavors to its dependents which are
   * other bundles (e.g. extensions)
   */
  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractAppleBundleDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (!cxxPlatformFlavorDomain.containsAnyOf(buildTarget.getFlavors())) {
      buildTarget = buildTarget.withAppendedFlavors(defaultCxxFlavor);
    }

    Optional<MultiarchFileInfo> fatBinaryInfo =
        MultiarchFileInfos.create(appleCxxPlatformsFlavorDomain, buildTarget);
    CxxPlatform cxxPlatform;
    if (fatBinaryInfo.isPresent()) {
      AppleCxxPlatform appleCxxPlatform = fatBinaryInfo.get().getRepresentativePlatform();
      cxxPlatform = appleCxxPlatform.getCxxPlatform();
    } else {
      cxxPlatform =
          ApplePlatforms.getCxxPlatformForBuildTarget(
              cxxPlatformFlavorDomain, defaultCxxFlavor, buildTarget);
    }

    String platformName = cxxPlatform.getFlavor().getName();
    final Flavor actualWatchFlavor;
    if (ApplePlatform.isSimulator(platformName)) {
      actualWatchFlavor = WATCH_SIMULATOR_FLAVOR;
    } else if (platformName.startsWith(ApplePlatform.IPHONEOS.getName())
        || platformName.startsWith(ApplePlatform.WATCHOS.getName())) {
      actualWatchFlavor = WATCH_OS_FLAVOR;
    } else {
      actualWatchFlavor = InternalFlavor.of(platformName);
    }

    FluentIterable<BuildTarget> depsExcludingBinary =
        FluentIterable.from(constructorArg.getDeps())
            .filter(Predicates.not(constructorArg.getBinary()::equals));

    // Propagate platform flavors.  Need special handling for watch to map the pseudo-flavor
    // watch to the actual watch platform (simulator or device) so can't use
    // BuildTargets.propagateFlavorsInDomainIfNotPresent()
    {
      FluentIterable<BuildTarget> targetsWithPlatformFlavors =
          depsExcludingBinary.filter(BuildTargets.containsFlavors(cxxPlatformFlavorDomain)::test);

      FluentIterable<BuildTarget> targetsWithoutPlatformFlavors =
          depsExcludingBinary.filter(
              BuildTargets.containsFlavors(cxxPlatformFlavorDomain).negate()::test);

      FluentIterable<BuildTarget> watchTargets =
          targetsWithoutPlatformFlavors
              .filter(BuildTargets.containsFlavor(WATCH)::test)
              .transform(
                  input -> input.withoutFlavors(WATCH).withAppendedFlavors(actualWatchFlavor));

      targetsWithoutPlatformFlavors =
          targetsWithoutPlatformFlavors.filter(BuildTargets.containsFlavor(WATCH).negate()::test);

      // Gather all the deps now that we've added platform flavors to everything.
      depsExcludingBinary =
          targetsWithPlatformFlavors
              .append(watchTargets)
              .append(
                  BuildTargets.propagateFlavorDomains(
                      buildTarget,
                      ImmutableSet.of(cxxPlatformFlavorDomain),
                      targetsWithoutPlatformFlavors));
    }

    // Propagate some flavors
    depsExcludingBinary =
        BuildTargets.propagateFlavorsInDomainIfNotPresent(
            StripStyle.FLAVOR_DOMAIN, buildTarget, depsExcludingBinary);
    depsExcludingBinary =
        BuildTargets.propagateFlavorsInDomainIfNotPresent(
            AppleDebugFormat.FLAVOR_DOMAIN, buildTarget, depsExcludingBinary);
    depsExcludingBinary =
        BuildTargets.propagateFlavorsInDomainIfNotPresent(
            LinkerMapMode.FLAVOR_DOMAIN, buildTarget, depsExcludingBinary);

    if (fatBinaryInfo.isPresent()) {
      depsExcludingBinary =
          depsExcludingBinary.append(
              fatBinaryInfo
                  .get()
                  .getRepresentativePlatform()
                  .getCodesignProvider()
                  .getParseTimeDeps());
    } else {
      depsExcludingBinary =
          depsExcludingBinary.append(
              appleCxxPlatformsFlavorDomain
                  .getValue(buildTarget)
                  .map(platform -> platform.getCodesignProvider().getParseTimeDeps())
                  .orElse(ImmutableSet.of()));
    }

    extraDepsBuilder.addAll(depsExcludingBinary);
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleBundleDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    if (metadataClass.isAssignableFrom(FrameworkDependencies.class)) {
      // Bundles should be opaque to framework dependencies.
      return Optional.empty();
    }
    return resolver.requireMetadata(args.getBinary(), metadataClass);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAppleBundleDescriptionArg
      extends CommonDescriptionArg,
          HasAppleBundleFields,
          HasAppleCodesignFields,
          HasDefaultPlatform,
          HasDeclaredDeps,
          HasTests {
    BuildTarget getBinary();

    // ibtool take --module <PRODUCT_MODULE_NAME> arguments to override
    // customModule field set on its elements. (only when customModuleProvider="target")
    // Module (so far, it seems to only represent swift module) contains the
    // implementation of the declared element in nib file.
    Optional<Boolean> getIbtoolModuleFlag();

    @Override
    @Hint(isDep = false)
    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getDeps();
  }
}
