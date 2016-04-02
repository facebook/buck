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

import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxConstructorArg;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.js.ReactNativeFlavors;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Common logic for a {@link com.facebook.buck.rules.Description} that creates Apple target rules.
 */
public class AppleDescriptions {

  public static final Flavor FRAMEWORK_FLAVOR = ImmutableFlavor.of("framework");

  public static final Flavor INCLUDE_FRAMEWORKS_FLAVOR = ImmutableFlavor.of("include-frameworks");
  public static final Flavor NO_INCLUDE_FRAMEWORKS_FLAVOR =
      ImmutableFlavor.of("no-include-frameworks");
  public static final FlavorDomain<Boolean> INCLUDE_FRAMEWORKS =
      new FlavorDomain<>(
          "Include frameworks",
          ImmutableMap.of(
              INCLUDE_FRAMEWORKS_FLAVOR, Boolean.TRUE,
              NO_INCLUDE_FRAMEWORKS_FLAVOR, Boolean.FALSE));
  public static final Flavor APPLE_DSYM = ImmutableFlavor.of("apple-dsym");
  public static final Flavor APPLE_BUNDLE_WITH_DSYM = ImmutableFlavor.of("apple-bundle-with-dsym");


  private static final SourceList EMPTY_HEADERS = SourceList.ofUnnamedSources(
      ImmutableSortedSet.<SourcePath>of());
  private static final String MERGED_ASSET_CATALOG_NAME = "Merged";

  /** Utility class: do not instantiate. */
  private AppleDescriptions() {}

  public static Path getPathToHeaderSymlinkTree(
      TargetNode<? extends CxxLibraryDescription.Arg> targetNode,
      HeaderVisibility headerVisibility) {
    return BuildTargets.getGenPath(
        BuildTarget.of(targetNode.getBuildTarget().getUnflavoredBuildTarget()),
        "%s" + AppleHeaderVisibilities.getHeaderSymlinkTreeSuffix(headerVisibility));
  }

  public static Path getHeaderPathPrefix(
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget) {
    return Paths.get(arg.headerPathPrefix.or(buildTarget.getShortName()));
  }

  public static ImmutableSortedMap<String, SourcePath> convertAppleHeadersToPublicCxxHeaders(
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      CxxLibraryDescription.Arg arg) {
    // The exported headers in the populated cxx constructor arg will contain exported headers from
    // the apple constructor arg with the public include style.
    return AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
                pathResolver,
                headerPathPrefix,
                arg.exportedHeaders.or(EMPTY_HEADERS));
  }

  public static ImmutableSortedMap<String, SourcePath> convertAppleHeadersToPrivateCxxHeaders(
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      CxxLibraryDescription.Arg arg) {
    // The private headers will contain exported headers with the private include style and private
    // headers with both styles.
    return ImmutableSortedMap.<String, SourcePath>naturalOrder()
        .putAll(
            AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
                pathResolver,
                arg.headers.or(EMPTY_HEADERS)))
        .putAll(
            AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
                pathResolver,
                headerPathPrefix,
                arg.headers.or(EMPTY_HEADERS)))
        .putAll(
            AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
                pathResolver,
                arg.exportedHeaders.or(EMPTY_HEADERS)))
        .build();
  }

  @VisibleForTesting
  static ImmutableSortedMap<String, SourcePath> parseAppleHeadersForUseFromOtherTargets(
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      SourceList headers) {
    if (headers.getUnnamedSources().isPresent()) {
      // The user specified a set of header files. For use from other targets, prepend their names
      // with the header path prefix.
      return convertToFlatCxxHeaders(
          headerPathPrefix,
          pathResolver,
          headers.getUnnamedSources().get());
    } else {
      // The user specified a map from include paths to header files. Just use the specified map.
      return headers.getNamedSources().get();
    }
  }

  @VisibleForTesting
  static ImmutableMap<String, SourcePath> parseAppleHeadersForUseFromTheSameTarget(
      Function<SourcePath, Path> pathResolver,
      SourceList headers) {
    if (headers.getUnnamedSources().isPresent()) {
      // The user specified a set of header files. Headers can be included from the same target
      // using only their file name without a prefix.
      return convertToFlatCxxHeaders(
          Paths.get(""),
          pathResolver,
          headers.getUnnamedSources().get());
    } else {
      // The user specified a map from include paths to header files. There is nothing we need to
      // add on top of the exported headers.
      return ImmutableMap.of();
    }
  }

  @VisibleForTesting
  static ImmutableSortedMap<String, SourcePath> convertToFlatCxxHeaders(
      Path headerPathPrefix,
      Function<SourcePath, Path> sourcePathResolver,
      Set<SourcePath> headerPaths) {
    ImmutableSortedMap.Builder<String, SourcePath> cxxHeaders = ImmutableSortedMap.naturalOrder();
    for (SourcePath headerPath : headerPaths) {
      Path fileName = sourcePathResolver.apply(headerPath).getFileName();
      String key = headerPathPrefix.resolve(fileName).toString();
      cxxHeaders.put(key, headerPath);
    }
    return cxxHeaders.build();
  }

  public static void populateCxxConstructorArg(
      SourcePathResolver resolver,
      CxxConstructorArg output,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget) {
    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(arg, buildTarget);
    // The resulting cxx constructor arg will have no exported headers and both headers and exported
    // headers specified in the apple arg will be available with both public and private include
    // styles.
    ImmutableSortedMap<String, SourcePath> headerMap =
        ImmutableSortedMap.<String, SourcePath>naturalOrder()
            .putAll(
                convertAppleHeadersToPublicCxxHeaders(
                    resolver.deprecatedPathFunction(),
                    headerPathPrefix,
                    arg))
            .putAll(
                convertAppleHeadersToPrivateCxxHeaders(
                    resolver.deprecatedPathFunction(),
                    headerPathPrefix,
                    arg))
            .build();

    output.srcs = arg.srcs;
    output.platformSrcs = arg.platformSrcs;
    output.headers = Optional.of(SourceList.ofNamedSources(headerMap));
    output.platformHeaders = Optional.of(PatternMatchedCollection.<SourceList>of());
    output.prefixHeader = arg.prefixHeader;
    output.compilerFlags = arg.compilerFlags;
    output.platformCompilerFlags = arg.platformCompilerFlags;
    output.langCompilerFlags = arg.langCompilerFlags;
    output.preprocessorFlags = arg.preprocessorFlags;
    output.platformPreprocessorFlags = arg.platformPreprocessorFlags;
    output.langPreprocessorFlags = arg.langPreprocessorFlags;
    output.linkerFlags = arg.linkerFlags;
    output.platformLinkerFlags = arg.platformLinkerFlags;
    output.frameworks = arg.frameworks;
    output.libraries = arg.libraries;
    output.deps = arg.deps;
    // This is intentionally an empty string; we put all prefixes into
    // the header map itself.
    output.headerNamespace = Optional.of("");
    output.cxxRuntimeType = Optional.absent();
    output.tests = arg.tests;
  }

  public static void populateCxxBinaryDescriptionArg(
      SourcePathResolver resolver,
      CxxBinaryDescription.Arg output,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget) {
    populateCxxConstructorArg(
        resolver,
        output,
        arg,
        buildTarget);
    output.linkStyle = Optional.absent();
  }

  public static void populateCxxLibraryDescriptionArg(
      SourcePathResolver resolver,
      CxxLibraryDescription.Arg output,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget) {
    populateCxxConstructorArg(
        resolver,
        output,
        arg,
        buildTarget);
    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(arg, buildTarget);
    output.headers = Optional.of(
        SourceList.ofNamedSources(
            convertAppleHeadersToPrivateCxxHeaders(
                resolver.deprecatedPathFunction(),
                headerPathPrefix,
                arg)));
    output.exportedDeps = arg.exportedDeps;
    output.exportedPreprocessorFlags = arg.exportedPreprocessorFlags;
    output.exportedHeaders = Optional.of(
        SourceList.ofNamedSources(
            convertAppleHeadersToPublicCxxHeaders(
                resolver.deprecatedPathFunction(),
                headerPathPrefix,
                arg)));
    output.exportedPlatformHeaders = Optional.of(PatternMatchedCollection.<SourceList>of());
    output.exportedPlatformPreprocessorFlags = arg.exportedPlatformPreprocessorFlags;
    output.exportedLangPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    output.exportedLinkerFlags = arg.exportedLinkerFlags;
    output.exportedPlatformLinkerFlags = arg.exportedPlatformLinkerFlags;
    output.soname = Optional.absent();
    output.forceStatic = Optional.of(false);
    output.linkWhole = arg.linkWhole;
    output.supportedPlatformsRegex = arg.supportedPlatformsRegex;
    output.canBeAsset = arg.canBeAsset;
    output.exportedDeps = arg.exportedDeps;
  }

  @VisibleForTesting
  static Function<
      ImmutableList<String>,
      ImmutableList<String>> expandSdkVariableReferencesFunction(
      final AppleSdkPaths appleSdkPaths) {
    return new Function<ImmutableList<String>, ImmutableList<String>>() {
      @Override
      public ImmutableList<String> apply(ImmutableList<String> flags) {
        return FluentIterable
            .from(flags)
            .transform(appleSdkPaths.replaceSourceTreeReferencesFunction())
            .toList();
      }
    };
  }

  public static Optional<AppleAssetCatalog> createBuildRuleForTransitiveAssetCatalogDependencies(
      TargetGraph targetGraph,
      BuildRuleParams params,
      SourcePathResolver sourcePathResolver,
      ApplePlatform applePlatform,
      Tool actool) {
    TargetNode<?> targetNode = targetGraph.get(params.getBuildTarget());

    ImmutableSet<AppleAssetCatalogDescription.Arg> assetCatalogArgs =
        AppleBuildRules.collectRecursiveAssetCatalogs(targetGraph, ImmutableList.of(targetNode));

    ImmutableSortedSet.Builder<SourcePath> assetCatalogDirsBuilder =
        ImmutableSortedSet.naturalOrder();

    for (AppleAssetCatalogDescription.Arg arg : assetCatalogArgs) {
      assetCatalogDirsBuilder.addAll(arg.dirs);
    }

    ImmutableSortedSet<SourcePath> assetCatalogDirs =
        assetCatalogDirsBuilder.build();

    if (assetCatalogDirs.isEmpty()) {
      return Optional.absent();
    }

    BuildRuleParams assetCatalogParams = params.copyWithChanges(
        params.getBuildTarget().withAppendedFlavor(AppleAssetCatalog.FLAVOR),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));

    return Optional.of(
        new AppleAssetCatalog(
            assetCatalogParams,
            sourcePathResolver,
            applePlatform.getName(),
            actool,
            assetCatalogDirs,
            MERGED_ASSET_CATALOG_NAME));
  }

  static AppleDsym createAppleDsym(
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform,
      ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      AppleBundle appleBundle) {
    AppleCxxPlatform appleCxxPlatform = ApplePlatforms.getAppleCxxPlatformForBuildTarget(
        cxxPlatformFlavorDomain,
        defaultCxxPlatform,
        platformFlavorsToAppleCxxPlatforms,
        params.getBuildTarget(),
        FatBinaryInfos.create(platformFlavorsToAppleCxxPlatforms, params.getBuildTarget()));
    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    return new AppleDsym(
        params.copyWithChanges(
            params.getBuildTarget().withAppendedFlavor(APPLE_DSYM),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(appleBundle)),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        sourcePathResolver,
        appleBundle.getBundleRoot(),
        appleBundle.getBundleBinaryPath(),
        appleCxxPlatform.getDsymutil(),
        appleCxxPlatform.getLldb(),
        appleCxxPlatform.getCxxPlatform().getStrip());
  }

  static AppleBundleWithDsym createAppleBundleWithDsym(
      AppleBundle appleBundle,
      AppleDsym appleDsym,
      BuildRuleParams params,
      BuildRuleResolver resolver) {
    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    return new AppleBundleWithDsym(
        params.copyWithChanges(
            params.getBuildTarget().withAppendedFlavor(APPLE_BUNDLE_WITH_DSYM),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(appleDsym)),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        sourcePathResolver,
        appleBundle);
  }

  static AppleBundle createAppleBundle(
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform,
      ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      BuildTarget binary,
      Either<AppleBundleExtension, String> extension,
      Optional<String> productName,
      final SourcePath infoPlist,
      Optional<ImmutableMap<String, String>> infoPlistSubstitutions,
      ImmutableSortedSet<BuildTarget> deps,
      ImmutableSortedSet<BuildTarget> tests)
      throws NoSuchBuildTargetException {
    AppleCxxPlatform appleCxxPlatform = ApplePlatforms.getAppleCxxPlatformForBuildTarget(
        cxxPlatformFlavorDomain, defaultCxxPlatform, platformFlavorsToAppleCxxPlatforms,
        params.getBuildTarget(),
        FatBinaryInfos.create(platformFlavorsToAppleCxxPlatforms, params.getBuildTarget()));

    AppleBundleDestinations destinations =
        AppleBundleDestinations.platformDestinations(
            appleCxxPlatform.getAppleSdk().getApplePlatform());

    ImmutableSet.Builder<SourcePath> bundleDirsBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<SourcePath> dirsContainingResourceDirsBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<SourcePath> bundleFilesBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<SourcePath> bundleVariantFilesBuilder = ImmutableSet.builder();
    AppleResources.collectResourceDirsAndFiles(
        targetGraph,
        targetGraph.get(params.getBuildTarget()),
        bundleDirsBuilder,
        dirsContainingResourceDirsBuilder,
        bundleFilesBuilder,
        bundleVariantFilesBuilder);
    ImmutableSet<SourcePath> bundleDirs = bundleDirsBuilder.build();
    ImmutableSet<SourcePath> dirsContainingResourceDirs = dirsContainingResourceDirsBuilder.build();
    ImmutableSet<SourcePath> bundleFiles = bundleFilesBuilder.build();
    ImmutableSet<SourcePath> bundleVariantFiles = bundleVariantFilesBuilder.build();
    ImmutableSet.Builder<SourcePath> frameworksBuilder = ImmutableSet.builder();
    if (INCLUDE_FRAMEWORKS.getRequiredValue(params.getBuildTarget())) {
      for (BuildTarget dep : deps) {
        Optional<FrameworkDependencies> frameworkDependencies =
            resolver.requireMetadata(
                BuildTarget.builder(dep)
                    .addFlavors(FRAMEWORK_FLAVOR)
                    .addFlavors(NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .addFlavors(appleCxxPlatform.getCxxPlatform().getFlavor())
                    .build(),
                FrameworkDependencies.class);
        if (frameworkDependencies.isPresent()) {
          frameworksBuilder.addAll(frameworkDependencies.get().getSourcePaths());
        }
      }
    }
    ImmutableSet<SourcePath> frameworks = frameworksBuilder.build();

    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);

    Optional<AppleAssetCatalog> assetCatalog =
        createBuildRuleForTransitiveAssetCatalogDependencies(
            targetGraph,
            params,
            sourcePathResolver,
            appleCxxPlatform.getAppleSdk().getApplePlatform(),
            appleCxxPlatform.getActool());

    // TODO(bhamiltoncx): Sort through the changes needed to make project generation work with
    // binary being optional.
    BuildRule flavoredBinaryRule = getFlavoredBinaryRule(
        cxxPlatformFlavorDomain,
        defaultCxxPlatform,
        targetGraph,
        params.getBuildTarget().getFlavors(),
        resolver,
        binary);
    BuildRuleParams bundleParamsWithFlavoredBinaryDep = getBundleParamsWithUpdatedDeps(
        params,
        binary,
        ImmutableSet.<BuildRule>builder()
            .add(flavoredBinaryRule)
            .addAll(assetCatalog.asSet())
            .addAll(
                BuildRules.toBuildRulesFor(
                    params.getBuildTarget(),
                    resolver,
                    SourcePaths.filterBuildTargetSourcePaths(
                        Iterables.concat(
                            ImmutableList.of(
                                bundleFiles,
                                bundleDirs,
                                dirsContainingResourceDirs,
                                bundleVariantFiles,
                                frameworks)))))
            .build());

    ImmutableMap<SourcePath, String> extensionBundlePaths = collectFirstLevelAppleDependencyBundles(
        params.getDeps(),
        destinations);

    return new AppleBundle(
        bundleParamsWithFlavoredBinaryDep,
        sourcePathResolver,
        extension,
        productName,
        infoPlist,
        infoPlistSubstitutions.get(),
        Optional.of(flavoredBinaryRule),
        destinations,
        bundleDirs,
        bundleFiles,
        dirsContainingResourceDirs,
        extensionBundlePaths,
        Optional.of(bundleVariantFiles),
        frameworks,
        appleCxxPlatform,
        assetCatalog,
        tests,
        codeSignIdentityStore,
        provisioningProfileStore);
  }

  private static BuildRule getFlavoredBinaryRule(
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform,
      TargetGraph targetGraph,
      ImmutableSet<Flavor> flavors,
      BuildRuleResolver resolver,
      BuildTarget binary) throws NoSuchBuildTargetException {
    // Cxx targets must have one Platform Flavor set otherwise nothing gets compiled.
    if (flavors.contains(AppleDescriptions.FRAMEWORK_FLAVOR)) {
      flavors = ImmutableSet.<Flavor>builder()
          .addAll(flavors)
          .add(CxxDescriptionEnhancer.SHARED_FLAVOR)
          .build();
    }
    flavors =
        ImmutableSet.copyOf(
            Sets.difference(
                flavors,
                ImmutableSet.of(
                    ReactNativeFlavors.DO_NOT_BUNDLE,
                    AppleDescriptions.FRAMEWORK_FLAVOR,
                    AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
                    AppleDebugFormat.NONE.getFlavor(),
                    AppleBinaryDescription.APP_FLAVOR)));
    if (!cxxPlatformFlavorDomain.containsAnyOf(flavors)) {
      flavors = new ImmutableSet.Builder<Flavor>()
          .addAll(flavors)
          .add(defaultCxxPlatform.getFlavor())
          .build();
    }

    BuildTarget.Builder buildTargetBuilder =
        BuildTarget.builder(binary.getUnflavoredBuildTarget()).addAllFlavors(flavors);
    if (!(AppleLibraryDescription.LIBRARY_TYPE.getFlavor(flavors).isPresent())) {
      buildTargetBuilder.addAllFlavors(binary.getFlavors());
    } else {
      buildTargetBuilder.addAllFlavors(
          Sets.difference(
              binary.getFlavors(),
              AppleLibraryDescription.LIBRARY_TYPE.getFlavors()));
    }
    BuildTarget buildTarget = buildTargetBuilder.build();

    final TargetNode<?> binaryTargetNode = targetGraph.get(buildTarget);
    // If the binary target of the AppleBundle is an AppleLibrary then the build flavor
    // must be specified.
    if (binaryTargetNode.getDescription() instanceof AppleLibraryDescription &&
        (Sets.intersection(
            AppleBundleDescription.SUPPORTED_LIBRARY_FLAVORS,
            buildTarget.getFlavors()).size() != 1)) {
      throw new HumanReadableException(
          "AppleExtension bundle [%s] must have exactly one of these flavors: [%s].",
          binaryTargetNode.getBuildTarget().toString(),
          Joiner.on(", ").join(AppleBundleDescription.SUPPORTED_LIBRARY_FLAVORS));
    }

    return resolver.requireRule(buildTarget);
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

  private static ImmutableMap<SourcePath, String> collectFirstLevelAppleDependencyBundles(
      ImmutableSortedSet<BuildRule> deps,
      AppleBundleDestinations destinations) {
    ImmutableMap.Builder<SourcePath, String> extensionBundlePaths = ImmutableMap.builder();
    // We only care about the direct layer of dependencies. ExtensionBundles inside ExtensionBundles
    // do not get pulled in to the top-level Bundle.
    for (BuildRule rule : deps) {
      if (rule instanceof BuildRuleWithAppleBundle) {
        AppleBundle appleBundle = ((BuildRuleWithAppleBundle) rule).getAppleBundle();
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

          if ((platformName.equals(ApplePlatform.WATCHOS.getName()) ||
              platformName.equals(ApplePlatform.WATCHSIMULATOR.getName())) &&
              appleBundle.getExtension().equals(AppleBundleExtension.APP.toFileExtension())) {
            destinationPath = destinations.getWatchAppPath();
          } else if (appleBundle.isLegacyWatchApp()) {
            destinationPath = destinations.getResourcesPath();
          } else {
            destinationPath = destinations.getPlugInsPath();
          }

          extensionBundlePaths.put(sourcePath, destinationPath.toString());
        }
      }
    }

    return extensionBundlePaths.build();
  }
}
