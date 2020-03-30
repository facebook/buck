/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.apple.toolchain.UnresolvedAppleCxxPlatform;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasContacts;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasDefaultPlatform;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.description.metadata.MetadataProvidingDescription;
import com.facebook.buck.core.linkgroup.CxxLinkGroupMapping;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.cxx.LinkableListFilterFactory;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.versions.Version;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.function.Predicate;
import org.immutables.value.Value;

public class AppleBundleDescription
    implements DescriptionWithTargetGraph<AppleBundleDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<AppleBundleDescription.AbstractAppleBundleDescriptionArg>,
        MetadataProvidingDescription<AppleBundleDescriptionArg> {

  public static final ImmutableSet<Flavor> SUPPORTED_LIBRARY_FLAVORS =
      ImmutableSet.of(CxxDescriptionEnhancer.STATIC_FLAVOR, CxxDescriptionEnhancer.SHARED_FLAVOR);

  public static final Flavor WATCH_OS_FLAVOR = InternalFlavor.of("watchos-armv7k");
  public static final Flavor WATCH_OS_64_32_FLAVOR = InternalFlavor.of("watchos-arm64_32");
  public static final Flavor WATCH_SIMULATOR_FLAVOR = InternalFlavor.of("watchsimulator-i386");

  private static final Flavor WATCH = InternalFlavor.of("watch");

  private final ToolchainProvider toolchainProvider;
  private final XCodeDescriptions xcodeDescriptions;
  private final AppleBinaryDescription appleBinaryDescription;
  private final AppleLibraryDescription appleLibraryDescription;
  private final AppleConfig appleConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final SwiftBuckConfig swiftBuckConfig;

  public AppleBundleDescription(
      ToolchainProvider toolchainProvider,
      XCodeDescriptions xcodeDescriptions,
      AppleBinaryDescription appleBinaryDescription,
      AppleLibraryDescription appleLibraryDescription,
      AppleConfig appleConfig,
      CxxBuckConfig cxxBuckConfig,
      SwiftBuckConfig swiftBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.xcodeDescriptions = xcodeDescriptions;
    this.appleBinaryDescription = appleBinaryDescription;
    this.appleLibraryDescription = appleLibraryDescription;
    this.appleConfig = appleConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.swiftBuckConfig = swiftBuckConfig;
  }

  @Override
  public Class<AppleBundleDescriptionArg> getConstructorArgType() {
    return AppleBundleDescriptionArg.class;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    ImmutableSet.Builder<FlavorDomain<?>> builder = ImmutableSet.builder();

    ImmutableSet<FlavorDomain<?>> localDomains =
        ImmutableSet.of(AppleDebugFormat.FLAVOR_DOMAIN, AppleDescriptions.INCLUDE_FRAMEWORKS);

    builder.addAll(localDomains);
    appleLibraryDescription
        .flavorDomains(toolchainTargetConfiguration)
        .ifPresent(domains -> builder.addAll(domains));
    appleBinaryDescription
        .flavorDomains(toolchainTargetConfiguration)
        .ifPresent(domains -> builder.addAll(domains));

    return Optional.of(builder.build());
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    if (appleLibraryDescription.hasFlavors(flavors, toolchainTargetConfiguration)) {
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
    return appleBinaryDescription.hasFlavors(flavorBuilder.build(), toolchainTargetConfiguration);
  }

  @Override
  public AppleBundle createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AppleBundleDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    AppleDebugFormat flavoredDebugFormat =
        AppleDebugFormat.FLAVOR_DOMAIN
            .getValue(buildTarget)
            .orElse(appleConfig.getDefaultDebugInfoFormatForBinaries());
    if (!buildTarget.getFlavors().contains(flavoredDebugFormat.getFlavor())) {
      return (AppleBundle)
          graphBuilder.requireRule(
              buildTarget.withAppendedFlavors(flavoredDebugFormat.getFlavor()));
    }
    if (!AppleDescriptions.INCLUDE_FRAMEWORKS.getValue(buildTarget).isPresent()) {
      return (AppleBundle)
          graphBuilder.requireRule(
              buildTarget.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR));
    }
    CxxPlatformsProvider cxxPlatformsProvider =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration());

    Predicate<BuildTarget> resourceFilter =
        LinkableListFilterFactory.resourcePredicateFrom(
            cxxBuckConfig,
            args.getResourceGroup(),
            args.getResourceGroupMap(),
            context.getTargetGraph());

    return AppleDescriptions.createAppleBundle(
        xcodeDescriptions,
        cxxPlatformsProvider,
        getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration()),
        context.getTargetGraph(),
        buildTarget,
        context.getProjectFilesystem(),
        params,
        graphBuilder,
        toolchainProvider.getByName(
            CodeSignIdentityStore.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            CodeSignIdentityStore.class),
        toolchainProvider.getByName(
            ProvisioningProfileStore.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            ProvisioningProfileStore.class),
        args.getBinary(),
        args.getPlatformBinary(),
        args.getDefaultPlatform(),
        args.getExtension(),
        args.getProductName(),
        args.getInfoPlist(),
        args.getInfoPlistSubstitutions(),
        args.getDeps(),
        args.getTests(),
        flavoredDebugFormat,
        appleConfig.useDryRunCodeSigning(),
        appleConfig.cacheBundlesAndPackages(),
        appleConfig.shouldVerifyBundleResources(),
        appleConfig.assetCatalogValidation(),
        args.getAssetCatalogsCompilationOptions(),
        args.getCodesignFlags(),
        args.getCodesignIdentity(),
        args.getIbtoolModuleFlag(),
        args.getIbtoolFlags(),
        args.getXcodeProductType(),
        appleConfig.getCodesignTimeout(),
        swiftBuckConfig.getCopyStdlibToFrameworks(),
        swiftBuckConfig.getUseLipoThin(),
        cxxBuckConfig.shouldCacheStrip(),
        appleConfig.useEntitlementsWhenAdhocCodeSigning(),
        resourceFilter);
  }

  /**
   * Propagate the bundle's platform, debug symbol and strip flavors to its dependents which are
   * other bundles (e.g. extensions)
   */
  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractAppleBundleDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    CxxPlatformsProvider cxxPlatformsProvider =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration());
    if (!cxxPlatformsProvider.getUnresolvedCxxPlatforms().containsAnyOf(buildTarget.getFlavors())) {
      Flavor platformFlavor =
          constructorArg
              .getDefaultPlatform()
              .orElse(cxxPlatformsProvider.getDefaultUnresolvedCxxPlatform().getFlavor());
      buildTarget = buildTarget.withAppendedFlavors(platformFlavor);
    }

    FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformsFlavorDomain =
        getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration());
    Optional<MultiarchFileInfo> fatBinaryInfo =
        MultiarchFileInfos.create(appleCxxPlatformsFlavorDomain, buildTarget);
    UnresolvedCxxPlatform cxxPlatform;
    if (fatBinaryInfo.isPresent()) {
      UnresolvedAppleCxxPlatform appleCxxPlatform =
          appleCxxPlatformsFlavorDomain.getValue(
              fatBinaryInfo.get().getRepresentativePlatformFlavor());
      cxxPlatform = appleCxxPlatform.getUnresolvedCxxPlatform();
    } else {
      cxxPlatform =
          ApplePlatforms.getCxxPlatformForBuildTarget(
              cxxPlatformsProvider, buildTarget, constructorArg.getDefaultPlatform());
    }

    // TODO(cjhopman): Why doesn't this add parse time deps from the cxxPlatform? Does it just
    // happen to work because something else in the graph probably does that?

    String platformName = cxxPlatform.getFlavor().getName();
    Flavor[] actualWatchFlavors;
    if (ApplePlatform.isSimulator(platformName)) {
      actualWatchFlavors = new Flavor[] {WATCH_SIMULATOR_FLAVOR};
    } else if (platformName.startsWith(ApplePlatform.IPHONEOS.getName())
        || platformName.startsWith(ApplePlatform.WATCHOS.getName())) {
      actualWatchFlavors = new Flavor[] {WATCH_OS_FLAVOR, WATCH_OS_64_32_FLAVOR};
    } else {
      actualWatchFlavors = new Flavor[] {InternalFlavor.of(platformName)};
    }

    ImmutableSortedSet<BuildTarget> binaryTargets = constructorArg.getBinaryTargets();
    FluentIterable<BuildTarget> depsExcludingBinary =
        FluentIterable.from(constructorArg.getDeps()).filter(dep -> !binaryTargets.contains(dep));

    // Propagate platform flavors.  Need special handling for watch to map the pseudo-flavor
    // watch to the actual watch platform (simulator or device) so can't use
    // Flavors.propagateFlavorsInDomainIfNotPresent()
    {
      FluentIterable<BuildTarget> targetsWithPlatformFlavors =
          depsExcludingBinary.filter(
              Flavors.containsFlavors(cxxPlatformsProvider.getUnresolvedCxxPlatforms())::test);

      FluentIterable<BuildTarget> targetsWithoutPlatformFlavors =
          depsExcludingBinary.filter(
              Flavors.containsFlavors(cxxPlatformsProvider.getUnresolvedCxxPlatforms()).negate()
                  ::test);

      FluentIterable<BuildTarget> watchTargets =
          targetsWithoutPlatformFlavors
              .filter(Flavors.containsFlavor(WATCH)::test)
              .transform(
                  input -> input.withoutFlavors(WATCH).withAppendedFlavors(actualWatchFlavors));

      targetsWithoutPlatformFlavors =
          targetsWithoutPlatformFlavors.filter(Flavors.containsFlavor(WATCH).negate()::test);

      // Gather all the deps now that we've added platform flavors to everything.
      depsExcludingBinary =
          targetsWithPlatformFlavors
              .append(watchTargets)
              .append(
                  Flavors.propagateFlavorDomains(
                      buildTarget,
                      ImmutableSet.of(cxxPlatformsProvider.getUnresolvedCxxPlatforms()),
                      targetsWithoutPlatformFlavors));
    }

    // Propagate some flavors
    depsExcludingBinary =
        Flavors.propagateFlavorsInDomainIfNotPresent(
            StripStyle.FLAVOR_DOMAIN, buildTarget, depsExcludingBinary);
    depsExcludingBinary =
        Flavors.propagateFlavorsInDomainIfNotPresent(
            AppleDebugFormat.FLAVOR_DOMAIN, buildTarget, depsExcludingBinary);
    depsExcludingBinary =
        Flavors.propagateFlavorsInDomainIfNotPresent(
            LinkerMapMode.FLAVOR_DOMAIN, buildTarget, depsExcludingBinary);

    if (fatBinaryInfo.isPresent()) {
      UnresolvedAppleCxxPlatform appleCxxPlatform =
          appleCxxPlatformsFlavorDomain.getValue(
              fatBinaryInfo.get().getRepresentativePlatformFlavor());
      depsExcludingBinary =
          depsExcludingBinary.append(
              appleCxxPlatform.getParseTimeDeps(buildTarget.getTargetConfiguration()));
    } else {
      TargetConfiguration targetConfiguration = buildTarget.getTargetConfiguration();
      depsExcludingBinary =
          depsExcludingBinary.append(
              appleCxxPlatformsFlavorDomain
                  .getValue(buildTarget)
                  .map(platform -> platform.getParseTimeDeps(targetConfiguration))
                  .orElse(ImmutableSet.of()));
    }

    extraDepsBuilder.addAll(depsExcludingBinary);
    BuildTarget finalBuildTarget = buildTarget;
    appleCxxPlatformsFlavorDomain
        .getValues()
        .forEach(
            platform ->
                targetGraphOnlyDepsBuilder.addAll(
                    platform.getParseTimeDeps(finalBuildTarget.getTargetConfiguration())));
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      AppleBundleDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    if (metadataClass.isAssignableFrom(FrameworkDependencies.class)) {
      // Bundles should be opaque to framework dependencies.
      return Optional.empty();
    }
    CxxPlatformsProvider cxxPlatformsProvider =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration());
    FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatforms =
        getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration());
    AppleCxxPlatform appleCxxPlatform =
        ApplePlatforms.getAppleCxxPlatformForBuildTarget(
            graphBuilder,
            cxxPlatformsProvider,
            appleCxxPlatforms,
            buildTarget,
            args.getDefaultPlatform(),
            MultiarchFileInfos.create(appleCxxPlatforms, buildTarget));
    BuildTarget binaryTarget =
        AppleDescriptions.getTargetPlatformBinary(
            args.getBinary(), args.getPlatformBinary(), appleCxxPlatform.getFlavor());
    return graphBuilder.requireMetadata(binaryTarget, metadataClass);
  }

  private FlavorDomain<UnresolvedAppleCxxPlatform> getAppleCxxPlatformsFlavorDomain(
      TargetConfiguration toolchainTargetConfiguration) {
    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        toolchainProvider.getByName(
            AppleCxxPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            AppleCxxPlatformsProvider.class);
    return appleCxxPlatformsProvider.getUnresolvedAppleCxxPlatforms();
  }

  private CxxPlatformsProvider getCxxPlatformsProvider(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME,
        toolchainTargetConfiguration,
        CxxPlatformsProvider.class);
  }

  @RuleArg
  interface AbstractAppleBundleDescriptionArg
      extends BuildRuleArg,
          HasAppleBundleFields,
          HasAppleCodesignFields,
          HasContacts,
          HasDefaultPlatform,
          HasDeclaredDeps,
          HasTests {
    // binary should not be immediately added as a dependency, since in case there is platform
    // binary matching target platform exists, it will be used as an actual dependency.
    @Hint(isTargetGraphOnlyDep = true)
    Optional<BuildTarget> getBinary();

    // similar to binary attribute but provides a way to select a platform-specific binary
    @Hint(isTargetGraphOnlyDep = true)
    Optional<PatternMatchedCollection<BuildTarget>> getPlatformBinary();

    /**
     * Returns all binary targets of this bundle, which includes default and platform-specific ones.
     */
    default ImmutableSortedSet<BuildTarget> getBinaryTargets() {
      ImmutableSortedSet.Builder<BuildTarget> binaryTargetsBuilder =
          ImmutableSortedSet.naturalOrder();
      if (getBinary().isPresent()) {
        binaryTargetsBuilder.add(getBinary().get());
      }
      if (getPlatformBinary().isPresent()) {
        binaryTargetsBuilder.addAll(getPlatformBinary().get().getValues());
      }
      return binaryTargetsBuilder.build();
    }

    // ibtool take --module <PRODUCT_MODULE_NAME> arguments to override
    // customModule field set on its elements. (only when customModuleProvider="target")
    // Module (so far, it seems to only represent swift module) contains the
    // implementation of the declared element in nib file.
    Optional<Boolean> getIbtoolModuleFlag();

    Optional<ImmutableList<String>> getIbtoolFlags();

    // See documentation in LinkableCxxConstructorArg. These are equivalent mappings.
    Optional<ImmutableList<CxxLinkGroupMapping>> getResourceGroupMap();

    Optional<String> getResourceGroup();

    @Override
    @Hint(isDep = false)
    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getDeps();
  }
}
