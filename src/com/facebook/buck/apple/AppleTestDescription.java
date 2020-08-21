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

import com.facebook.buck.apple.AppleBuildRules.RecursiveDependenciesMode;
import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryForTestsProvider;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.apple.toolchain.UnresolvedAppleCxxPlatform;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.HasContacts;
import com.facebook.buck.core.description.arg.HasTestTimeout;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.core.description.metadata.MetadataProvidingDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.UserVerify;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.FlavorDomainException;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.test.rule.HasTestRunner;
import com.facebook.buck.core.test.rule.coercer.TestRunnerSpecCoercer;
import com.facebook.buck.core.test.rule.impl.ExternalTestRunner;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroups;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.AbsoluteOutputMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.test.config.TestBuckConfig;
import com.facebook.buck.unarchive.UnzipStep;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.versions.Version;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

public class AppleTestDescription
    implements DescriptionWithTargetGraph<AppleTestDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<AppleTestDescription.AbstractAppleTestDescriptionArg>,
        MetadataProvidingDescription<AppleTestDescriptionArg>,
        AppleLibrarySwiftDelegate {

  /** Flavors for the additional generated build rules. */
  static final Flavor LIBRARY_FLAVOR = InternalFlavor.of("apple-test-library");

  static final Flavor BUNDLE_FLAVOR = InternalFlavor.of("apple-test-bundle");
  private static final Flavor UNZIP_XCTOOL_FLAVOR = InternalFlavor.of("unzip-xctool");
  public static final UserFlavor COMPILE_DEPS =
      UserFlavor.of(
          "compile-deps",
          "Produces a directory containing the resource dependencies and an aggregated static library created from the dependencies of the target");

  private static final ImmutableSet<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(LIBRARY_FLAVOR, BUNDLE_FLAVOR, COMPILE_DEPS);

  /**
   * Auxiliary build modes which makes this description emit just the results of the underlying
   * library delegate.
   */
  private static final Set<Flavor> AUXILIARY_LIBRARY_FLAVORS =
      ImmutableSet.of(
          CxxCompilationDatabase.COMPILATION_DATABASE,
          CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
          CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);

  private final ToolchainProvider toolchainProvider;
  private final XCodeDescriptions xcodeDescriptions;
  private final AppleConfig appleConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final SwiftBuckConfig swiftBuckConfig;
  private final AppleLibraryDescription appleLibraryDescription;

  public AppleTestDescription(
      ToolchainProvider toolchainProvider,
      XCodeDescriptions xcodeDescriptions,
      AppleConfig appleConfig,
      CxxBuckConfig cxxBuckConfig,
      SwiftBuckConfig swiftBuckConfig,
      AppleLibraryDescription appleLibraryDescription) {
    this.toolchainProvider = toolchainProvider;
    this.xcodeDescriptions = xcodeDescriptions;
    this.appleConfig = appleConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.swiftBuckConfig = swiftBuckConfig;
    this.appleLibraryDescription = appleLibraryDescription;
  }

  @Override
  public Class<AppleTestDescriptionArg> getConstructorArgType() {
    return AppleTestDescriptionArg.class;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return appleLibraryDescription.flavorDomains(toolchainTargetConfiguration);
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return Sets.difference(flavors, SUPPORTED_FLAVORS).isEmpty()
        || appleLibraryDescription.hasFlavors(flavors, toolchainTargetConfiguration);
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AppleTestDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    args.checkDuplicateSources(graphBuilder.getSourcePathResolver());
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CxxPlatformsProvider cxxPlatformsProvider =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration());
    if (buildTarget.getFlavors().contains(COMPILE_DEPS)) {
      return createCompileDepsRule(
          cxxPlatformsProvider, buildTarget, graphBuilder, context, params, args);
    }
    if (!appleConfig.shouldUseSwiftDelegate()) {
      Optional<BuildRule> buildRule =
          appleLibraryDescription.createSwiftBuildRule(
              buildTarget,
              projectFilesystem,
              graphBuilder,
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

    boolean createBundle =
        Sets.intersection(buildTarget.getFlavors().getSet(), AUXILIARY_LIBRARY_FLAVORS).isEmpty();
    // Flavors pertaining to the library targets that are generated.
    Sets.SetView<Flavor> libraryFlavors =
        Sets.difference(buildTarget.getFlavors().getSet(), AUXILIARY_LIBRARY_FLAVORS);
    boolean addDefaultPlatform = libraryFlavors.isEmpty();
    ImmutableSet.Builder<Flavor> extraFlavorsBuilder = ImmutableSet.builder();
    if (createBundle) {
      extraFlavorsBuilder.add(LIBRARY_FLAVOR, CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR);
    }
    extraFlavorsBuilder.add(debugFormat.getFlavor());
    if (addDefaultPlatform) {
      Flavor defaultCxxFlavor =
          args.getDefaultPlatform()
              .orElse(cxxPlatformsProvider.getDefaultUnresolvedCxxPlatform().getFlavor());
      extraFlavorsBuilder.add(defaultCxxFlavor);
    }

    FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformFlavorDomain =
        getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration());

    Optional<MultiarchFileInfo> multiarchFileInfo =
        MultiarchFileInfos.create(appleCxxPlatformFlavorDomain, buildTarget);
    AppleCxxPlatform appleCxxPlatform;
    ImmutableList<CxxPlatform> cxxPlatforms;
    TargetConfiguration targetConfiguration = buildTarget.getTargetConfiguration();
    if (multiarchFileInfo.isPresent()) {
      FlavorDomain<UnresolvedCxxPlatform> cxxPlatformFlavorDomain =
          cxxPlatformsProvider.getUnresolvedCxxPlatforms();
      ImmutableList.Builder<CxxPlatform> cxxPlatformBuilder = ImmutableList.builder();
      for (BuildTarget thinTarget : multiarchFileInfo.get().getThinTargets()) {
        cxxPlatformBuilder.add(
            cxxPlatformFlavorDomain
                .getValue(thinTarget)
                .get()
                .resolve(graphBuilder, targetConfiguration));
      }
      cxxPlatforms = cxxPlatformBuilder.build();
      appleCxxPlatform =
          appleCxxPlatformFlavorDomain
              .getValue(multiarchFileInfo.get().getRepresentativePlatformFlavor())
              .resolve(graphBuilder);
    } else {
      CxxPlatform cxxPlatform =
          ApplePlatforms.getCxxPlatformForBuildTarget(
                  cxxPlatformsProvider, buildTarget, args.getDefaultPlatform())
              .resolve(graphBuilder, buildTarget.getTargetConfiguration());
      cxxPlatforms = ImmutableList.of(cxxPlatform);
      appleCxxPlatform =
          verifyAppleCxxPlatform(appleCxxPlatformFlavorDomain, cxxPlatform, buildTarget)
              .resolve(graphBuilder);
    }

    Optional<TestHostInfo> testHostWithTargetApp = Optional.empty();
    if (args.getTestHostApp().isPresent()) {
      testHostWithTargetApp =
          Optional.of(
              createTestHostInfo(
                  buildTarget,
                  args.getIsUiTest(),
                  graphBuilder,
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
            graphBuilder,
            args,
            testHostWithTargetApp.flatMap(TestHostInfo::getTestHostAppBinarySourcePath),
            testHostWithTargetApp.map(TestHostInfo::getBlacklist).orElse(ImmutableSet.of()),
            libraryTarget,
            RichStream.from(args.getTestHostApp()).toImmutableSortedSet(Ordering.natural()));
    if (!createBundle || SwiftLibraryDescription.isSwiftTarget(libraryTarget)) {
      return library;
    }

    String platformName = appleCxxPlatform.getAppleSdk().getApplePlatform().getName();

    BuildTarget appleBundleBuildTarget =
        buildTarget.withAppendedFlavors(
            BUNDLE_FLAVOR,
            debugFormat.getFlavor(),
            LinkerMapMode.NO_LINKER_MAP.getFlavor(),
            AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    AppleBundle bundle =
        AppleBundle.class.cast(
            graphBuilder.computeIfAbsent(
                appleBundleBuildTarget,
                ignored ->
                    AppleDescriptions.createAppleBundle(
                        xcodeDescriptions,
                        getCxxPlatformsProvider(targetConfiguration),
                        appleCxxPlatformFlavorDomain,
                        context.getTargetGraph(),
                        appleBundleBuildTarget,
                        projectFilesystem,
                        params.withDeclaredDeps(
                            ImmutableSortedSet.<BuildRule>naturalOrder()
                                .add(library)
                                .addAll(params.getDeclaredDeps().get())
                                .build()),
                        graphBuilder,
                        toolchainProvider.getByName(
                            CodeSignIdentityStore.DEFAULT_NAME,
                            targetConfiguration,
                            CodeSignIdentityStore.class),
                        toolchainProvider.getByName(
                            ProvisioningProfileStore.DEFAULT_NAME,
                            targetConfiguration,
                            ProvisioningProfileStore.class),
                        Optional.of(library.getBuildTarget()),
                        Optional.empty(),
                        args.getDefaultPlatform(),
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
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        appleConfig.getCodesignTimeout(),
                        swiftBuckConfig.getCopyStdlibToFrameworks(),
                        swiftBuckConfig.getUseLipoThin(),
                        cxxBuckConfig.shouldCacheStrip(),
                        appleConfig.useEntitlementsWhenAdhocCodeSigning(),
                        Predicates.alwaysTrue())));

    Optional<SourcePath> xctool =
        getXctool(projectFilesystem, params, targetConfiguration, graphBuilder);

    if (args.getSpecs().isPresent()) {
      UserVerify.verify(
          args.getRunner().isPresent(),
          "runner should be specified for rules implementing test protocol");
      BuildRule runnerRule = graphBuilder.requireRule(args.getRunner().get());
      UserVerify.verify(
          runnerRule instanceof ExternalTestRunner,
          "runner should be an external_test_runner for apple_test");

      ExternalTestRunner runner = (ExternalTestRunner) runnerRule;

      StringWithMacrosConverter macrosConverter =
          StringWithMacrosConverter.of(
              buildTarget,
              context.getCellPathResolver().getCellNameResolver(),
              graphBuilder,
              ImmutableList.of(
                  LocationMacroExpander.INSTANCE, AbsoluteOutputMacroExpander.INSTANCE));

      return new AppleTestX(
          runner.getBinary(),
          TestRunnerSpecCoercer.coerce(args.getSpecs().get(), macrosConverter),
          xctool,
          appleConfig.getXctoolStutterTimeoutMs(),
          appleCxxPlatform.getXctest(),
          appleConfig.getXctestPlatformNames().contains(platformName),
          platformName,
          appleConfig.getXctoolDefaultDestinationSpecifier(),
          buildTarget,
          projectFilesystem,
          params.withDeclaredDeps(ImmutableSortedSet.of(bundle)).withoutExtraDeps(),
          bundle,
          testHostWithTargetApp.map(TestHostInfo::getTestHostApp),
          testHostWithTargetApp.flatMap(TestHostInfo::getUiTestTargetApp),
          args.getContacts(),
          args.getLabels(),
          toolchainProvider.getByName(
              AppleDeveloperDirectoryForTestsProvider.DEFAULT_NAME,
              targetConfiguration,
              AppleDeveloperDirectoryForTestsProvider.class),
          args.getIsUiTest(),
          args.getSnapshotReferenceImagesPath(),
          args.getSnapshotImagesDiffPath(),
          appleConfig.useIdb(),
          appleConfig.getIdbPath());
    }

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
            targetConfiguration,
            AppleDeveloperDirectoryForTestsProvider.class),
        appleConfig.getTestLogDirectoryEnvironmentVariable(),
        appleConfig.getTestLogLevelEnvironmentVariable(),
        appleConfig.getTestLogLevel(),
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(
                appleConfig
                    .getDelegate()
                    .getView(TestBuckConfig.class)
                    .getDefaultTestRuleTimeoutMs()),
        args.getIsUiTest(),
        args.getSnapshotReferenceImagesPath(),
        args.getSnapshotImagesDiffPath(),
        args.getEnv(),
        appleConfig.useIdb(),
        appleConfig.getIdbPath());
  }

  private FlavorDomain<UnresolvedAppleCxxPlatform> getAppleCxxPlatformsFlavorDomain(
      TargetConfiguration toolchainTargetConfiguration) {
    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        toolchainProvider.getByName(
            AppleCxxPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            AppleCxxPlatformsProvider.class);
    FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformFlavorDomain =
        appleCxxPlatformsProvider.getUnresolvedAppleCxxPlatforms();
    return appleCxxPlatformFlavorDomain;
  }

  private UnresolvedAppleCxxPlatform verifyAppleCxxPlatform(
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformFlavorDomain,
      CxxPlatform cxxPlatform,
      BuildTarget buildTarget) {
    UnresolvedAppleCxxPlatform appleCxxPlatform;
    try {
      appleCxxPlatform = appleCxxPlatformFlavorDomain.getValue(cxxPlatform.getFlavor());
    } catch (FlavorDomainException e) {
      throw new HumanReadableException(
          e,
          "%s: Apple test requires an Apple platform, found '%s'",
          buildTarget,
          cxxPlatform.getFlavor().getName());
    }
    return appleCxxPlatform;
  }

  private boolean flavorIndicatesStaticLibrary(Flavor flavor) {
    boolean isCxxLibraryType = CxxLibraryDescription.LIBRARY_TYPE.contains(flavor);
    boolean isStaticLibrary = flavor == CxxLibraryDescription.Type.STATIC.getFlavor();
    return isCxxLibraryType && isStaticLibrary;
  }

  private boolean flavorIndicatesCorrectPlatform(Flavor flavor, CxxPlatform cxxPlatform) {
    boolean isApplePlatform = ApplePlatformType.of(flavor.getName()) != ApplePlatformType.UNKNOWN;
    boolean sameApplePlatform =
        ApplePlatform.of(flavor.getName()) == ApplePlatform.of(cxxPlatform.getFlavor().getName());
    return isApplePlatform && sameApplePlatform;
  }

  private boolean configSdkRootIndicatesCorrectPlatform(
      TargetNode<AppleLibraryDescriptionArg> targetNode, AppleTestDescriptionArg args) {
    if (targetNode.getConstructorArg().getConfigs() == null
        || targetNode.getConstructorArg().getConfigs().get("Debug") == null
        || targetNode.getConstructorArg().getConfigs().get("Debug").get("SDKROOT") == null
        || args.getConfigs() == null
        || args.getConfigs().get("Debug") == null
        || args.getConfigs().get("Debug").get("SDKROOT") == null) {
      return true;
    }
    return ApplePlatform.of(targetNode.getConstructorArg().getConfigs().get("Debug").get("SDKROOT"))
        == ApplePlatform.of(args.getConfigs().get("Debug").get("SDKROOT"));
  }

  private BuildRule createCompileDepsRule(
      CxxPlatformsProvider cxxPlatformsProvider,
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      BuildRuleCreationContextWithTargetGraph context,
      BuildRuleParams params,
      AppleTestDescriptionArg args) {
    CxxPlatform cxxPlatform =
        ApplePlatforms.getCxxPlatformForBuildTarget(
                cxxPlatformsProvider, buildTarget, args.getDefaultPlatform())
            .resolve(graphBuilder, buildTarget.getTargetConfiguration());
    FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformFlavorDomain =
        getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration());
    AppleCxxPlatform appleCxxPlatform =
        verifyAppleCxxPlatform(appleCxxPlatformFlavorDomain, cxxPlatform, buildTarget)
            .resolve(graphBuilder);
    TargetGraph targetGraph = context.getTargetGraph();
    AppleBundleResources collectedResources =
        AppleResources.collectResourceDirsAndFiles(
            xcodeDescriptions,
            targetGraph,
            graphBuilder,
            Optional.empty(),
            targetGraph.get(buildTarget),
            appleCxxPlatform,
            AppleBuildRules.RecursiveDependenciesMode.COPYING,
            Predicates.alwaysTrue());

    ImmutableSortedSet<BuildRule> transitiveStaticLibraryDependencies =
        collectTransitiveStaticLibraries(
            targetGraph, buildTarget, cxxPlatform, args, graphBuilder, context, params);
    BuildRuleParams newParams = params.withExtraDeps(transitiveStaticLibraryDependencies);
    ImmutableList<SourcePath> depBuildRuleSourcePaths =
        transitiveStaticLibraryDependencies.stream()
            .map(BuildRule::getSourcePathToOutput)
            .filter(Objects::nonNull)
            .collect(ImmutableList.toImmutableList());
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    Path outputPath =
        BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s")
            .resolve(AppleTestDescription.COMPILE_DEPS.getName());
    return new AppleTestAggregatedDependencies(
        buildTarget,
        projectFilesystem,
        newParams,
        outputPath,
        collectedResources,
        appleCxxPlatform,
        depBuildRuleSourcePaths);
  }

  private ImmutableSortedSet<BuildRule> collectTransitiveStaticLibraries(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      CxxPlatform cxxPlatform,
      AppleTestDescriptionArg args,
      ActionGraphBuilder graphBuilder,
      BuildRuleCreationContextWithTargetGraph context,
      BuildRuleParams params) {
    ImmutableSet<TargetNode<AppleLibraryDescriptionArg>>
        transitiveStaticLibraryDependencyTargetNodes =
            AppleBuildRules.collectTransitiveBuildRuleTargets(
                xcodeDescriptions,
                targetGraph,
                Optional.empty(),
                ImmutableSet.of(AppleLibraryDescription.class),
                ImmutableList.of(targetGraph.get(buildTarget)),
                RecursiveDependenciesMode.BUILDING);
    return transitiveStaticLibraryDependencyTargetNodes.stream()
        .filter(
            t ->
                t.getBuildTarget().getFlavors().getSet().stream()
                        .reduce(
                            true,
                            (result, next) ->
                                result
                                    && flavorIndicatesStaticLibrary(next)
                                    && flavorIndicatesCorrectPlatform(next, cxxPlatform),
                            (result1, result2) -> result1 && result2)
                    && configSdkRootIndicatesCorrectPlatform(t, args))
        .map(
            t ->
                graphBuilder.computeIfAbsent(
                    t.getBuildTarget()
                        .withoutFlavors()
                        .withAppendedFlavors(
                            CxxLibraryDescription.Type.STATIC.getFlavor(), cxxPlatform.getFlavor()),
                    t2 ->
                        appleLibraryDescription.createBuildRule(
                            context, t2, params, t.getConstructorArg())))
        .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
  }

  private Optional<SourcePath> getXctool(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      TargetConfiguration targetConfiguration,
      ActionGraphBuilder graphBuilder) {
    // If xctool is specified as a build target in the buck config, it's wrapping ZIP file which
    // we need to unpack to get at the actual binary.  Otherwise, if it's specified as a path, we
    // can use that directly.
    if (appleConfig.getXctoolZipTarget(targetConfiguration).isPresent()) {
      BuildRule xctoolZipBuildRule =
          graphBuilder.getRule(appleConfig.getXctoolZipTarget(targetConfiguration).get());

      // Since the content is unzipped in a directory that might differ for each cell the tests are
      // from, we append a flavor that depends on the root path of the projectFilesystem
      // in order to get a different rule for each cell the tests are from.
      String relativeRootPathString =
          xctoolZipBuildRule
              .getProjectFilesystem()
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
          BuildTargetPaths.getGenPath(
              xctoolZipBuildRule.getProjectFilesystem(), unzipXctoolTarget, "%s/unzipped");
      graphBuilder.computeIfAbsent(
          unzipXctoolTarget,
          ignored -> {
            BuildRuleParams unzipXctoolParams =
                params
                    .withDeclaredDeps(ImmutableSortedSet.of(xctoolZipBuildRule))
                    .withoutExtraDeps();
            return new AbstractBuildRuleWithDeclaredAndExtraDeps(
                unzipXctoolTarget, xctoolZipBuildRule.getProjectFilesystem(), unzipXctoolParams) {
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
                                    Objects.requireNonNull(
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
      BuildRuleCreationContextWithTargetGraph context,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      AppleTestDescriptionArg args,
      Optional<SourcePath> testHostAppBinarySourcePath,
      ImmutableSet<BuildTarget> blacklist,
      BuildTarget libraryTarget,
      ImmutableSortedSet<BuildTarget> extraCxxDeps) {
    BuildTarget existingLibraryTarget =
        libraryTarget
            .withAppendedFlavors(AppleDebuggableBinary.RULE_FLAVOR, CxxStrip.RULE_FLAVOR)
            .withAppendedFlavors(StripStyle.NON_GLOBAL_SYMBOLS.getFlavor());
    Optional<BuildRule> existingLibrary = graphBuilder.getRuleOptional(existingLibraryTarget);
    BuildRule library;
    if (existingLibrary.isPresent()) {
      library = existingLibrary.get();
    } else {
      library =
          appleLibraryDescription.createLibraryBuildRule(
              context,
              libraryTarget,
              params,
              graphBuilder,
              args,
              // For now, instead of building all deps as dylibs and fixing up their install_names,
              // we'll just link them statically.
              Optional.of(Linker.LinkableDepType.STATIC),
              testHostAppBinarySourcePath,
              blacklist,
              extraCxxDeps,
              CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction.fromDeps());
      graphBuilder.computeIfAbsent(library.getBuildTarget(), ignored -> library);
    }
    return library;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractAppleTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // TODO(beng, coneko): This should technically only be a runtime dependency;
    // doing this adds it to the extra deps in BuildRuleParams passed to
    // the bundle and test rule.
    Optional<BuildTarget> xctoolZipTarget =
        appleConfig.getXctoolZipTarget(buildTarget.getTargetConfiguration());
    if (xctoolZipTarget.isPresent()) {
      extraDepsBuilder.add(xctoolZipTarget.get());
    }
    extraDepsBuilder.addAll(
        appleConfig.getCodesignProvider().getParseTimeDeps(buildTarget.getTargetConfiguration()));

    CxxPlatformsProvider cxxPlatformsProvider =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration());
    ImmutableList<UnresolvedCxxPlatform> cxxPlatforms =
        cxxPlatformsProvider.getUnresolvedCxxPlatforms().getValues(buildTarget);

    if (cxxPlatforms.isEmpty()) {
      extraDepsBuilder.addAll(
          cxxPlatformsProvider
              .getDefaultUnresolvedCxxPlatform()
              .getParseTimeDeps(buildTarget.getTargetConfiguration()));
    } else {
      cxxPlatforms.forEach(
          platform ->
              extraDepsBuilder.addAll(
                  platform.getParseTimeDeps(buildTarget.getTargetConfiguration())));
    }
    getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration())
        .getValues()
        .forEach(
            platform ->
                targetGraphOnlyDepsBuilder.addAll(
                    platform.getParseTimeDeps(buildTarget.getTargetConfiguration())));
  }

  private AppleBundle getBuildRuleForTestHostAppTarget(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      BuildTarget testHostBuildTarget,
      AppleDebugFormat debugFormat,
      Iterable<Flavor> additionalFlavors,
      String testHostKeyName) {
    BuildRule rule =
        graphBuilder.requireRule(
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
          DescriptionCache.getRuleType(AppleBundleDescription.class));
    }
    return (AppleBundle) rule;
  }

  @VisibleForTesting
  TestHostInfo createTestHostInfo(
      BuildTarget buildTarget,
      boolean isUITestTestHostInfo,
      ActionGraphBuilder graphBuilder,
      BuildTarget testHostAppBuildTarget,
      Optional<BuildTarget> uiTestTargetAppBuildTarget,
      AppleDebugFormat debugFormat,
      Iterable<Flavor> additionalFlavors,
      ImmutableList<CxxPlatform> cxxPlatforms) {

    AppleBundle testHostWithTargetApp =
        getBuildRuleForTestHostAppTarget(
            buildTarget,
            graphBuilder,
            testHostAppBuildTarget,
            debugFormat,
            additionalFlavors,
            "test_host_app");

    SourcePath testHostAppBinarySourcePath =
        testHostWithTargetApp.getBinaryBuildRule().getSourcePathToOutput();

    ImmutableMap<BuildTarget, NativeLinkableGroup> roots =
        NativeLinkableGroups.getNativeLinkableRoots(
            testHostWithTargetApp.getBinary().get().getBuildDeps(),
            r ->
                !(r instanceof NativeLinkableGroup)
                    ? Optional.of(r.getBuildDeps())
                    : Optional.empty());

    // Union the blacklist of all the platforms. This should give a superset for each particular
    // platform, which should be acceptable as items in the blacklist thare are unmatched are simply
    // ignored.
    ImmutableSet.Builder<BuildTarget> blacklistBuilder = ImmutableSet.builder();
    for (CxxPlatform platform : cxxPlatforms) {
      ImmutableSet<BuildTarget> blacklistables =
          NativeLinkables.getTransitiveNativeLinkables(
                  graphBuilder,
                  Iterables.transform(
                      roots.values(), g -> g.getNativeLinkable(platform, graphBuilder)))
              .stream()
              .filter(x -> !x.shouldBeLinkedInAppleTestAndHost())
              .map(x -> x.getBuildTarget())
              .collect(ImmutableSet.toImmutableSet());
      blacklistBuilder.addAll(blacklistables);
    }

    if (!uiTestTargetAppBuildTarget.isPresent()) {
      // Check for legacy UITest setup
      if (isUITestTestHostInfo) {
        return ImmutableTestHostInfo.of(
            testHostWithTargetApp,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of());
      }
      return ImmutableTestHostInfo.of(
          testHostWithTargetApp,
          Optional.empty(),
          Optional.of(testHostAppBinarySourcePath),
          Optional.empty(),
          blacklistBuilder.build());
    }
    AppleBundle uiTestTargetApp =
        getBuildRuleForTestHostAppTarget(
            buildTarget,
            graphBuilder,
            uiTestTargetAppBuildTarget.get(),
            debugFormat,
            additionalFlavors,
            "ui_test_target_app");
    SourcePath uiTestTargetAppBinarySourcePath =
        uiTestTargetApp.getBinaryBuildRule().getSourcePathToOutput();

    return ImmutableTestHostInfo.of(
        testHostWithTargetApp,
        Optional.of(uiTestTargetApp),
        Optional.of(testHostAppBinarySourcePath),
        Optional.of(uiTestTargetAppBinarySourcePath),
        blacklistBuilder.build());
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      AppleTestDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    return appleLibraryDescription.createMetadataForLibrary(
        buildTarget, graphBuilder, cellRoots, args, metadataClass);
  }

  private CxxPlatformsProvider getCxxPlatformsProvider(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME,
        toolchainTargetConfiguration,
        CxxPlatformsProvider.class);
  }

  @BuckStyleValue
  interface TestHostInfo {
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

  @RuleArg
  interface AbstractAppleTestDescriptionArg
      extends AppleNativeTargetDescriptionArg,
          HasAppleBundleFields,
          HasAppleCodesignFields,
          HasContacts,
          HasEntitlementsFile,
          HasTestTimeout,
          HasTestRunner {
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

    // for use with FBSnapshotTestcase, injects the path as IMAGE_DIFF_DIR
    Optional<Either<SourcePath, String>> getSnapshotImagesDiffPath();

    // Bundle related fields.
    ImmutableMap<String, String> getDestinationSpecifier();

    // Environment variables to set during the test run
    Optional<ImmutableMap<String, String>> getEnv();

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
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      CxxLibraryDescription.CommonArg args) {

    ImmutableSet<BuildRule> deps = args.getCxxDeps().get(graphBuilder, cxxPlatform);
    BuildTarget baseTarget = buildTarget.withFlavors();
    Optional<CxxPreprocessorInput> publicInput =
        CxxLibraryDescription.queryMetadataCxxPreprocessorInput(
            graphBuilder, baseTarget, cxxPlatform, HeaderVisibility.PUBLIC);
    Collection<CxxPreprocessorInput> depsInputs =
        CxxPreprocessables.getTransitiveCxxPreprocessorInputFromDeps(
            cxxPlatform, graphBuilder, deps);

    ImmutableSet.Builder<CxxPreprocessorInput> inputsBuilder = ImmutableSet.builder();
    inputsBuilder.addAll(depsInputs);
    publicInput.ifPresent(i -> inputsBuilder.add(i));

    return inputsBuilder.build();
  }
}
