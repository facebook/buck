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

import static com.facebook.buck.swift.SwiftDescriptions.SWIFT_EXTENSION;

import com.facebook.buck.cxx.BuildRuleWithBinary;
import com.facebook.buck.cxx.CxxBinaryDescriptionArg;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxLibraryDescriptionArg;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.ProvidesLinkedBinaryDeps;
import com.facebook.buck.cxx.StripStyle;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
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
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Common logic for a {@link com.facebook.buck.rules.Description} that creates Apple target rules.
 */
public class AppleDescriptions {

  public static final Flavor FRAMEWORK_FLAVOR = InternalFlavor.of("framework");

  public static final Flavor INCLUDE_FRAMEWORKS_FLAVOR = InternalFlavor.of("include-frameworks");
  public static final Flavor NO_INCLUDE_FRAMEWORKS_FLAVOR =
      InternalFlavor.of("no-include-frameworks");
  public static final FlavorDomain<Boolean> INCLUDE_FRAMEWORKS =
      new FlavorDomain<>(
          "Include frameworks",
          ImmutableMap.of(
              INCLUDE_FRAMEWORKS_FLAVOR, Boolean.TRUE,
              NO_INCLUDE_FRAMEWORKS_FLAVOR, Boolean.FALSE));
  private static final ImmutableSet<Flavor> BUNDLE_SPECIFIC_FLAVORS =
      ImmutableSet.of(INCLUDE_FRAMEWORKS_FLAVOR, NO_INCLUDE_FRAMEWORKS_FLAVOR);

  private static final String MERGED_ASSET_CATALOG_NAME = "Merged";

  /** Utility class: do not instantiate. */
  private AppleDescriptions() {}

  public static Path getHeaderPathPrefix(
      AppleNativeTargetDescriptionArg arg, BuildTarget buildTarget) {
    return Paths.get(arg.getHeaderPathPrefix().orElse(buildTarget.getShortName()));
  }

  public static ImmutableSortedMap<String, SourcePath> convertAppleHeadersToPublicCxxHeaders(
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      CxxLibraryDescription.CommonArg arg) {
    // The exported headers in the populated cxx constructor arg will contain exported headers from
    // the apple constructor arg with the public include style.
    return AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
        pathResolver, headerPathPrefix, arg.getExportedHeaders());
  }

  public static ImmutableSortedMap<String, SourcePath> convertAppleHeadersToPrivateCxxHeaders(
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      CxxLibraryDescription.CommonArg arg) {
    // The private headers will contain exported headers with the private include style and private
    // headers with both styles.
    return ImmutableSortedMap.<String, SourcePath>naturalOrder()
        .putAll(
            AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
                pathResolver, arg.getHeaders()))
        .putAll(
            AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
                pathResolver, headerPathPrefix, arg.getHeaders()))
        .putAll(
            AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
                pathResolver, arg.getExportedHeaders()))
        .build();
  }

  @VisibleForTesting
  static ImmutableSortedMap<String, SourcePath> parseAppleHeadersForUseFromOtherTargets(
      Function<SourcePath, Path> pathResolver, Path headerPathPrefix, SourceList headers) {
    if (headers.getUnnamedSources().isPresent()) {
      // The user specified a set of header files. For use from other targets, prepend their names
      // with the header path prefix.
      return convertToFlatCxxHeaders(
          headerPathPrefix, pathResolver, headers.getUnnamedSources().get());
    } else {
      // The user specified a map from include paths to header files. Just use the specified map.
      return headers.getNamedSources().get();
    }
  }

  @VisibleForTesting
  static ImmutableMap<String, SourcePath> parseAppleHeadersForUseFromTheSameTarget(
      Function<SourcePath, Path> pathResolver, SourceList headers) {
    if (headers.getUnnamedSources().isPresent()) {
      // The user specified a set of header files. Headers can be included from the same target
      // using only their file name without a prefix.
      return convertToFlatCxxHeaders(
          Paths.get(""), pathResolver, headers.getUnnamedSources().get());
    } else {
      // The user specified a map from include paths to header files. There is nothing we need to
      // add on top of the exported headers.
      return ImmutableMap.of();
    }
  }

  /**
   * Convert {@link SourcePath} to a mapping of {@code include path -> file path}.
   *
   * <p>{@code include path} is the path that can be referenced in {@code #include} directives.
   * {@code file path} is the actual path to the file on disk.
   *
   * @throws HumanReadableException when two {@code SourcePath} yields the same IncludePath.
   */
  @VisibleForTesting
  static ImmutableSortedMap<String, SourcePath> convertToFlatCxxHeaders(
      Path headerPathPrefix,
      Function<SourcePath, Path> sourcePathResolver,
      Set<SourcePath> headerPaths) {
    Set<String> includeToFile = new HashSet<String>(headerPaths.size());
    ImmutableSortedMap.Builder<String, SourcePath> builder = ImmutableSortedMap.naturalOrder();
    for (SourcePath headerPath : headerPaths) {
      Path fileName = sourcePathResolver.apply(headerPath).getFileName();
      String key = headerPathPrefix.resolve(fileName).toString();
      if (includeToFile.contains(key)) {
        ImmutableSortedMap<String, SourcePath> result = builder.build();
        throw new HumanReadableException(
            "The same include path maps to multiple files:\n"
                + "  Include path: %s\n"
                + "  Conflicting files:\n"
                + "    %s\n"
                + "    %s",
            key, headerPath, result.get(key));
      }
      includeToFile.add(key);
      builder.put(key, headerPath);
    }
    return builder.build();
  }

  public static void populateCxxConstructorArg(
      SourcePathResolver resolver,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget,
      Consumer<ImmutableSortedSet<SourceWithFlags>> setSrcs,
      Consumer<SourceList> setHeaders,
      Consumer<String> setHeaderNamespace) {
    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(arg, buildTarget);
    // The resulting cxx constructor arg will have no exported headers and both headers and exported
    // headers specified in the apple arg will be available with both public and private include
    // styles.
    ImmutableSortedMap<String, SourcePath> headerMap =
        ImmutableSortedMap.<String, SourcePath>naturalOrder()
            .putAll(
                convertAppleHeadersToPublicCxxHeaders(
                    resolver::getRelativePath, headerPathPrefix, arg))
            .putAll(
                convertAppleHeadersToPrivateCxxHeaders(
                    resolver::getRelativePath, headerPathPrefix, arg))
            .build();

    ImmutableSortedSet.Builder<SourceWithFlags> nonSwiftSrcs = ImmutableSortedSet.naturalOrder();
    for (SourceWithFlags src : arg.getSrcs()) {
      if (!MorePaths.getFileExtension(resolver.getAbsolutePath(src.getSourcePath()))
          .equalsIgnoreCase(SWIFT_EXTENSION)) {
        nonSwiftSrcs.add(src);
      }
    }
    setSrcs.accept(nonSwiftSrcs.build());

    setHeaders.accept(SourceList.ofNamedSources(headerMap));
    // This is intentionally an empty string; we put all prefixes into
    // the header map itself.
    setHeaderNamespace.accept("");
  }

  public static void populateCxxBinaryDescriptionArg(
      SourcePathResolver resolver,
      CxxBinaryDescriptionArg.Builder output,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget) {
    populateCxxConstructorArg(
        resolver,
        arg,
        buildTarget,
        output::setSrcs,
        output::setHeaders,
        output::setHeaderNamespace);
    output.setDefaultPlatform(Optional.empty());
  }

  public static void populateCxxLibraryDescriptionArg(
      SourcePathResolver resolver,
      CxxLibraryDescriptionArg.Builder output,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget) {
    populateCxxConstructorArg(
        resolver,
        arg,
        buildTarget,
        output::setSrcs,
        output::setHeaders,
        output::setHeaderNamespace);
    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(arg, buildTarget);
    output.setHeaders(
        SourceList.ofNamedSources(
            convertAppleHeadersToPrivateCxxHeaders(
                resolver::getRelativePath, headerPathPrefix, arg)));
    output.setExportedHeaders(
        SourceList.ofNamedSources(
            convertAppleHeadersToPublicCxxHeaders(
                resolver::getRelativePath, headerPathPrefix, arg)));
  }

  public static Optional<AppleAssetCatalog> createBuildRuleForTransitiveAssetCatalogDependencies(
      TargetGraph targetGraph,
      BuildRuleParams params,
      SourcePathResolver sourcePathResolver,
      ApplePlatform applePlatform,
      String targetSDKVersion,
      Tool actool) {
    TargetNode<?, ?> targetNode = targetGraph.get(params.getBuildTarget());

    ImmutableSet<AppleAssetCatalogDescriptionArg> assetCatalogArgs =
        AppleBuildRules.collectRecursiveAssetCatalogs(
            targetGraph, Optional.empty(), ImmutableList.of(targetNode));

    ImmutableSortedSet.Builder<SourcePath> assetCatalogDirsBuilder =
        ImmutableSortedSet.naturalOrder();

    Optional<String> appIcon = Optional.empty();
    Optional<String> launchImage = Optional.empty();

    AppleAssetCatalogDescription.Optimization optimization = null;

    for (AppleAssetCatalogDescriptionArg arg : assetCatalogArgs) {
      if (optimization == null) {
        optimization = arg.getOptimization();
      }

      assetCatalogDirsBuilder.addAll(arg.getDirs());
      if (arg.getAppIcon().isPresent()) {
        if (appIcon.isPresent()) {
          throw new HumanReadableException(
              "At most one asset catalog in the dependencies of %s " + "can have a app_icon",
              params.getBuildTarget());
        }

        appIcon = arg.getAppIcon();
      }

      if (arg.getLaunchImage().isPresent()) {
        if (launchImage.isPresent()) {
          throw new HumanReadableException(
              "At most one asset catalog in the dependencies of %s " + "can have a launch_image",
              params.getBuildTarget());
        }

        launchImage = arg.getLaunchImage();
      }

      if (arg.getOptimization() != optimization) {
        throw new HumanReadableException(
            "At most one asset catalog optimisation style can be "
                + "specified in the dependencies %s",
            params.getBuildTarget());
      }
    }

    ImmutableSortedSet<SourcePath> assetCatalogDirs = assetCatalogDirsBuilder.build();

    if (assetCatalogDirs.isEmpty()) {
      return Optional.empty();
    }
    Preconditions.checkNotNull(
        optimization, "optimization was null even though assetCatalogArgs was not empty");

    for (SourcePath assetCatalogDir : assetCatalogDirs) {
      Path baseName = sourcePathResolver.getRelativePath(assetCatalogDir).getFileName();
      if (!baseName.toString().endsWith(".xcassets")) {
        throw new HumanReadableException(
            "Target %s had asset catalog dir %s - asset catalog dirs must end with .xcassets",
            params.getBuildTarget(), assetCatalogDir);
      }
    }

    BuildRuleParams assetCatalogParams =
        params
            .withAppendedFlavor(AppleAssetCatalog.FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.of()),
                Suppliers.ofInstance(ImmutableSortedSet.of()));

    return Optional.of(
        new AppleAssetCatalog(
            assetCatalogParams,
            applePlatform.getName(),
            targetSDKVersion,
            actool,
            assetCatalogDirs,
            appIcon,
            launchImage,
            optimization,
            MERGED_ASSET_CATALOG_NAME));
  }

  public static Optional<CoreDataModel> createBuildRulesForCoreDataDependencies(
      TargetGraph targetGraph,
      BuildRuleParams params,
      String moduleName,
      AppleCxxPlatform appleCxxPlatform) {
    TargetNode<?, ?> targetNode = targetGraph.get(params.getBuildTarget());

    ImmutableSet<AppleWrapperResourceArg> coreDataModelArgs =
        AppleBuildRules.collectTransitiveBuildRules(
            targetGraph,
            Optional.empty(),
            AppleBuildRules.CORE_DATA_MODEL_DESCRIPTION_CLASSES,
            ImmutableList.of(targetNode));

    BuildRuleParams coreDataModelParams =
        params
            .withAppendedFlavor(CoreDataModel.FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.of()),
                Suppliers.ofInstance(ImmutableSortedSet.of()));

    if (coreDataModelArgs.isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.of(
          new CoreDataModel(
              coreDataModelParams,
              appleCxxPlatform,
              moduleName,
              coreDataModelArgs
                  .stream()
                  .map(input -> new PathSourcePath(params.getProjectFilesystem(), input.getPath()))
                  .collect(MoreCollectors.toImmutableSet())));
    }
  }

  public static Optional<SceneKitAssets> createBuildRulesForSceneKitAssetsDependencies(
      TargetGraph targetGraph, BuildRuleParams params, AppleCxxPlatform appleCxxPlatform) {
    TargetNode<?, ?> targetNode = targetGraph.get(params.getBuildTarget());

    ImmutableSet<AppleWrapperResourceArg> sceneKitAssetsArgs =
        AppleBuildRules.collectTransitiveBuildRules(
            targetGraph,
            Optional.empty(),
            AppleBuildRules.SCENEKIT_ASSETS_DESCRIPTION_CLASSES,
            ImmutableList.of(targetNode));

    BuildRuleParams sceneKitAssetsParams =
        params
            .withAppendedFlavor(SceneKitAssets.FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.of()),
                Suppliers.ofInstance(ImmutableSortedSet.of()));

    if (sceneKitAssetsArgs.isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.of(
          new SceneKitAssets(
              sceneKitAssetsParams,
              appleCxxPlatform,
              sceneKitAssetsArgs
                  .stream()
                  .map(input -> new PathSourcePath(params.getProjectFilesystem(), input.getPath()))
                  .collect(MoreCollectors.toImmutableSet())));
    }
  }

  static AppleDebuggableBinary createAppleDebuggableBinary(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      BuildRule strippedBinaryRule,
      ProvidesLinkedBinaryDeps unstrippedBinaryRule,
      AppleDebugFormat debugFormat,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatforms) {
    Optional<AppleDsym> appleDsym =
        createAppleDsymForDebugFormat(
            debugFormat,
            params,
            resolver,
            unstrippedBinaryRule,
            cxxPlatformFlavorDomain,
            defaultCxxPlatform,
            appleCxxPlatforms);
    BuildRule buildRuleForDebugFormat;
    if (debugFormat == AppleDebugFormat.DWARF) {
      buildRuleForDebugFormat = unstrippedBinaryRule;
    } else {
      buildRuleForDebugFormat = strippedBinaryRule;
    }
    AppleDebuggableBinary rule =
        new AppleDebuggableBinary(
            params
                .withBuildTarget(
                    strippedBinaryRule
                        .getBuildTarget()
                        .withAppendedFlavors(
                            AppleDebuggableBinary.RULE_FLAVOR, debugFormat.getFlavor()))
                .copyReplacingDeclaredAndExtraDeps(
                    Suppliers.ofInstance(
                        AppleDebuggableBinary.getRequiredRuntimeDeps(
                            debugFormat, strippedBinaryRule, unstrippedBinaryRule, appleDsym)),
                    Suppliers.ofInstance(ImmutableSortedSet.of())),
            buildRuleForDebugFormat);
    return rule;
  }

  private static Optional<AppleDsym> createAppleDsymForDebugFormat(
      AppleDebugFormat debugFormat,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ProvidesLinkedBinaryDeps unstrippedBinaryRule,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatforms) {
    if (debugFormat == AppleDebugFormat.DWARF_AND_DSYM) {
      BuildTarget dsymBuildTarget =
          params
              .getBuildTarget()
              .withoutFlavors(CxxStrip.RULE_FLAVOR)
              .withoutFlavors(StripStyle.FLAVOR_DOMAIN.getFlavors())
              .withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors())
              .withoutFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor())
              .withAppendedFlavors(AppleDsym.RULE_FLAVOR);
      Optional<BuildRule> dsymRule = resolver.getRuleOptional(dsymBuildTarget);
      if (!dsymRule.isPresent()) {
        dsymRule =
            Optional.of(
                createAppleDsym(
                    params.withBuildTarget(dsymBuildTarget),
                    resolver,
                    unstrippedBinaryRule,
                    cxxPlatformFlavorDomain,
                    defaultCxxPlatform,
                    appleCxxPlatforms));
      }
      Preconditions.checkArgument(dsymRule.get() instanceof AppleDsym);
      return Optional.of((AppleDsym) dsymRule.get());
    }
    return Optional.empty();
  }

  static AppleDsym createAppleDsym(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ProvidesLinkedBinaryDeps unstrippedBinaryBuildRule,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatforms) {

    AppleCxxPlatform appleCxxPlatform =
        ApplePlatforms.getAppleCxxPlatformForBuildTarget(
            cxxPlatformFlavorDomain,
            defaultCxxPlatform,
            appleCxxPlatforms,
            unstrippedBinaryBuildRule.getBuildTarget(),
            MultiarchFileInfos.create(
                appleCxxPlatforms, unstrippedBinaryBuildRule.getBuildTarget()));

    AppleDsym appleDsym =
        new AppleDsym(
            params.copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .add(unstrippedBinaryBuildRule)
                        .addAll(unstrippedBinaryBuildRule.getCompileDeps())
                        .addAll(unstrippedBinaryBuildRule.getStaticLibraryDeps())
                        .build()),
                Suppliers.ofInstance(ImmutableSortedSet.of())),
            appleCxxPlatform.getDsymutil(),
            appleCxxPlatform.getLldb(),
            unstrippedBinaryBuildRule.getSourcePathToOutput(),
            AppleDsym.getDsymOutputPath(params.getBuildTarget(), params.getProjectFilesystem()));
    resolver.addToIndex(appleDsym);
    return appleDsym;
  }

  static AppleBundle createAppleBundle(
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatforms,
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      BuildTarget binary,
      Either<AppleBundleExtension, String> extension,
      Optional<String> productName,
      final SourcePath infoPlist,
      ImmutableMap<String, String> infoPlistSubstitutions,
      ImmutableSortedSet<BuildTarget> deps,
      ImmutableSortedSet<BuildTarget> tests,
      AppleDebugFormat debugFormat,
      boolean dryRunCodeSigning,
      boolean cacheable)
      throws NoSuchBuildTargetException {
    AppleCxxPlatform appleCxxPlatform =
        ApplePlatforms.getAppleCxxPlatformForBuildTarget(
            cxxPlatformFlavorDomain,
            defaultCxxPlatform,
            appleCxxPlatforms,
            params.getBuildTarget(),
            MultiarchFileInfos.create(appleCxxPlatforms, params.getBuildTarget()));

    AppleBundleDestinations destinations;

    if (extension.isLeft() && extension.getLeft().equals(AppleBundleExtension.FRAMEWORK)) {
      destinations =
          AppleBundleDestinations.platformFrameworkDestinations(
              appleCxxPlatform.getAppleSdk().getApplePlatform());
    } else {
      destinations =
          AppleBundleDestinations.platformDestinations(
              appleCxxPlatform.getAppleSdk().getApplePlatform());
    }

    AppleBundleResources collectedResources =
        AppleResources.collectResourceDirsAndFiles(
            targetGraph, resolver, Optional.empty(), targetGraph.get(params.getBuildTarget()));

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
    // TODO(17155714): framework embedding is currently oddly entwined with framework generation.
    // This change simply treats all the immediate prebuilt framework dependencies as wishing to be
    // embedded, but in the future this should be dealt with with some greater sophistication.
    for (BuildTarget dep : deps) {
      Optional<TargetNode<PrebuiltAppleFrameworkDescriptionArg, ?>> prebuiltNode =
          targetGraph
              .getOptional(dep)
              .flatMap(node -> node.castArg(PrebuiltAppleFrameworkDescriptionArg.class));
      if (prebuiltNode.isPresent()
          && !prebuiltNode
              .get()
              .getConstructorArg()
              .getPreferredLinkage()
              .equals(NativeLinkable.Linkage.STATIC)) {
        frameworksBuilder.add(resolver.requireRule(dep).getSourcePathToOutput());
      }
    }
    ImmutableSet<SourcePath> frameworks = frameworksBuilder.build();

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);
    BuildRuleParams paramsWithoutBundleSpecificFlavors = stripBundleSpecificFlavors(params);

    Optional<AppleAssetCatalog> assetCatalog =
        createBuildRuleForTransitiveAssetCatalogDependencies(
            targetGraph,
            paramsWithoutBundleSpecificFlavors,
            sourcePathResolver,
            appleCxxPlatform.getAppleSdk().getApplePlatform(),
            appleCxxPlatform.getMinVersion(),
            appleCxxPlatform.getActool());
    addToIndex(resolver, assetCatalog);

    Optional<CoreDataModel> coreDataModel =
        createBuildRulesForCoreDataDependencies(
            targetGraph,
            paramsWithoutBundleSpecificFlavors,
            AppleBundle.getBinaryName(params.getBuildTarget(), productName),
            appleCxxPlatform);
    addToIndex(resolver, coreDataModel);

    Optional<SceneKitAssets> sceneKitAssets =
        createBuildRulesForSceneKitAssetsDependencies(
            targetGraph, paramsWithoutBundleSpecificFlavors, appleCxxPlatform);
    addToIndex(resolver, sceneKitAssets);

    // TODO(beng): Sort through the changes needed to make project generation work with
    // binary being optional.
    BuildRule flavoredBinaryRule =
        getFlavoredBinaryRule(
            cxxPlatformFlavorDomain,
            defaultCxxPlatform,
            targetGraph,
            paramsWithoutBundleSpecificFlavors.getBuildTarget().getFlavors(),
            resolver,
            binary);

    if (!AppleDebuggableBinary.isBuildRuleDebuggable(flavoredBinaryRule)) {
      debugFormat = AppleDebugFormat.NONE;
    }
    BuildTarget unstrippedTarget =
        flavoredBinaryRule
            .getBuildTarget()
            .withoutFlavors(
                CxxStrip.RULE_FLAVOR,
                AppleDebuggableBinary.RULE_FLAVOR,
                AppleBinaryDescription.APP_FLAVOR)
            .withoutFlavors(StripStyle.FLAVOR_DOMAIN.getFlavors())
            .withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors())
            .withoutFlavors(AppleDebuggableBinary.RULE_FLAVOR)
            .withoutFlavors(ImmutableSet.of(AppleBinaryDescription.APP_FLAVOR));
    Optional<LinkerMapMode> linkerMapMode =
        LinkerMapMode.FLAVOR_DOMAIN.getValue(params.getBuildTarget());
    if (linkerMapMode.isPresent()) {
      unstrippedTarget = unstrippedTarget.withAppendedFlavors(linkerMapMode.get().getFlavor());
    }
    BuildRule unstrippedBinaryRule = resolver.requireRule(unstrippedTarget);

    BuildRule targetDebuggableBinaryRule;
    Optional<AppleDsym> appleDsym;
    if (unstrippedBinaryRule instanceof ProvidesLinkedBinaryDeps) {
      BuildTarget binaryBuildTarget =
          getBinaryFromBuildRuleWithBinary(flavoredBinaryRule)
              .getBuildTarget()
              .withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors());
      BuildRuleParams binaryParams = params.withBuildTarget(binaryBuildTarget);
      targetDebuggableBinaryRule =
          createAppleDebuggableBinary(
              binaryParams,
              resolver,
              getBinaryFromBuildRuleWithBinary(flavoredBinaryRule),
              (ProvidesLinkedBinaryDeps) unstrippedBinaryRule,
              debugFormat,
              cxxPlatformFlavorDomain,
              defaultCxxPlatform,
              appleCxxPlatforms);
      appleDsym =
          createAppleDsymForDebugFormat(
              debugFormat,
              binaryParams,
              resolver,
              (ProvidesLinkedBinaryDeps) unstrippedBinaryRule,
              cxxPlatformFlavorDomain,
              defaultCxxPlatform,
              appleCxxPlatforms);
    } else {
      targetDebuggableBinaryRule = unstrippedBinaryRule;
      appleDsym = Optional.empty();
    }

    BuildRuleParams bundleParamsWithFlavoredBinaryDep =
        getBundleParamsWithUpdatedDeps(
            params,
            binary,
            ImmutableSet.<BuildRule>builder()
                .add(targetDebuggableBinaryRule)
                .addAll(OptionalCompat.asSet(assetCatalog))
                .addAll(OptionalCompat.asSet(coreDataModel))
                .addAll(OptionalCompat.asSet(sceneKitAssets))
                .addAll(
                    BuildRules.toBuildRulesFor(
                        params.getBuildTarget(),
                        resolver,
                        RichStream.from(collectedResources.getAll())
                            .concat(frameworks.stream())
                            .filter(BuildTargetSourcePath.class)
                            .map(BuildTargetSourcePath::getTarget)
                            .collect(MoreCollectors.toImmutableSet())))
                .addAll(OptionalCompat.asSet(appleDsym))
                .build());

    ImmutableMap<SourcePath, String> extensionBundlePaths =
        collectFirstLevelAppleDependencyBundles(params.getBuildDeps(), destinations);

    return new AppleBundle(
        bundleParamsWithFlavoredBinaryDep,
        resolver,
        extension,
        productName,
        infoPlist,
        infoPlistSubstitutions,
        Optional.of(getBinaryFromBuildRuleWithBinary(flavoredBinaryRule)),
        appleDsym,
        destinations,
        collectedResources,
        extensionBundlePaths,
        frameworks,
        appleCxxPlatform,
        assetCatalog,
        coreDataModel,
        sceneKitAssets,
        tests,
        codeSignIdentityStore,
        provisioningProfileStore,
        dryRunCodeSigning,
        cacheable);
  }

  private static void addToIndex(BuildRuleResolver resolver, Optional<? extends BuildRule> rule) {
    if (rule.isPresent()) {
      resolver.addToIndex(rule.get());
    }
  }

  private static BuildRule getBinaryFromBuildRuleWithBinary(BuildRule rule) {
    if (rule instanceof BuildRuleWithBinary) {
      rule = ((BuildRuleWithBinary) rule).getBinaryBuildRule();
    }
    return rule;
  }

  private static BuildRule getFlavoredBinaryRule(
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform,
      TargetGraph targetGraph,
      ImmutableSet<Flavor> flavors,
      BuildRuleResolver resolver,
      BuildTarget binary)
      throws NoSuchBuildTargetException {

    // Don't flavor genrule deps.
    if (targetGraph.get(binary).getDescription() instanceof AbstractGenruleDescription) {
      return resolver.requireRule(binary);
    }

    // Cxx targets must have one Platform Flavor set otherwise nothing gets compiled.
    if (flavors.contains(AppleDescriptions.FRAMEWORK_FLAVOR)) {
      flavors =
          ImmutableSet.<Flavor>builder()
              .addAll(flavors)
              .add(CxxDescriptionEnhancer.SHARED_FLAVOR)
              .build();
    }
    flavors =
        ImmutableSet.copyOf(
            Sets.difference(
                flavors,
                ImmutableSet.of(
                    AppleDescriptions.FRAMEWORK_FLAVOR, AppleBinaryDescription.APP_FLAVOR)));
    if (!cxxPlatformFlavorDomain.containsAnyOf(flavors)) {
      flavors =
          new ImmutableSet.Builder<Flavor>()
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
          Sets.difference(binary.getFlavors(), AppleLibraryDescription.LIBRARY_TYPE.getFlavors()));
    }
    BuildTarget buildTarget = buildTargetBuilder.build();

    final TargetNode<?, ?> binaryTargetNode = targetGraph.get(buildTarget);

    if (binaryTargetNode.getDescription() instanceof AppleTestDescription) {
      return resolver.getRule(binary);
    }

    // If the binary target of the AppleBundle is an AppleLibrary then the build flavor
    // must be specified.
    if (binaryTargetNode.getDescription() instanceof AppleLibraryDescription
        && (Sets.intersection(
                    AppleBundleDescription.SUPPORTED_LIBRARY_FLAVORS, buildTarget.getFlavors())
                .size()
            != 1)) {
      throw new HumanReadableException(
          "AppleExtension bundle [%s] must have exactly one of these flavors: [%s].",
          binaryTargetNode.getBuildTarget().toString(),
          Joiner.on(", ").join(AppleBundleDescription.SUPPORTED_LIBRARY_FLAVORS));
    }

    if (!StripStyle.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors())) {
      buildTarget = buildTarget.withAppendedFlavors(StripStyle.NON_GLOBAL_SYMBOLS.getFlavor());
    }

    return resolver.requireRule(buildTarget);
  }

  private static BuildRuleParams getBundleParamsWithUpdatedDeps(
      final BuildRuleParams params,
      final BuildTarget originalBinaryTarget,
      final Set<BuildRule> newDeps) {
    // Remove the unflavored binary rule and add the flavored one instead.
    final Predicate<BuildRule> notOriginalBinaryRule =
        Predicates.not(BuildRules.isBuildRuleWithTarget(originalBinaryTarget));
    return params.copyReplacingDeclaredAndExtraDeps(
        Suppliers.ofInstance(
            FluentIterable.from(params.getDeclaredDeps().get())
                .filter(notOriginalBinaryRule)
                .append(newDeps)
                .toSortedSet(Ordering.natural())),
        Suppliers.ofInstance(
            FluentIterable.from(params.getExtraDeps().get())
                .filter(notOriginalBinaryRule)
                .toSortedSet(Ordering.natural())));
  }

  private static ImmutableMap<SourcePath, String> collectFirstLevelAppleDependencyBundles(
      ImmutableSortedSet<BuildRule> deps, AppleBundleDestinations destinations) {
    ImmutableMap.Builder<SourcePath, String> extensionBundlePaths = ImmutableMap.builder();
    // We only care about the direct layer of dependencies. ExtensionBundles inside ExtensionBundles
    // do not get pulled in to the top-level Bundle.
    for (BuildRule rule : deps) {
      if (rule instanceof AppleBundle) {
        AppleBundle appleBundle = (AppleBundle) rule;
        SourcePath sourcePath =
            Preconditions.checkNotNull(
                appleBundle.getSourcePathToOutput(),
                "Path cannot be null for AppleBundle [%s].",
                appleBundle);

        if (AppleBundleExtension.APPEX.toFileExtension().equals(appleBundle.getExtension())
            || AppleBundleExtension.APP.toFileExtension().equals(appleBundle.getExtension())) {
          Path destinationPath;

          String platformName = appleBundle.getPlatformName();

          if ((platformName.equals(ApplePlatform.WATCHOS.getName())
                  || platformName.equals(ApplePlatform.WATCHSIMULATOR.getName()))
              && appleBundle.getExtension().equals(AppleBundleExtension.APP.toFileExtension())) {
            destinationPath = destinations.getWatchAppPath();
          } else if (appleBundle.isLegacyWatchApp()) {
            destinationPath = destinations.getResourcesPath();
          } else {
            destinationPath = destinations.getPlugInsPath();
          }

          extensionBundlePaths.put(sourcePath, destinationPath.toString());
        } else if (AppleBundleExtension.FRAMEWORK
            .toFileExtension()
            .equals(appleBundle.getExtension())) {
          extensionBundlePaths.put(sourcePath, destinations.getFrameworksPath().toString());
        }
      }
    }

    return extensionBundlePaths.build();
  }

  /**
   * Strip flavors that only apply to a bundle from build targets that are passed to constituent
   * rules of the bundle, such as its associated binary, asset catalog, etc.
   */
  private static BuildRuleParams stripBundleSpecificFlavors(BuildRuleParams params) {
    return params.withBuildTarget(params.getBuildTarget().withoutFlavors(BUNDLE_SPECIFIC_FLAVORS));
  }

  public static boolean flavorsDoNotAllowLinkerMapMode(BuildRuleParams params) {
    ImmutableSet<Flavor> flavors = params.getBuildTarget().getFlavors();
    return flavors.contains(CxxCompilationDatabase.COMPILATION_DATABASE)
        || flavors.contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)
        || flavors.contains(CxxDescriptionEnhancer.STATIC_FLAVOR)
        || flavors.contains(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR)
        || flavors.contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
        || flavors.contains(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR);
  }
}
