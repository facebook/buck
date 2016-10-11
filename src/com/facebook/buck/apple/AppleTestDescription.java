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
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.cxx.StripStyle;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.facebook.buck.zip.UnzipStep;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.Set;

public class AppleTestDescription implements
    Description<AppleTestDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<AppleTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("apple_test");

  /**
   * Flavors for the additional generated build rules.
   */
  static final Flavor LIBRARY_FLAVOR = ImmutableFlavor.of("apple-test-library");
  static final Flavor BUNDLE_FLAVOR = ImmutableFlavor.of("apple-test-bundle");
  private static final Flavor UNZIP_XCTOOL_FLAVOR = ImmutableFlavor.of("unzip-xctool");

  private static final ImmutableSet<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      LIBRARY_FLAVOR, BUNDLE_FLAVOR);

  /**
   * Auxiliary build modes which makes this description emit just the results of the underlying
   * library delegate.
   */
  private static final Set<Flavor> AUXILIARY_LIBRARY_FLAVORS = ImmutableSet.of(
      CxxCompilationDatabase.COMPILATION_DATABASE,
      CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
      CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);

  private final AppleConfig appleConfig;
  private final AppleLibraryDescription appleLibraryDescription;
  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain;
  private final CxxPlatform defaultCxxPlatform;
  private final CodeSignIdentityStore codeSignIdentityStore;
  private final ProvisioningProfileStore provisioningProfileStore;
  private final Supplier<Optional<Path>> xcodeDeveloperDirectorySupplier;
  private final AppleDebugFormat defaultDebugFormat;
  private final Optional<Long> defaultTestRuleTimeoutMs;

  public AppleTestDescription(
      AppleConfig appleConfig,
      AppleLibraryDescription appleLibraryDescription,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      Supplier<Optional<Path>> xcodeDeveloperDirectorySupplier,
      AppleDebugFormat defaultDebugFormat,
      Optional<Long> defaultTestRuleTimeoutMs) {
    this.appleConfig = appleConfig;
    this.appleLibraryDescription = appleLibraryDescription;
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.appleCxxPlatformFlavorDomain = appleCxxPlatformFlavorDomain;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.codeSignIdentityStore = codeSignIdentityStore;
    this.provisioningProfileStore = provisioningProfileStore;
    this.xcodeDeveloperDirectorySupplier = xcodeDeveloperDirectorySupplier;
    this.defaultDebugFormat = defaultDebugFormat;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
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
    return
        Sets.difference(flavors, SUPPORTED_FLAVORS).isEmpty() ||
        appleLibraryDescription.hasFlavors(flavors);
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    AppleDebugFormat debugFormat = AppleDebugFormat.FLAVOR_DOMAIN
        .getValue(params.getBuildTarget())
        .or(defaultDebugFormat);
    if (params.getBuildTarget().getFlavors().contains(debugFormat.getFlavor())) {
      params = params.withoutFlavor(debugFormat.getFlavor());
    }

    boolean createBundle = Sets.intersection(
        params.getBuildTarget().getFlavors(),
        AUXILIARY_LIBRARY_FLAVORS).isEmpty();
    // Flavors pertaining to the library targets that are generated.
    Sets.SetView<Flavor> libraryFlavors = Sets.difference(
        params.getBuildTarget().getFlavors(),
        AUXILIARY_LIBRARY_FLAVORS);
    boolean addDefaultPlatform = libraryFlavors.isEmpty();
    ImmutableSet.Builder<Flavor> extraFlavorsBuilder = ImmutableSet.builder();
    if (createBundle) {
      extraFlavorsBuilder.add(
          LIBRARY_FLAVOR,
          CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR);
    }
    extraFlavorsBuilder.add(debugFormat.getFlavor());
    if (addDefaultPlatform) {
      extraFlavorsBuilder.add(defaultCxxPlatform.getFlavor());
    }

    Optional<MultiarchFileInfo> multiarchFileInfo =
        MultiarchFileInfos.create(appleCxxPlatformFlavorDomain, params.getBuildTarget());
    AppleCxxPlatform appleCxxPlatform;
    ImmutableList<CxxPlatform> cxxPlatforms;
    if (multiarchFileInfo.isPresent()) {
      ImmutableList.Builder<CxxPlatform> cxxPlatformBuilder = ImmutableList.builder();
      for (BuildTarget thinTarget : multiarchFileInfo.get().getThinTargets()) {
        cxxPlatformBuilder.add(cxxPlatformFlavorDomain.getValue(thinTarget).get());
      }
      cxxPlatforms = cxxPlatformBuilder.build();
      appleCxxPlatform = multiarchFileInfo.get().getRepresentativePlatform();
    } else {
      CxxPlatform cxxPlatform = cxxPlatformFlavorDomain
          .getValue(params.getBuildTarget())
          .or(defaultCxxPlatform);
      cxxPlatforms = ImmutableList.of(cxxPlatform);
      try {
        appleCxxPlatform = appleCxxPlatformFlavorDomain.getValue(cxxPlatform.getFlavor());
      } catch (FlavorDomainException e) {
        throw new HumanReadableException(
            e,
            "%s: Apple test requires an Apple platform, found '%s'",
            params.getBuildTarget(),
            cxxPlatform.getFlavor().getName());
      }
    }

    Optional<TestHostInfo> testHostInfo;
    if (args.testHostApp.isPresent()) {
      testHostInfo = Optional.of(
          createTestHostInfo(
              params,
              resolver,
              args.testHostApp.get(),
              debugFormat,
              libraryFlavors,
              cxxPlatforms));
    } else {
      testHostInfo = Optional.absent();
    }

    BuildTarget libraryTarget = params.getBuildTarget()
        .withAppendedFlavors(extraFlavorsBuilder.build())
        .withAppendedFlavors(debugFormat.getFlavor());
    BuildRule library = createTestLibraryRule(
        targetGraph,
        params,
        resolver,
        args,
        testHostInfo.transform(TestHostInfo.GET_TEST_HOST_APP_BINARY_SOURCE_PATH_FUNCTION),
        testHostInfo.transform(TestHostInfo.GET_BLACKLIST_FUNCTION)
            .or(ImmutableSet.of()),
        libraryTarget);
    if (!createBundle || SwiftLibraryDescription.isSwiftTarget(libraryTarget)) {
      return library;
    }


    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    String platformName = appleCxxPlatform.getAppleSdk().getApplePlatform().getName();

    BuildRule bundle = AppleDescriptions.createAppleBundle(
        cxxPlatformFlavorDomain,
        defaultCxxPlatform,
        appleCxxPlatformFlavorDomain,
        targetGraph,
        params.copyWithChanges(
            params.getBuildTarget().withAppendedFlavors(
                BUNDLE_FLAVOR,
                debugFormat.getFlavor(),
                AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            // We have to add back the original deps here, since they're likely
            // stripped from the library link above (it doesn't actually depend on them).
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .add(library)
                    .addAll(params.getDeclaredDeps().get())
                    .build()),
            params.getExtraDeps()),
        resolver,
        codeSignIdentityStore,
        provisioningProfileStore,
        library.getBuildTarget(),
        args.getExtension(),
        Optional.absent(),
        args.infoPlist,
        args.infoPlistSubstitutions,
        args.deps.get(),
        args.tests.get(),
        debugFormat);

    Optional<SourcePath> xctool = getXctool(params, resolver, sourcePathResolver);

    return new AppleTest(
        xctool,
        appleConfig.getXctoolStutterTimeoutMs(),
        appleCxxPlatform.getXctest(),
        appleConfig.getXctestPlatformNames().contains(platformName),
        platformName,
        appleConfig.getXctoolDefaultDestinationSpecifier(),
        args.destinationSpecifier,
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of(bundle)),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        sourcePathResolver,
        bundle,
        testHostInfo.transform(TestHostInfo.GET_TEST_HOST_APP_FUNCTION),
        args.contacts.get(),
        args.labels.get(),
        args.getRunTestSeparately(),
        xcodeDeveloperDirectorySupplier,
        appleConfig.getTestLogDirectoryEnvironmentVariable(),
        appleConfig.getTestLogLevelEnvironmentVariable(),
        appleConfig.getTestLogLevel(),
        args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
        args.isUiTest());
  }

  private Optional<SourcePath> getXctool(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      final SourcePathResolver sourcePathResolver) {
    // If xctool is specified as a build target in the buck config, it's wrapping ZIP file which
    // we need to unpack to get at the actual binary.  Otherwise, if it's specified as a path, we
    // can use that directly.
    if (appleConfig.getXctoolZipTarget().isPresent()) {
      final BuildRule xctoolZipBuildRule = resolver.getRule(appleConfig.getXctoolZipTarget().get());
      BuildTarget unzipXctoolTarget =
          BuildTarget.builder(xctoolZipBuildRule.getBuildTarget())
              .addFlavors(UNZIP_XCTOOL_FLAVOR)
              .build();
      final Path outputDirectory =
          BuildTargets.getGenPath(params.getProjectFilesystem(), unzipXctoolTarget, "%s/unzipped");
      if (!resolver.getRuleOptional(unzipXctoolTarget).isPresent()) {
        BuildRuleParams unzipXctoolParams =
            params.copyWithChanges(
                unzipXctoolTarget,
                Suppliers.ofInstance(ImmutableSortedSet.of(xctoolZipBuildRule)),
                Suppliers.ofInstance(ImmutableSortedSet.of()));
        resolver.addToIndex(
            new AbstractBuildRule(unzipXctoolParams, sourcePathResolver) {
              @Override
              public ImmutableList<Step> getBuildSteps(
                  BuildContext context,
                  BuildableContext buildableContext) {
                buildableContext.recordArtifact(outputDirectory);
                return ImmutableList.of(
                    new MakeCleanDirectoryStep(getProjectFilesystem(), outputDirectory),
                    new UnzipStep(
                        getProjectFilesystem(),
                        Preconditions.checkNotNull(xctoolZipBuildRule.getPathToOutput()),
                        outputDirectory));
              }
              @Override
              public Path getPathToOutput() {
                return outputDirectory;
              }
            });
      }
      return Optional.of(
          new BuildTargetSourcePath(unzipXctoolTarget, outputDirectory.resolve("bin/xctool")));
    } else if (appleConfig.getXctoolPath().isPresent()) {
      return Optional.of(
          new PathSourcePath(params.getProjectFilesystem(), appleConfig.getXctoolPath().get()));
    } else {
      return Optional.absent();
    }
  }

  private <A extends Arg> BuildRule createTestLibraryRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      Optional<SourcePath> testHostAppBinarySourcePath,
      ImmutableSet<BuildTarget> blacklist,
      BuildTarget libraryTarget) throws NoSuchBuildTargetException {
    BuildTarget existingLibraryTarget = libraryTarget
        .withAppendedFlavors(AppleDebuggableBinary.RULE_FLAVOR, CxxStrip.RULE_FLAVOR)
        .withAppendedFlavors(StripStyle.NON_GLOBAL_SYMBOLS.getFlavor());
    Optional<BuildRule> existingLibrary = resolver.getRuleOptional(existingLibraryTarget);
    BuildRule library;
    if (existingLibrary.isPresent()) {
      library = existingLibrary.get();
    } else {
      library = appleLibraryDescription.createLibraryBuildRule(
          targetGraph,
          params.copyWithBuildTarget(libraryTarget),
          resolver,
          args,
          // For now, instead of building all deps as dylibs and fixing up their install_names,
          // we'll just link them statically.
          Optional.of(Linker.LinkableDepType.STATIC),
          testHostAppBinarySourcePath,
          blacklist);
      resolver.addToIndex(library);
    }
    return library;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AppleTestDescription.Arg constructorArg) {
    // TODO(bhamiltoncx, Coneko): This should technically only be a runtime dependency;
    // doing this adds it to the extra deps in BuildRuleParams passed to
    // the bundle and test rule.
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
    Optional<BuildTarget> xctoolZipTarget = appleConfig.getXctoolZipTarget();
    if (xctoolZipTarget.isPresent()) {
      deps.add(xctoolZipTarget.get());
    }
    deps.addAll(
        appleLibraryDescription.findDepsForTargetFromConstructorArgs(
            buildTarget,
            cellRoots,
            constructorArg));
    return deps.build();
  }

  private TestHostInfo createTestHostInfo(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      BuildTarget testHostAppBuildTarget,
      AppleDebugFormat debugFormat,
      Iterable<Flavor> additionalFlavors,
      ImmutableList<CxxPlatform> cxxPlatforms) throws NoSuchBuildTargetException {
    BuildRule rule = resolver.requireRule(
        BuildTarget.builder(testHostAppBuildTarget)
            .addAllFlavors(additionalFlavors)
            .addFlavors(debugFormat.getFlavor())
            .addFlavors(StripStyle.NON_GLOBAL_SYMBOLS.getFlavor())
            .build());

    if (!(rule instanceof AppleBundle)) {
      throw new HumanReadableException(
          "Apple test rule '%s' has test_host_app '%s' not of type '%s'.",
          params.getBuildTarget(),
          testHostAppBuildTarget,
          AppleBundleDescription.TYPE);
    }

    AppleBundle testHostApp = (AppleBundle) rule;
    SourcePath testHostAppBinarySourcePath = new BuildTargetSourcePath(
        testHostApp.getBinaryBuildRule().getBuildTarget());

    ImmutableMap<BuildTarget, NativeLinkable> roots =
        NativeLinkables.getNativeLinkableRoots(
            testHostApp.getBinary().get().getDeps(),
            Predicates.alwaysTrue());

    // Union the blacklist of all the platforms. This should give a superset for each particular
    // platform, which should be acceptable as items in the blacklist thare are unmatched are simply
    // ignored.
    ImmutableSet.Builder<BuildTarget> blacklistBuilder = ImmutableSet.builder();
    for (CxxPlatform platform : cxxPlatforms) {
      blacklistBuilder.addAll(
          NativeLinkables.getTransitiveNativeLinkables(platform, roots.values()).keySet());
    }
    return TestHostInfo.of(testHostApp, testHostAppBinarySourcePath, blacklistBuilder.build());
  }

  @Value.Immutable
  @BuckStyleTuple
  interface AbstractTestHostInfo {
    AppleBundle getTestHostApp();

    /**
     * Location of the test host binary that can be passed as the "bundle loader" option when
     * linking the test library.
     */
    SourcePath getTestHostAppBinarySourcePath();

    /**
     * Libraries included in test host that should not be linked into the test library.
     */
    ImmutableSet<BuildTarget> getBlacklist();

    Function<TestHostInfo, AppleBundle> GET_TEST_HOST_APP_FUNCTION =
        new Function<TestHostInfo, AppleBundle>() {
          @Override
          public AppleBundle apply(TestHostInfo input) {
            return input.getTestHostApp();
          }
        };

    Function<TestHostInfo, SourcePath> GET_TEST_HOST_APP_BINARY_SOURCE_PATH_FUNCTION =
        new Function<TestHostInfo, SourcePath>() {
          @Override
          public SourcePath apply(TestHostInfo input) {
            return input.getTestHostAppBinarySourcePath();
          }
        };

    Function<TestHostInfo, ImmutableSet<BuildTarget>> GET_BLACKLIST_FUNCTION =
        new Function<TestHostInfo, ImmutableSet<BuildTarget>>() {
          @Override
          public ImmutableSet<BuildTarget> apply(TestHostInfo input) {
            return input.getBlacklist();
          }
        };
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AppleNativeTargetDescriptionArg implements HasAppleBundleFields {
    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<Boolean> canGroup;
    public Optional<Boolean> runTestSeparately;
    public Optional<Boolean> isUiTest;
    public Optional<BuildTarget> testHostApp;

    // Bundle related fields.
    public SourcePath infoPlist;
    public Optional<ImmutableMap<String, String>> infoPlistSubstitutions;
    public Optional<String> xcodeProductType;

    public Optional<ImmutableMap<String, String>> destinationSpecifier;

    public Optional<Long> testRuleTimeoutMs;

    @Override
    public Either<AppleBundleExtension, String> getExtension() {
      return Either.ofLeft(AppleBundleExtension.XCTEST);
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

    public boolean getRunTestSeparately() {
      return runTestSeparately.or(false);
    }

    public boolean isUiTest() {
      return isUiTest.or(false);
    }
  }
}
