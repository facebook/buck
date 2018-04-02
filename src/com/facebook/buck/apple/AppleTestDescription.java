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
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryForTestsProvider;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasContacts;
import com.facebook.buck.rules.HasTestTimeout;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.swift.SwiftRuntimeNativeLinkable;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.unarchive.UnzipStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.versions.Version;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

public class AppleTestDescription
    implements Description<AppleTestDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<AppleTestDescription.AbstractAppleTestDescriptionArg>,
        MetadataProvidingDescription<AppleTestDescriptionArg>,
        AppleLibrarySwiftDelegate {

  /** Flavors for the additional generated build rules. */
  static final Flavor LIBRARY_FLAVOR = InternalFlavor.of("apple-test-library");

  static final Flavor BUNDLE_FLAVOR = InternalFlavor.of("apple-test-bundle");
  private static final Flavor UNZIP_XCTOOL_FLAVOR = InternalFlavor.of("unzip-xctool");

  private static final ImmutableSet<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(LIBRARY_FLAVOR, BUNDLE_FLAVOR);

  /**
   * Auxiliary build modes which makes this description emit just the results of the underlying
   * library delegate.
   */
  private static final Set<Flavor> AUXILIARY_LIBRARY_FLAVORS =
      ImmutableSet.of(
          CxxCompilationDatabase.COMPILATION_DATABASE,
          CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
          CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
          CxxDescriptionEnhancer.SANDBOX_TREE_FLAVOR);

  private final ToolchainProvider toolchainProvider;
  private final AppleConfig appleConfig;
  private final AppleLibraryDescription appleLibraryDescription;

  public AppleTestDescription(
      ToolchainProvider toolchainProvider,
      AppleConfig appleConfig,
      AppleLibraryDescription appleLibraryDescription) {
    this.toolchainProvider = toolchainProvider;
    this.appleConfig = appleConfig;
    this.appleLibraryDescription = appleLibraryDescription;
  }

  @Override
  public Class<AppleTestDescriptionArg> getConstructorArgType() {
    return AppleTestDescriptionArg.class;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return appleLibraryDescription.flavorDomains();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return Sets.difference(flavors, SUPPORTED_FLAVORS).isEmpty()
        || appleLibraryDescription.hasFlavors(flavors);
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AppleTestDescriptionArg args) {
    BuildRuleResolver resolver = context.getBuildRuleResolver();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    if (!appleConfig.shouldUseSwiftDelegate()) {
      Optional<BuildRule> buildRule =
          appleLibraryDescription.createSwiftBuildRule(
              buildTarget,
              projectFilesystem,
              params,
              resolver,
              new SourcePathRuleFinder(resolver),
              context.getCellPathResolver(),
              args,
              Optional.of(this));

      if (buildRule.isPresent()) {
        return buildRule.get();
      }
    }

    if (args.getUiTestTargetApp().isPresent() && !args.getIsUiTest()) {
      throw new HumanReadableException(
          "Invalid configuration for %s with 'ui_test_target_app' specified, but 'is_ui_test' set to false or 'test_host_app' not specified",
          buildTarget);
    }

    AppleDebugFormat debugFormat =
        AppleDebugFormat.FLAVOR_DOMAIN
            .getValue(buildTarget)
            .orElse(appleConfig.getDefaultDebugInfoFormatForTests());
    if (buildTarget.getFlavors().contains(debugFormat.getFlavor())) {
      buildTarget = buildTarget.withoutFlavors(debugFormat.getFlavor());
    }

    CxxPlatformsProvider cxxPlatformsProvider = getCxxPlatformsProvider();
    FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain = cxxPlatformsProvider.getCxxPlatforms();
    Flavor defaultCxxFlavor = cxxPlatformsProvider.getDefaultCxxPlatform().getFlavor();

    boolean createBundle =
        Sets.intersection(buildTarget.getFlavors(), AUXILIARY_LIBRARY_FLAVORS).isEmpty();
    // Flavors pertaining to the library targets that are generated.
    Sets.SetView<Flavor> libraryFlavors =
        Sets.difference(buildTarget.getFlavors(), AUXILIARY_LIBRARY_FLAVORS);
    boolean addDefaultPlatform = libraryFlavors.isEmpty();
    ImmutableSet.Builder<Flavor> extraFlavorsBuilder = ImmutableSet.builder();
    if (createBundle) {
      extraFlavorsBuilder.add(LIBRARY_FLAVOR, CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR);
    }
    extraFlavorsBuilder.add(debugFormat.getFlavor());
    if (addDefaultPlatform) {
      extraFlavorsBuilder.add(defaultCxxFlavor);
    }

    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        toolchainProvider.getByName(
            AppleCxxPlatformsProvider.DEFAULT_NAME, AppleCxxPlatformsProvider.class);
    FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain =
        appleCxxPlatformsProvider.getAppleCxxPlatforms();

    Optional<MultiarchFileInfo> multiarchFileInfo =
        MultiarchFileInfos.create(appleCxxPlatformFlavorDomain, buildTarget);
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
      CxxPlatform cxxPlatform =
          cxxPlatformFlavorDomain
              .getValue(buildTarget)
              .orElse(cxxPlatformFlavorDomain.getValue(defaultCxxFlavor));
      cxxPlatforms = ImmutableList.of(cxxPlatform);
      try {
        appleCxxPlatform = appleCxxPlatformFlavorDomain.getValue(cxxPlatform.getFlavor());
      } catch (FlavorDomainException e) {
        throw new HumanReadableException(
            e,
            "%s: Apple test requires an Apple platform, found '%s'",
            buildTarget,
            cxxPlatform.getFlavor().getName());
      }
    }

    Optional<TestHostInfo> testHostWithTargetApp = Optional.empty();
    if (args.getTestHostApp().isPresent()) {
      testHostWithTargetApp =
          Optional.of(
              createTestHostInfo(
                  buildTarget,
                  args.getIsUiTest(),
                  resolver,
                  args.getTestHostApp().get(),
                  args.getUiTestTargetApp(),
                  debugFormat,
                  libraryFlavors,
                  cxxPlatforms));
    }

    BuildTarget libraryTarget =
        buildTarget
            .withAppendedFlavors(extraFlavorsBuilder.build())
            .withAppendedFlavors(debugFormat.getFlavor())
            .withAppendedFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor());
    BuildRule library =
        createTestLibraryRule(
            context,
            params,
            resolver,
            args,
            testHostWithTargetApp.flatMap(TestHostInfo::getTestHostAppBinarySourcePath),
            testHostWithTargetApp.map(TestHostInfo::getBlacklist).orElse(ImmutableSet.of()),
            libraryTarget,
            Optionals.toStream(args.getTestHostApp()).toImmutableSortedSet(Ordering.natural()));
    if (!createBundle || SwiftLibraryDescription.isSwiftTarget(libraryTarget)) {
      return library;
    }

    String platformName = appleCxxPlatform.getAppleSdk().getApplePlatform().getName();

    AppleBundle bundle =
        AppleDescriptions.createAppleBundle(
            cxxPlatformFlavorDomain,
            defaultCxxFlavor,
            appleCxxPlatformFlavorDomain,
            context.getTargetGraph(),
            buildTarget.withAppendedFlavors(
                BUNDLE_FLAVOR,
                debugFormat.getFlavor(),
                LinkerMapMode.NO_LINKER_MAP.getFlavor(),
                AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            projectFilesystem,
            params.withDeclaredDeps(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .add(library)
                    .addAll(params.getDeclaredDeps().get())
                    .build()),
            resolver,
            toolchainProvider.getByName(
                CodeSignIdentityStore.DEFAULT_NAME, CodeSignIdentityStore.class),
            toolchainProvider.getByName(
                ProvisioningProfileStore.DEFAULT_NAME, ProvisioningProfileStore.class),
            Optional.of(library.getBuildTarget()),
            Optional.empty(),
            args.getExtension(),
            Optional.empty(),
            args.getInfoPlist(),
            args.getInfoPlistSubstitutions(),
            args.getDeps(),
            args.getTests(),
            debugFormat,
            appleConfig.useDryRunCodeSigning(),
            appleConfig.cacheBundlesAndPackages(),
            appleConfig.shouldVerifyBundleResources(),
            appleConfig.assetCatalogValidation(),
            args.getAssetCatalogsCompilationOptions(),
            args.getCodesignFlags(),
            args.getCodesignIdentity(),
            Optional.empty());
    resolver.addToIndex(bundle);

    Optional<SourcePath> xctool = getXctool(projectFilesystem, params, resolver);

    return new AppleTest(
        xctool,
        appleConfig.getXctoolStutterTimeoutMs(),
        appleCxxPlatform.getXctest(),
        appleConfig.getXctestPlatformNames().contains(platformName),
        platformName,
        appleConfig.getXctoolDefaultDestinationSpecifier(),
        Optional.of(args.getDestinationSpecifier()),
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(bundle)).withoutExtraDeps(),
        bundle,
        testHostWithTargetApp.map(TestHostInfo::getTestHostApp),
        testHostWithTargetApp.flatMap(TestHostInfo::getUiTestTargetApp),
        args.getContacts(),
        args.getLabels(),
        args.getRunTestSeparately(),
        toolchainProvider.getByName(
            AppleDeveloperDirectoryForTestsProvider.DEFAULT_NAME,
            AppleDeveloperDirectoryForTestsProvider.class),
        appleConfig.getTestLogDirectoryEnvironmentVariable(),
        appleConfig.getTestLogLevelEnvironmentVariable(),
        appleConfig.getTestLogLevel(),
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(appleConfig.getDelegate().getDefaultTestRuleTimeoutMs()),
        args.getIsUiTest(),
        args.getSnapshotReferenceImagesPath());
  }

  private Optional<SourcePath> getXctool(
      ProjectFilesystem projectFilesystem, BuildRuleParams params, BuildRuleResolver resolver) {
    // If xctool is specified as a build target in the buck config, it's wrapping ZIP file which
    // we need to unpack to get at the actual binary.  Otherwise, if it's specified as a path, we
    // can use that directly.
    if (appleConfig.getXctoolZipTarget().isPresent()) {
      BuildRule xctoolZipBuildRule = resolver.getRule(appleConfig.getXctoolZipTarget().get());

      // Since the content is unzipped in a directory that might differ for each cell the tests are
      // from, we append a flavor that depends on the root path of the projectFilesystem
      // in order to get a different rule for each cell the tests are from.
      String relativeRootPathString =
          xctoolZipBuildRule
              .getBuildTarget()
              .getCellPath()
              .relativize(projectFilesystem.getRootPath())
              .toString();
      Hasher hasher = Hashing.sha1().newHasher();
      hasher.putBytes(relativeRootPathString.getBytes(Charsets.UTF_8));
      String sha1Hash = hasher.hash().toString();

      BuildTarget unzipXctoolTarget =
          xctoolZipBuildRule
              .getBuildTarget()
              .withAppendedFlavors(UNZIP_XCTOOL_FLAVOR)
              .withAppendedFlavors(InternalFlavor.of(sha1Hash));
      Path outputDirectory =
          BuildTargets.getGenPath(projectFilesystem, unzipXctoolTarget, "%s/unzipped");
      resolver.computeIfAbsent(
          unzipXctoolTarget,
          ignored -> {
            BuildRuleParams unzipXctoolParams =
                params
                    .withDeclaredDeps(ImmutableSortedSet.of(xctoolZipBuildRule))
                    .withoutExtraDeps();
            return new AbstractBuildRuleWithDeclaredAndExtraDeps(
                unzipXctoolTarget, projectFilesystem, unzipXctoolParams) {
              @Override
              public ImmutableList<Step> getBuildSteps(
                  BuildContext context, BuildableContext buildableContext) {
                buildableContext.recordArtifact(outputDirectory);
                return new ImmutableList.Builder<Step>()
                    .addAll(
                        MakeCleanDirectoryStep.of(
                            BuildCellRelativePath.fromCellRelativePath(
                                context.getBuildCellRootPath(),
                                getProjectFilesystem(),
                                outputDirectory)))
                    .add(
                        new UnzipStep(
                            getProjectFilesystem(),
                            context
                                .getSourcePathResolver()
                                .getAbsolutePath(
                                    Preconditions.checkNotNull(
                                        xctoolZipBuildRule.getSourcePathToOutput())),
                            outputDirectory,
                            Optional.empty()))
                    .build();
              }

              @Override
              public SourcePath getSourcePathToOutput() {
                return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputDirectory);
              }
            };
          });
      return Optional.of(
          ExplicitBuildTargetSourcePath.of(
              unzipXctoolTarget, outputDirectory.resolve("bin/xctool")));
    } else if (appleConfig.getXctoolPath().isPresent()) {
      return Optional.of(PathSourcePath.of(projectFilesystem, appleConfig.getXctoolPath().get()));
    } else {
      return Optional.empty();
    }
  }

  private BuildRule createTestLibraryRule(
      BuildRuleCreationContext context,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      AppleTestDescriptionArg args,
      Optional<SourcePath> testHostAppBinarySourcePath,
      ImmutableSet<BuildTarget> blacklist,
      BuildTarget libraryTarget,
      ImmutableSortedSet<BuildTarget> extraCxxDeps) {
    BuildTarget existingLibraryTarget =
        libraryTarget
            .withAppendedFlavors(AppleDebuggableBinary.RULE_FLAVOR, CxxStrip.RULE_FLAVOR)
            .withAppendedFlavors(StripStyle.NON_GLOBAL_SYMBOLS.getFlavor());
    Optional<BuildRule> existingLibrary = resolver.getRuleOptional(existingLibraryTarget);
    BuildRule library;
    if (existingLibrary.isPresent()) {
      library = existingLibrary.get();
    } else {
      library =
          appleLibraryDescription.createLibraryBuildRule(
              context,
              libraryTarget,
              params,
              resolver,
              args,
              // For now, instead of building all deps as dylibs and fixing up their install_names,
              // we'll just link them statically.
              Optional.of(Linker.LinkableDepType.STATIC),
              testHostAppBinarySourcePath,
              blacklist,
              extraCxxDeps,
              CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction.fromDeps());
      resolver.addToIndex(library);
    }
    return library;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractAppleTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // TODO(beng, coneko): This should technically only be a runtime dependency;
    // doing this adds it to the extra deps in BuildRuleParams passed to
    // the bundle and test rule.
    Optional<BuildTarget> xctoolZipTarget = appleConfig.getXctoolZipTarget();
    if (xctoolZipTarget.isPresent()) {
      extraDepsBuilder.add(xctoolZipTarget.get());
    }
    extraDepsBuilder.addAll(appleConfig.getCodesignProvider().getParseTimeDeps());

    CxxPlatformsProvider cxxPlatformsProvider = getCxxPlatformsProvider();
    ImmutableList<CxxPlatform> cxxPlatforms =
        cxxPlatformsProvider.getCxxPlatforms().getValues(buildTarget);

    extraDepsBuilder.addAll(
        CxxPlatforms.getParseTimeDeps(
            cxxPlatforms.isEmpty()
                ? Collections.singleton(cxxPlatformsProvider.getDefaultCxxPlatform())
                : cxxPlatforms));
  }

  private AppleBundle getBuildRuleForTestHostAppTarget(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      BuildTarget testHostBuildTarget,
      AppleDebugFormat debugFormat,
      Iterable<Flavor> additionalFlavors,
      String testHostKeyName) {
    BuildRule rule =
        resolver.requireRule(
            testHostBuildTarget.withAppendedFlavors(
                ImmutableSet.<Flavor>builder()
                    .addAll(additionalFlavors)
                    .add(debugFormat.getFlavor(), StripStyle.NON_GLOBAL_SYMBOLS.getFlavor())
                    .build()));

    if (!(rule instanceof AppleBundle)) {
      throw new HumanReadableException(
          "Apple test rule '%s' has %s '%s' not of type '%s'.",
          buildTarget,
          testHostKeyName,
          testHostBuildTarget,
          Description.getBuildRuleType(AppleBundleDescription.class));
    }
    return (AppleBundle) rule;
  }

  @VisibleForTesting
  TestHostInfo createTestHostInfo(
      BuildTarget buildTarget,
      boolean isUITestTestHostInfo,
      BuildRuleResolver resolver,
      BuildTarget testHostAppBuildTarget,
      Optional<BuildTarget> uiTestTargetAppBuildTarget,
      AppleDebugFormat debugFormat,
      Iterable<Flavor> additionalFlavors,
      ImmutableList<CxxPlatform> cxxPlatforms) {

    AppleBundle testHostWithTargetApp =
        getBuildRuleForTestHostAppTarget(
            buildTarget,
            resolver,
            testHostAppBuildTarget,
            debugFormat,
            additionalFlavors,
            "test_host_app");

    SourcePath testHostAppBinarySourcePath =
        testHostWithTargetApp.getBinaryBuildRule().getSourcePathToOutput();

    ImmutableMap<BuildTarget, NativeLinkable> roots =
        NativeLinkables.getNativeLinkableRoots(
            testHostWithTargetApp.getBinary().get().getBuildDeps(),
            r -> !(r instanceof NativeLinkable) ? Optional.of(r.getBuildDeps()) : Optional.empty());

    // Union the blacklist of all the platforms. This should give a superset for each particular
    // platform, which should be acceptable as items in the blacklist thare are unmatched are simply
    // ignored.
    ImmutableSet.Builder<BuildTarget> blacklistBuilder = ImmutableSet.builder();
    for (CxxPlatform platform : cxxPlatforms) {
      ImmutableSet<BuildTarget> blacklistables =
          NativeLinkables.getTransitiveNativeLinkables(platform, resolver, roots.values())
              .entrySet()
              .stream()
              .filter(x -> !(x.getValue() instanceof SwiftRuntimeNativeLinkable))
              .map(x -> x.getKey())
              .collect(ImmutableSet.toImmutableSet());
      blacklistBuilder.addAll(blacklistables);
    }

    if (!uiTestTargetAppBuildTarget.isPresent()) {
      // Check for legacy UITest setup
      if (isUITestTestHostInfo) {
        return TestHostInfo.of(
            testHostWithTargetApp,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of());
      }
      return TestHostInfo.of(
          testHostWithTargetApp,
          Optional.empty(),
          Optional.of(testHostAppBinarySourcePath),
          Optional.empty(),
          blacklistBuilder.build());
    }
    AppleBundle uiTestTargetApp =
        getBuildRuleForTestHostAppTarget(
            buildTarget,
            resolver,
            uiTestTargetAppBuildTarget.get(),
            debugFormat,
            additionalFlavors,
            "ui_test_target_app");
    SourcePath uiTestTargetAppBinarySourcePath =
        uiTestTargetApp.getBinaryBuildRule().getSourcePathToOutput();

    return TestHostInfo.of(
        testHostWithTargetApp,
        Optional.of(uiTestTargetApp),
        Optional.of(testHostAppBinarySourcePath),
        Optional.of(uiTestTargetAppBinarySourcePath),
        blacklistBuilder.build());
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleTestDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    return appleLibraryDescription.createMetadataForLibrary(
        buildTarget, resolver, cellRoots, args, metadataClass);
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }

  @Value.Immutable
  @BuckStyleTuple
  interface AbstractTestHostInfo {
    AppleBundle getTestHostApp();

    Optional<AppleBundle> getUiTestTargetApp();

    /**
     * Location of the test host binary that can be passed as the "bundle loader" option when
     * linking the test library.
     */
    Optional<SourcePath> getTestHostAppBinarySourcePath();

    /**
     * Location of the ui test target binary that can be passed test target application of UITest
     */
    Optional<SourcePath> getUiTestTargetAppBinarySourcePath();

    /** Libraries included in test host that should not be linked into the test library. */
    ImmutableSet<BuildTarget> getBlacklist();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAppleTestDescriptionArg
      extends AppleNativeTargetDescriptionArg,
          HasAppleBundleFields,
          HasAppleCodesignFields,
          HasContacts,
          HasEntitlementsFile,
          HasTestTimeout {
    @Value.Default
    default boolean getRunTestSeparately() {
      return false;
    }

    @Value.Default
    default boolean getIsUiTest() {
      return false;
    }

    // Application used to host test bundle process
    Optional<BuildTarget> getTestHostApp();

    // Application used as XCUITest application target.
    Optional<BuildTarget> getUiTestTargetApp();

    // for use with FBSnapshotTestcase, injects the path as FB_REFERENCE_IMAGE_DIR
    Optional<Either<SourcePath, String>> getSnapshotReferenceImagesPath();

    // Bundle related fields.
    ImmutableMap<String, String> getDestinationSpecifier();

    @Override
    default Either<AppleBundleExtension, String> getExtension() {
      return Either.ofLeft(AppleBundleExtension.XCTEST);
    }

    @Override
    default Optional<String> getProductName() {
      return Optional.empty();
    }
  }

  // AppleLibrarySwiftDelegate

  @Override
  public ImmutableSet<CxxPreprocessorInput> getPreprocessorInputForSwift(
      BuildTarget buildTarget,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      CxxLibraryDescription.CommonArg args) {

    ImmutableSet<BuildRule> deps = args.getCxxDeps().get(ruleResolver, cxxPlatform);
    BuildTarget baseTarget = buildTarget.withFlavors();
    Optional<CxxPreprocessorInput> publicInput =
        CxxLibraryDescription.queryMetadataCxxPreprocessorInput(
            ruleResolver, baseTarget, cxxPlatform, HeaderVisibility.PUBLIC);
    Collection<CxxPreprocessorInput> depsInputs =
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(cxxPlatform, ruleResolver, deps);

    ImmutableSet.Builder<CxxPreprocessorInput> inputsBuilder = ImmutableSet.builder();
    inputsBuilder.addAll(depsInputs);
    publicInput.ifPresent(i -> inputsBuilder.add(i));

    return inputsBuilder.build();
  }
}
