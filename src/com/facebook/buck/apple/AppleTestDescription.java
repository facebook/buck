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

import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
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
import com.google.common.collect.Sets;

import java.util.Map;
import java.util.Set;

public class AppleTestDescription implements
    Description<AppleTestDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<AppleTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("apple_test");

  /**
   * Flavors for the additional generated build rules.
   */
  private static final Flavor LIBRARY_FLAVOR = ImmutableFlavor.of("apple-test-library");
  private static final Flavor BUNDLE_FLAVOR = ImmutableFlavor.of("apple-test-bundle");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      LIBRARY_FLAVOR, BUNDLE_FLAVOR);

  private static final Predicate<Flavor> IS_SUPPORTED_FLAVOR = Predicates.in(SUPPORTED_FLAVORS);

  private static final Set<Flavor> NON_LIBRARY_FLAVORS = ImmutableSet.of(
      CxxCompilationDatabase.COMPILATION_DATABASE,
      CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
      CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);

  private final AppleConfig appleConfig;
  private final AppleBundleDescription appleBundleDescription;
  private final AppleLibraryDescription appleLibraryDescription;
  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;
  private final ImmutableSet<CodeSignIdentity> allValidCodeSignIdentities;

  public AppleTestDescription(
      AppleConfig appleConfig,
      AppleBundleDescription appleBundleDescription,
      AppleLibraryDescription appleLibraryDescription,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      Map<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      CxxPlatform defaultCxxPlatform,
      ImmutableSet<CodeSignIdentity> allValidCodeSignIdentities) {
    this.appleConfig = appleConfig;
    this.appleBundleDescription = appleBundleDescription;
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
    return FluentIterable.from(flavors).allMatch(IS_SUPPORTED_FLAVOR) ||
        appleLibraryDescription.hasFlavors(flavors);
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    String extension = args.extension.isLeft() ?
        args.extension.getLeft().toFileExtension() :
        args.extension.getRight();
    if (!AppleBundleExtensions.VALID_XCTOOL_BUNDLE_EXTENSIONS.contains(extension)) {
      throw new HumanReadableException(
          "Invalid bundle extension for apple_test rule: %s (must be one of %s)",
          extension,
          AppleBundleExtensions.VALID_XCTOOL_BUNDLE_EXTENSIONS);
    }
    boolean createBundle = Sets.intersection(
        params.getBuildTarget().getFlavors(),
        NON_LIBRARY_FLAVORS).isEmpty();
    Sets.SetView<Flavor> nonLibraryFlavors = Sets.difference(
        params.getBuildTarget().getFlavors(),
        NON_LIBRARY_FLAVORS);
    boolean addDefaultPlatform = nonLibraryFlavors.isEmpty();
    ImmutableSet.Builder<Flavor> extraFlavorsBuilder = ImmutableSet.builder();
    if (createBundle) {
      extraFlavorsBuilder.add(
          LIBRARY_FLAVOR,
          CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR);
    }
    if (addDefaultPlatform) {
      extraFlavorsBuilder.add(defaultCxxPlatform.getFlavor());
    }

    CxxPlatform cxxPlatform;
    try {
      cxxPlatform = cxxPlatformFlavorDomain
          .getValue(params.getBuildTarget().getFlavors())
          .or(defaultCxxPlatform);
    } catch (FlavorDomainException e) {
      throw new HumanReadableException(e, "%s: %s", params.getBuildTarget(), e.getMessage());
    }

    Optional<AppleBundle> testHostApp;
    Optional<SourcePath> testHostAppBinarySourcePath;
    ImmutableSet<BuildRule> blacklist;
    if (args.testHostApp.isPresent()) {
      TargetNode<?> testHostAppNode = targetGraph.get(args.testHostApp.get());
      Preconditions.checkNotNull(testHostAppNode);

      if (testHostAppNode.getType() != AppleBundleDescription.TYPE) {
        throw new HumanReadableException(
            "Apple test rule %s has unrecognized test_host_app %s type %s (should be %s)",
            params.getBuildTarget(),
            args.testHostApp.get(),
            testHostAppNode.getType(),
            AppleBundleDescription.TYPE);
      }

      AppleBundleDescription.Arg testHostAppDescription = (AppleBundleDescription.Arg)
          testHostAppNode.getConstructorArg();

      testHostApp = Optional.of(
          appleBundleDescription
              .createBuildRule(
                  targetGraph,
                  params.copyWithChanges(
                      BuildTarget.builder(args.testHostApp.get())
                          .addAllFlavors(nonLibraryFlavors)
                          .build(),
                      Suppliers.ofInstance(
                          BuildRules.toBuildRulesFor(
                              args.testHostApp.get(),
                              resolver,
                              testHostAppNode.getDeclaredDeps())),
                      Suppliers.ofInstance(
                          BuildRules.toBuildRulesFor(
                              args.testHostApp.get(),
                              resolver,
                              testHostAppNode.getExtraDeps()))),
                  resolver,
                  testHostAppDescription));
      testHostAppBinarySourcePath = Optional.<SourcePath>of(
          new BuildTargetSourcePath(testHostAppDescription.binary));

      Pair<MutableDirectedGraph<BuildRule>, Map<BuildTarget, Linker.LinkableDepType>>
          transitiveDependencies = NativeLinkables.getTransitiveNativeLinkableInput(
            cxxPlatform,
            testHostApp.get().getBinary().get().getDeps(),
            Linker.LinkableDepType.STATIC,
            Predicates.alwaysTrue());

      blacklist = ImmutableSet.copyOf(transitiveDependencies.getFirst().getNodes());
    } else {
      testHostApp = Optional.absent();
      testHostAppBinarySourcePath = Optional.absent();
      blacklist = ImmutableSet.of();
    }

    BuildRule library = appleLibraryDescription.createBuildRule(
        targetGraph,
        params.copyWithChanges(
            BuildTarget.builder(params.getBuildTarget())
                .addAllFlavors(extraFlavorsBuilder.build())
                .build(),
            params.getDeclaredDeps(),
            params.getExtraDeps()),
        resolver,
        args,
        // For now, instead of building all deps as dylibs and fixing up their install_names,
        // we'll just link them statically.
        Optional.of(Linker.LinkableDepType.STATIC),
        testHostAppBinarySourcePath,
        blacklist);
    if (!createBundle) {
      return library;
    }

    AppleCxxPlatform appleCxxPlatform =
        platformFlavorsToAppleCxxPlatforms.get(cxxPlatform.getFlavor());
    if (appleCxxPlatform == null) {
      throw new HumanReadableException(
          "%s: Apple test requires an Apple platform, found '%s'",
          params.getBuildTarget(),
          cxxPlatform.getFlavor().getName());
    }

    AppleBundleDestinations destinations =
        AppleBundleDestinations.platformDestinations(
            appleCxxPlatform.getAppleSdk().getApplePlatform());

    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    ImmutableSet.Builder<SourcePath> resourceDirsBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<SourcePath> dirsContainingResourceDirsBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<SourcePath> resourceFilesBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<SourcePath> resourceVariantFilesBuilder = ImmutableSet.builder();

    AppleResources.collectResourceDirsAndFiles(
        targetGraph,
        Preconditions.checkNotNull(targetGraph.get(params.getBuildTarget())),
        resourceDirsBuilder,
        dirsContainingResourceDirsBuilder,
        resourceFilesBuilder,
        resourceVariantFilesBuilder);

    ImmutableSet<SourcePath> resourceDirs = resourceDirsBuilder.build();
    ImmutableSet<SourcePath> dirsContainingResourceDirs = dirsContainingResourceDirsBuilder.build();
    ImmutableSet<SourcePath> resourceFiles = resourceFilesBuilder.build();
    ImmutableSet<SourcePath> resourceVariantFiles = resourceVariantFilesBuilder.build();

    Optional<AppleAssetCatalog> assetCatalog =
        AppleDescriptions.createBuildRuleForTransitiveAssetCatalogDependencies(
            targetGraph,
            params,
            sourcePathResolver,
            appleCxxPlatform.getAppleSdk().getApplePlatform(),
            appleCxxPlatform.getActool());

    String platformName = appleCxxPlatform.getAppleSdk().getApplePlatform().getName();

    AppleBundle bundle = new AppleBundle(
        params.copyWithChanges(
            BuildTarget.builder(params.getBuildTarget()).addFlavors(BUNDLE_FLAVOR).build(),
            // We have to add back the original deps here, since they're likely
            // stripped from the library link above (it doesn't actually depend on them).
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .add(library)
                    .addAll(assetCatalog.asSet())
                    .addAll(params.getDeclaredDeps().get())
                    .addAll(
                        BuildRules.toBuildRulesFor(
                            params.getBuildTarget(),
                            resolver,
                            SourcePaths.filterBuildTargetSourcePaths(
                                Iterables.concat(
                                    resourceFiles,
                                    resourceDirs,
                                    dirsContainingResourceDirs,
                                    resourceVariantFiles))))
                    .build()),
            params.getExtraDeps()),
        sourcePathResolver,
        args.extension,
        args.infoPlist,
        args.infoPlistSubstitutions.get(),
        Optional.of(library),
        destinations,
        resourceDirs,
        resourceFiles,
        dirsContainingResourceDirsBuilder.build(),
        ImmutableSet.<SourcePath>of(),
        Optional.of(resourceVariantFiles),
        appleCxxPlatform.getIbtool(),
        appleCxxPlatform.getDsymutil(),
        appleCxxPlatform.getCxxPlatform().getStrip(),
        assetCatalog,
        ImmutableSortedSet.<BuildTarget>of(),
        appleCxxPlatform.getAppleSdk(),
        allValidCodeSignIdentities,
        args.provisioningProfileSearchPath,
        AppleBundle.DebugInfoFormat.NONE);


    Optional<BuildRule> xctoolZipBuildRule;
    if (appleConfig.getXctoolZipTarget().isPresent()) {
      xctoolZipBuildRule = Optional.of(
          resolver.getRule(appleConfig.getXctoolZipTarget().get()));
    } else {
      xctoolZipBuildRule = Optional.absent();
    }

    return new AppleTest(
        appleConfig.getXctoolPath(),
        xctoolZipBuildRule,
        appleCxxPlatform.getXctest(),
        appleCxxPlatform.getOtest(),
        appleConfig.getXctestPlatformNames().contains(platformName),
        platformName,
        appleConfig.getXctoolDefaultDestinationSpecifier(),
        args.destinationSpecifier,
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(bundle)),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        sourcePathResolver,
        bundle,
        testHostApp,
        extension,
        args.contacts.get(),
        args.labels.get());
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      AppleTestDescription.Arg constructorArg) {
    // TODO(user, coneko): This should technically only be a runtime dependency;
    // doing this adds it to the extra deps in BuildRuleParams passed to
    // the bundle and test rule.
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
    Optional<BuildTarget> xctoolZipTarget = appleConfig.getXctoolZipTarget();
    if (xctoolZipTarget.isPresent()) {
      deps.add(xctoolZipTarget.get());
    }
    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AppleNativeTargetDescriptionArg implements HasAppleBundleFields {
    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<Boolean> canGroup;
    public Optional<BuildTarget> testHostApp;

    // Bundle related fields.
    public Either<AppleBundleExtension, String> extension;
    public SourcePath infoPlist;
    public Optional<ImmutableMap<String, String>> infoPlistSubstitutions;
    public Optional<String> xcodeProductType;
    public Optional<SourcePath> provisioningProfileSearchPath;

    public Optional<ImmutableMap<String, String>> destinationSpecifier;

    @Override
    public Either<AppleBundleExtension, String> getExtension() {
      return extension;
    }

    @Override
    public SourcePath getInfoPlist() {
      return infoPlist;
    }

    @Override
    public Optional<String> getXcodeProductType() {
      return xcodeProductType;
    }

    public boolean canGroup() {
      return canGroup.or(false);
    }
  }
}
