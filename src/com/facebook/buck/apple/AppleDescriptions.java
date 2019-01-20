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

import static com.facebook.buck.apple.AppleAssetCatalog.validateAssetCatalogs;
import static com.facebook.buck.swift.SwiftDescriptions.SWIFT_EXTENSION;

import com.facebook.buck.apple.AppleAssetCatalog.ValidationType;
import com.facebook.buck.apple.AppleBuildRules.RecursiveDependenciesMode;
import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildRules;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.CxxBinaryDescriptionArg;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxLibraryDescriptionArg;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.cxx.HasAppleDebugSymbolDeps;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Either;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
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
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Common logic for a {@link DescriptionWithTargetGraph} that creates Apple target rules. */
public class AppleDescriptions {

  public static final Flavor FRAMEWORK_FLAVOR = InternalFlavor.of("framework");
  public static final Flavor SWIFT_COMPILE_FLAVOR = InternalFlavor.of("apple-swift-compile");
  public static final Flavor SWIFT_EXPORTED_OBJC_GENERATED_HEADER_SYMLINK_TREE_FLAVOR =
      InternalFlavor.of("apple-swift-objc-generated-header");
  public static final Flavor SWIFT_OBJC_GENERATED_HEADER_SYMLINK_TREE_FLAVOR =
      InternalFlavor.of("apple-swift-private-objc-generated-header");
  public static final Flavor SWIFT_UNDERLYING_MODULE_FLAVOR =
      InternalFlavor.of("apple-swift-underlying-module");

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
      BuildTarget buildTarget,
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      CxxLibraryDescription.CommonArg arg) {
    // The exported headers in the populated cxx constructor arg will contain exported headers from
    // the apple constructor arg with the public include style.
    return AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
        buildTarget, pathResolver, headerPathPrefix, arg.getExportedHeaders());
  }

  /** @returns Apple headers converted to public cxx headers */
  public static ImmutableSortedMap<String, SourcePath> convertHeadersToPublicCxxHeaders(
      BuildTarget buildTarget,
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      SourceSortedSet sourceSet) {
    // The exported headers in the populated cxx constructor arg will contain exported headers from
    // the apple constructor arg with the public include style.
    return AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
        buildTarget, pathResolver, headerPathPrefix, sourceSet);
  }

  public static ImmutableSortedMap<String, SourcePath> convertAppleHeadersToPrivateCxxHeaders(
      BuildTarget buildTarget,
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      CxxLibraryDescription.CommonArg arg) {
    // The private headers will contain exported headers with the private include style and private
    // headers with both styles.
    ImmutableSortedMap.Builder<String, SourcePath> headersMapBuilder =
        ImmutableSortedMap.<String, SourcePath>naturalOrder()
            .putAll(
                Stream.of(
                        AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
                                buildTarget, pathResolver, arg.getHeaders())
                            .entrySet()
                            .stream(),
                        AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
                                buildTarget, pathResolver, arg.getExportedHeaders())
                            .entrySet()
                            .stream(),
                        AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
                                buildTarget, pathResolver, headerPathPrefix, arg.getHeaders())
                            .entrySet()
                            .stream())
                    .reduce(Stream::concat)
                    .orElse(Stream.empty())
                    .distinct() // allow duplicate entries as long as they map to the same path
                    .collect(
                        ImmutableSortedMap.toImmutableSortedMap(
                            Ordering.natural(), Map.Entry::getKey, Map.Entry::getValue)));

    return headersMapBuilder.build();
  }

  /** @returns Apple headers converted to private cxx headers */
  public static ImmutableSortedMap<String, SourcePath> convertHeadersToPrivateCxxHeaders(
      BuildTarget buildTarget,
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      SourceSortedSet privateSourceSet,
      SourceSortedSet publicSourceSet) {
    // The private headers should contain exported headers with the private include style and
    // private
    // headers with both styles.
    ImmutableSortedMap.Builder<String, SourcePath> headersMapBuilder =
        ImmutableSortedMap.<String, SourcePath>naturalOrder()
            .putAll(
                Stream.of(
                        AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
                                buildTarget, pathResolver, privateSourceSet)
                            .entrySet()
                            .stream(),
                        AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
                                buildTarget, pathResolver, publicSourceSet)
                            .entrySet()
                            .stream(),
                        AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
                                buildTarget, pathResolver, headerPathPrefix, privateSourceSet)
                            .entrySet()
                            .stream())
                    .reduce(Stream::concat)
                    .orElse(Stream.empty())
                    .distinct() // allow duplicate entries as long as they map to the same path
                    .collect(
                        ImmutableSortedMap.toImmutableSortedMap(
                            Ordering.natural(), Map.Entry::getKey, Map.Entry::getValue)));

    return headersMapBuilder.build();
  }

  @VisibleForTesting
  static ImmutableSortedMap<String, SourcePath> parseAppleHeadersForUseFromOtherTargets(
      BuildTarget buildTarget,
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      SourceSortedSet headers) {
    if (headers.getUnnamedSources().isPresent()) {
      // The user specified a set of header files. For use from other targets, prepend their names
      // with the header path prefix.
      return convertToFlatCxxHeaders(
          buildTarget, headerPathPrefix, pathResolver, headers.getUnnamedSources().get());
    } else {
      // The user specified a map from include paths to header files. Just use the specified map.
      return headers.getNamedSources().get();
    }
  }

  @VisibleForTesting
  static ImmutableSortedMap<String, SourcePath> parseAppleHeadersForUseFromTheSameTarget(
      BuildTarget buildTarget, Function<SourcePath, Path> pathResolver, SourceSortedSet headers) {
    if (headers.getUnnamedSources().isPresent()) {
      // The user specified a set of header files. Headers can be included from the same target
      // using only their file name without a prefix.
      return convertToFlatCxxHeaders(
          buildTarget, Paths.get(""), pathResolver, headers.getUnnamedSources().get());
    } else {
      // The user specified a map from include paths to header files. There is nothing we need to
      // add on top of the exported headers.
      return ImmutableSortedMap.of();
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
      BuildTarget buildTarget,
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
            "In target '%s', '%s' maps to the following header files:\n"
                + "- %s\n"
                + "- %s\n\n"
                + "Please rename one of them or export one of them to a different path.",
            buildTarget, key, headerPath, result.get(key));
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
      Consumer<SourceSortedSet> setHeaders,
      Consumer<String> setHeaderNamespace) {
    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(arg, buildTarget);
    // The resulting cxx constructor arg will have no exported headers and both headers and exported
    // headers specified in the apple arg will be available with both public and private include
    // styles.
    ImmutableSortedMap<String, SourcePath> headerMap =
        ImmutableSortedMap.<String, SourcePath>naturalOrder()
            .putAll(
                Stream.concat(
                        convertAppleHeadersToPublicCxxHeaders(
                                buildTarget, resolver::getRelativePath, headerPathPrefix, arg)
                            .entrySet()
                            .stream(),
                        convertAppleHeadersToPrivateCxxHeaders(
                                buildTarget, resolver::getRelativePath, headerPathPrefix, arg)
                            .entrySet()
                            .stream())
                    .distinct() // allow duplicate entries as long as they map to the same path
                    .collect(
                        ImmutableSortedMap.toImmutableSortedMap(
                            Ordering.natural(), Map.Entry::getKey, Map.Entry::getValue)))
            .build();

    ImmutableSortedSet.Builder<SourceWithFlags> nonSwiftSrcs = ImmutableSortedSet.naturalOrder();
    for (SourceWithFlags src : arg.getSrcs()) {
      if (!MorePaths.getFileExtension(resolver.getAbsolutePath(src.getSourcePath()))
          .equalsIgnoreCase(SWIFT_EXTENSION)) {
        nonSwiftSrcs.add(src);
      }
    }
    setSrcs.accept(nonSwiftSrcs.build());

    setHeaders.accept(SourceSortedSet.ofNamedSources(headerMap));
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
        SourceSortedSet.ofNamedSources(
            convertAppleHeadersToPrivateCxxHeaders(
                buildTarget, resolver::getRelativePath, headerPathPrefix, arg)));
    output.setExportedHeaders(
        SourceSortedSet.ofNamedSources(
            convertAppleHeadersToPublicCxxHeaders(
                buildTarget, resolver::getRelativePath, headerPathPrefix, arg)));
    if (arg.isModular()) {
      output.addCompilerFlags(
          StringWithMacros.of(
              ImmutableList.of(
                  Either.ofLeft(
                      "-fmodule-name="
                          + arg.getHeaderPathPrefix().orElse(buildTarget.getShortName())))));
    }
  }

  public static Optional<AppleAssetCatalog> createBuildRuleForTransitiveAssetCatalogDependencies(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      ApplePlatform applePlatform,
      String targetSDKVersion,
      Tool actool,
      AppleAssetCatalog.ValidationType assetCatalogValidation,
      AppleAssetCatalogsCompilationOptions appleAssetCatalogsCompilationOptions) {
    TargetNode<?> targetNode = targetGraph.get(buildTarget);

    ImmutableSet<AppleAssetCatalogDescriptionArg> assetCatalogArgs =
        AppleBuildRules.collectRecursiveAssetCatalogs(
            xcodeDescriptions,
            targetGraph,
            Optional.empty(),
            ImmutableList.of(targetNode),
            RecursiveDependenciesMode.COPYING);

    ImmutableSortedSet.Builder<SourcePath> assetCatalogDirsBuilder =
        ImmutableSortedSet.naturalOrder();

    Optional<String> appIcon = Optional.empty();
    Optional<String> launchImage = Optional.empty();

    for (AppleAssetCatalogDescriptionArg arg : assetCatalogArgs) {
      assetCatalogDirsBuilder.addAll(arg.getDirs());
      if (arg.getAppIcon().isPresent()) {
        if (appIcon.isPresent()) {
          throw new HumanReadableException(
              "At most one asset catalog in the dependencies of %s " + "can have a app_icon",
              buildTarget);
        }

        appIcon = arg.getAppIcon();
      }

      if (arg.getLaunchImage().isPresent()) {
        if (launchImage.isPresent()) {
          throw new HumanReadableException(
              "At most one asset catalog in the dependencies of %s " + "can have a launch_image",
              buildTarget);
        }

        launchImage = arg.getLaunchImage();
      }
    }

    ImmutableSortedSet<SourcePath> assetCatalogDirs = assetCatalogDirsBuilder.build();

    if (assetCatalogDirs.isEmpty()) {
      return Optional.empty();
    }

    validateAssetCatalogs(
        assetCatalogDirs,
        buildTarget,
        projectFilesystem,
        sourcePathResolver,
        assetCatalogValidation);

    BuildTarget assetCatalogBuildTarget = buildTarget.withAppendedFlavors(AppleAssetCatalog.FLAVOR);
    if (buildTarget.getFlavors().contains(AppleBinaryDescription.LEGACY_WATCH_FLAVOR)) {
      // If the target is a legacy watch target, we need to provide the watchos platform to
      // the AppleAssetCatalog for it to generate assets in a format that's for watchos.
      applePlatform = ApplePlatform.WATCHOS;
      targetSDKVersion = "1.0";
    }
    return Optional.of(
        new AppleAssetCatalog(
            assetCatalogBuildTarget,
            projectFilesystem,
            ruleFinder,
            applePlatform.getName(),
            targetSDKVersion,
            actool,
            assetCatalogDirs,
            appIcon,
            launchImage,
            appleAssetCatalogsCompilationOptions,
            MERGED_ASSET_CATALOG_NAME));
  }

  public static Optional<CoreDataModel> createBuildRulesForCoreDataDependencies(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      String moduleName,
      AppleCxxPlatform appleCxxPlatform) {
    TargetNode<?> targetNode = targetGraph.get(buildTarget);

    ImmutableSet<AppleWrapperResourceArg> coreDataModelArgs =
        AppleBuildRules.collectTransitiveBuildRules(
            xcodeDescriptions,
            targetGraph,
            Optional.empty(),
            AppleBuildRules.CORE_DATA_MODEL_DESCRIPTION_CLASSES,
            ImmutableList.of(targetNode),
            RecursiveDependenciesMode.COPYING);

    BuildTarget coreDataModelBuildTarget = buildTarget.withAppendedFlavors(CoreDataModel.FLAVOR);

    if (coreDataModelArgs.isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.of(
          new CoreDataModel(
              coreDataModelBuildTarget,
              projectFilesystem,
              params.withoutDeclaredDeps().withoutExtraDeps(),
              appleCxxPlatform,
              moduleName,
              coreDataModelArgs
                  .stream()
                  .map(input -> PathSourcePath.of(projectFilesystem, input.getPath()))
                  .collect(ImmutableSet.toImmutableSet())));
    }
  }

  public static Optional<SceneKitAssets> createBuildRulesForSceneKitAssetsDependencies(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      AppleCxxPlatform appleCxxPlatform) {
    TargetNode<?> targetNode = targetGraph.get(buildTarget);

    ImmutableSet<AppleWrapperResourceArg> sceneKitAssetsArgs =
        AppleBuildRules.collectTransitiveBuildRules(
            xcodeDescriptions,
            targetGraph,
            Optional.empty(),
            AppleBuildRules.SCENEKIT_ASSETS_DESCRIPTION_CLASSES,
            ImmutableList.of(targetNode),
            RecursiveDependenciesMode.COPYING);

    BuildTarget sceneKitAssetsBuildTarget = buildTarget.withAppendedFlavors(SceneKitAssets.FLAVOR);

    if (sceneKitAssetsArgs.isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.of(
          new SceneKitAssets(
              sceneKitAssetsBuildTarget,
              projectFilesystem,
              params.withoutDeclaredDeps().withoutExtraDeps(),
              appleCxxPlatform,
              sceneKitAssetsArgs
                  .stream()
                  .map(input -> PathSourcePath.of(projectFilesystem, input.getPath()))
                  .collect(ImmutableSet.toImmutableSet())));
    }
  }

  static AppleDebuggableBinary createAppleDebuggableBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      BuildRule strippedBinaryRule,
      HasAppleDebugSymbolDeps unstrippedBinaryRule,
      AppleDebugFormat debugFormat,
      CxxPlatformsProvider cxxPlatformsProvider,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatforms,
      boolean shouldCacheStrips) {
    // Target used as the base target of AppleDebuggableBinary.

    BuildTarget baseTarget = unstrippedBinaryRule.getBuildTarget();
    switch (debugFormat) {
      case DWARF:
        return AppleDebuggableBinary.createFromUnstrippedBinary(
            projectFilesystem, baseTarget, unstrippedBinaryRule);
      case DWARF_AND_DSYM:
        AppleDsym dsym =
            requireAppleDsym(
                buildTarget,
                projectFilesystem,
                graphBuilder,
                unstrippedBinaryRule,
                cxxPlatformsProvider,
                appleCxxPlatforms,
                shouldCacheStrips);
        return AppleDebuggableBinary.createWithDsym(
            projectFilesystem, baseTarget, strippedBinaryRule, dsym);
      case NONE:
        return AppleDebuggableBinary.createWithoutDebugging(
            projectFilesystem, baseTarget, strippedBinaryRule);
    }
    throw new IllegalStateException("Unhandled debugFormat");
  }

  private static AppleDsym requireAppleDsym(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      HasAppleDebugSymbolDeps unstrippedBinaryRule,
      CxxPlatformsProvider cxxPlatformsProvider,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatforms,
      boolean isCacheable) {
    return (AppleDsym)
        graphBuilder.computeIfAbsent(
            buildTarget
                .withoutFlavors(CxxStrip.RULE_FLAVOR)
                .withoutFlavors(StripStyle.FLAVOR_DOMAIN.getFlavors())
                .withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors())
                .withoutFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor())
                .withAppendedFlavors(AppleDsym.RULE_FLAVOR),
            dsymBuildTarget -> {
              AppleCxxPlatform appleCxxPlatform =
                  ApplePlatforms.getAppleCxxPlatformForBuildTarget(
                      cxxPlatformsProvider,
                      appleCxxPlatforms,
                      unstrippedBinaryRule.getBuildTarget(),
                      MultiarchFileInfos.create(
                          appleCxxPlatforms, unstrippedBinaryRule.getBuildTarget()));
              return new AppleDsym(
                  dsymBuildTarget,
                  projectFilesystem,
                  new SourcePathRuleFinder(graphBuilder),
                  appleCxxPlatform.getDsymutil(),
                  appleCxxPlatform.getLldb(),
                  unstrippedBinaryRule.getSourcePathToOutput(),
                  unstrippedBinaryRule
                      .getAppleDebugSymbolDeps()
                      .map(BuildRule::getSourcePathToOutput)
                      .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
                  AppleDsym.getDsymOutputPath(dsymBuildTarget, projectFilesystem),
                  isCacheable);
            });
  }

  static AppleBundle createAppleBundle(
      XCodeDescriptions xcodeDescriptions,
      CxxPlatformsProvider cxxPlatformsProvider,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatforms,
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      Optional<BuildTarget> binary,
      Optional<PatternMatchedCollection<BuildTarget>> platformBinary,
      Either<AppleBundleExtension, String> extension,
      Optional<String> productName,
      SourcePath infoPlist,
      ImmutableMap<String, String> infoPlistSubstitutions,
      ImmutableSortedSet<BuildTarget> deps,
      ImmutableSortedSet<BuildTarget> tests,
      AppleDebugFormat debugFormat,
      boolean dryRunCodeSigning,
      boolean cacheable,
      boolean verifyResources,
      ValidationType assetCatalogValidation,
      AppleAssetCatalogsCompilationOptions appleAssetCatalogsCompilationOptions,
      ImmutableList<String> codesignFlags,
      Optional<String> codesignAdhocIdentity,
      Optional<Boolean> ibtoolModuleFlag,
      Optional<ImmutableList<String>> ibtoolFlags,
      Duration codesignTimeout,
      boolean copySwiftStdlibToFrameworks,
      boolean cacheStrips) {
    AppleCxxPlatform appleCxxPlatform =
        ApplePlatforms.getAppleCxxPlatformForBuildTarget(
            cxxPlatformsProvider,
            appleCxxPlatforms,
            buildTarget,
            MultiarchFileInfos.create(appleCxxPlatforms, buildTarget));
    BuildTarget binaryTarget =
        getTargetPlatformBinary(binary, platformBinary, appleCxxPlatform.getFlavor());

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
            xcodeDescriptions,
            targetGraph,
            graphBuilder,
            Optional.empty(),
            targetGraph.get(buildTarget),
            appleCxxPlatform,
            RecursiveDependenciesMode.COPYING);

    ImmutableSet.Builder<SourcePath> frameworksBuilder = ImmutableSet.builder();
    if (INCLUDE_FRAMEWORKS.getRequiredValue(buildTarget)) {
      for (BuildTarget dep : deps) {
        Optional<FrameworkDependencies> frameworkDependencies =
            graphBuilder.requireMetadata(
                dep.withAppendedFlavors(
                    FRAMEWORK_FLAVOR,
                    NO_INCLUDE_FRAMEWORKS_FLAVOR,
                    appleCxxPlatform.getCxxPlatform().getFlavor()),
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
      Optional<TargetNode<PrebuiltAppleFrameworkDescriptionArg>> prebuiltNode =
          targetGraph
              .getOptional(dep)
              .flatMap(
                  node -> TargetNodes.castArg(node, PrebuiltAppleFrameworkDescriptionArg.class));
      if (prebuiltNode.isPresent()
          && !prebuiltNode
              .get()
              .getConstructorArg()
              .getPreferredLinkage()
              .equals(NativeLinkable.Linkage.STATIC)) {
        frameworksBuilder.add(graphBuilder.requireRule(dep).getSourcePathToOutput());
      }
    }
    ImmutableSet<SourcePath> frameworks = frameworksBuilder.build();

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget buildTargetWithoutBundleSpecificFlavors = stripBundleSpecificFlavors(buildTarget);

    Optional<AppleAssetCatalog> assetCatalog =
        createBuildRuleForTransitiveAssetCatalogDependencies(
            xcodeDescriptions,
            targetGraph,
            buildTargetWithoutBundleSpecificFlavors,
            projectFilesystem,
            sourcePathResolver,
            ruleFinder,
            appleCxxPlatform.getAppleSdk().getApplePlatform(),
            appleCxxPlatform.getMinVersion(),
            appleCxxPlatform.getActool(),
            assetCatalogValidation,
            appleAssetCatalogsCompilationOptions);
    addToIndex(graphBuilder, assetCatalog);

    Optional<CoreDataModel> coreDataModel =
        createBuildRulesForCoreDataDependencies(
            xcodeDescriptions,
            targetGraph,
            buildTargetWithoutBundleSpecificFlavors,
            projectFilesystem,
            params,
            AppleBundle.getBinaryName(buildTarget, productName),
            appleCxxPlatform);
    addToIndex(graphBuilder, coreDataModel);

    Optional<SceneKitAssets> sceneKitAssets =
        createBuildRulesForSceneKitAssetsDependencies(
            xcodeDescriptions,
            targetGraph,
            buildTargetWithoutBundleSpecificFlavors,
            projectFilesystem,
            params,
            appleCxxPlatform);
    addToIndex(graphBuilder, sceneKitAssets);

    // TODO(beng): Sort through the changes needed to make project generation work with
    // binary being optional.
    ImmutableSortedSet<Flavor> flavoredBinaryRuleFlavors =
        buildTargetWithoutBundleSpecificFlavors.getFlavors();
    BuildRule flavoredBinaryRule =
        getFlavoredBinaryRule(
            cxxPlatformsProvider,
            targetGraph,
            flavoredBinaryRuleFlavors,
            graphBuilder,
            binaryTarget);

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
    Optional<LinkerMapMode> linkerMapMode = LinkerMapMode.FLAVOR_DOMAIN.getValue(buildTarget);
    if (linkerMapMode.isPresent()) {
      unstrippedTarget = unstrippedTarget.withAppendedFlavors(linkerMapMode.get().getFlavor());
    }
    BuildRule unstrippedBinaryRule = graphBuilder.requireRule(unstrippedTarget);

    BuildRule targetDebuggableBinaryRule;
    Optional<AppleDsym> appleDsym;
    if (unstrippedBinaryRule instanceof HasAppleDebugSymbolDeps) {
      BuildTarget binaryBuildTarget =
          getBinaryFromBuildRuleWithBinary(flavoredBinaryRule)
              .getBuildTarget()
              .withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors());
      AppleDebuggableBinary debuggableBinary =
          createAppleDebuggableBinary(
              binaryBuildTarget,
              projectFilesystem,
              graphBuilder,
              getBinaryFromBuildRuleWithBinary(flavoredBinaryRule),
              (HasAppleDebugSymbolDeps) unstrippedBinaryRule,
              debugFormat,
              cxxPlatformsProvider,
              appleCxxPlatforms,
              cacheStrips);
      targetDebuggableBinaryRule = debuggableBinary;
      appleDsym = debuggableBinary.getAppleDsym();
    } else {
      targetDebuggableBinaryRule = unstrippedBinaryRule;
      appleDsym = Optional.empty();
    }

    ImmutableList<String> ibtoolFlagsUnwrapped = ibtoolFlags.orElse(ImmutableList.of());

    ImmutableSet<BuildRule> extraBinaries =
        collectFirstLevelExtraBinariesFromDeps(
            appleCxxPlatform.getAppleSdk().getApplePlatform().getType(),
            deps,
            binaryTarget,
            cxxPlatformsProvider,
            targetGraph,
            flavoredBinaryRuleFlavors,
            graphBuilder);

    BuildRuleParams bundleParamsWithFlavoredBinaryDep =
        getBundleParamsWithUpdatedDeps(
            params,
            binaryTarget,
            ImmutableSet.<BuildRule>builder()
                .add(targetDebuggableBinaryRule)
                .addAll(Optionals.toStream(assetCatalog).iterator())
                .addAll(Optionals.toStream(coreDataModel).iterator())
                .addAll(Optionals.toStream(sceneKitAssets).iterator())
                .addAll(
                    BuildRules.toBuildRulesFor(
                        buildTarget,
                        graphBuilder,
                        RichStream.from(collectedResources.getAll())
                            .concat(frameworks.stream())
                            .filter(BuildTargetSourcePath.class)
                            .map(BuildTargetSourcePath::getTarget)
                            .collect(ImmutableSet.toImmutableSet())))
                .addAll(Optionals.toStream(appleDsym).iterator())
                .addAll(extraBinaries)
                .build());

    ImmutableMap<SourcePath, String> extensionBundlePaths =
        collectFirstLevelAppleDependencyBundles(params.getBuildDeps(), destinations);

    return new AppleBundle(
        buildTarget,
        projectFilesystem,
        bundleParamsWithFlavoredBinaryDep,
        graphBuilder,
        extension,
        productName,
        infoPlist,
        infoPlistSubstitutions,
        Optional.of(getBinaryFromBuildRuleWithBinary(flavoredBinaryRule)),
        appleDsym,
        extraBinaries,
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
        cacheable,
        verifyResources,
        codesignFlags,
        codesignAdhocIdentity,
        ibtoolModuleFlag,
        ibtoolFlagsUnwrapped,
        codesignTimeout,
        copySwiftStdlibToFrameworks);
  }

  /**
   * Returns a build target of the apple binary for the requested target platform.
   *
   * <p>By default it's the binary that was provided using {@code binary} attribute, but in case
   * {@code platform_binary} is specified and one of its patterns matches the target platform, it
   * will be returned instead.
   */
  public static BuildTarget getTargetPlatformBinary(
      Optional<BuildTarget> binary,
      Optional<PatternMatchedCollection<BuildTarget>> platformBinary,
      Flavor cxxPlatformFlavor) {
    String targetPlatform = cxxPlatformFlavor.toString();
    return platformBinary
        .flatMap(pb -> getPlatformMatchingBinary(targetPlatform, pb))
        .orElseGet(
            () ->
                binary.orElseThrow(
                    () ->
                        new HumanReadableException(
                            "Binary matching target platform "
                                + targetPlatform
                                + " cannot be found and binary default is not specified.\n"
                                + "Please refer to https://buckbuild.com/rule/apple_bundle.html#binary for "
                                + "more details.")));
  }

  /** Returns an optional binary target that matches the target platform. */
  private static Optional<BuildTarget> getPlatformMatchingBinary(
      String targetPlatform, PatternMatchedCollection<BuildTarget> platformBinary) {
    ImmutableList<BuildTarget> matchingBinaries = platformBinary.getMatchingValues(targetPlatform);
    if (matchingBinaries.size() > 1) {
      throw new HumanReadableException(
          "There must be at most one binary matching the target platform "
              + targetPlatform
              + " but all of "
              + matchingBinaries
              + " matched. Please make your pattern more precise and remove any duplicates.");
    }
    return matchingBinaries.isEmpty() ? Optional.empty() : Optional.of(matchingBinaries.get(0));
  }

  private static void addToIndex(
      ActionGraphBuilder graphBuilder, Optional<? extends BuildRule> rule) {
    if (rule.isPresent()) {
      graphBuilder.addToIndex(rule.get());
    }
  }

  private static BuildRule getBinaryFromBuildRuleWithBinary(BuildRule rule) {
    if (rule instanceof BuildRuleWithBinary) {
      rule = ((BuildRuleWithBinary) rule).getBinaryBuildRule();
    }
    return rule;
  }

  private static BuildRule getFlavoredBinaryRule(
      CxxPlatformsProvider cxxPlatformsProvider,
      TargetGraph targetGraph,
      ImmutableSet<Flavor> flavors,
      ActionGraphBuilder graphBuilder,
      BuildTarget binary) {

    // Don't flavor genrule deps.
    if (targetGraph.get(binary).getDescription() instanceof AbstractGenruleDescription) {
      return graphBuilder.requireRule(binary);
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
    if (!cxxPlatformsProvider.getCxxPlatforms().containsAnyOf(flavors)) {
      flavors =
          new ImmutableSet.Builder<Flavor>()
              .addAll(flavors)
              .add(cxxPlatformsProvider.getDefaultCxxPlatform().getFlavor())
              .build();
    }

    ImmutableSet.Builder<Flavor> binaryFlavorsBuilder = ImmutableSet.builder();
    binaryFlavorsBuilder.addAll(flavors);
    if (!(AppleLibraryDescription.LIBRARY_TYPE.getFlavor(flavors).isPresent())) {
      binaryFlavorsBuilder.addAll(binary.getFlavors());
    } else {
      binaryFlavorsBuilder.addAll(
          Sets.difference(binary.getFlavors(), AppleLibraryDescription.LIBRARY_TYPE.getFlavors()));
    }
    BuildTarget buildTarget = binary.withFlavors(binaryFlavorsBuilder.build());

    TargetNode<?> binaryTargetNode = targetGraph.get(buildTarget);

    if (binaryTargetNode.getDescription() instanceof AppleTestDescription) {
      return graphBuilder.getRule(binary);
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

    return graphBuilder.requireRule(buildTarget);
  }

  private static BuildRuleParams getBundleParamsWithUpdatedDeps(
      BuildRuleParams params, BuildTarget originalBinaryTarget, Set<BuildRule> newDeps) {
    // Remove the unflavored binary rule and add the flavored one instead.
    Predicate<BuildRule> notOriginalBinaryRule =
        BuildRules.isBuildRuleWithTarget(originalBinaryTarget).negate();
    return params
        .withDeclaredDeps(
            FluentIterable.from(params.getDeclaredDeps().get())
                .filter(notOriginalBinaryRule::test)
                .append(newDeps)
                .toSortedSet(Ordering.natural()))
        .withExtraDeps(
            params
                .getExtraDeps()
                .get()
                .stream()
                .filter(notOriginalBinaryRule)
                .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
  }

  private static ImmutableMap<SourcePath, String> collectFirstLevelAppleDependencyBundles(
      SortedSet<BuildRule> deps, AppleBundleDestinations destinations) {
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
        } else if (AppleBundleExtension.XPC.toFileExtension().equals(appleBundle.getExtension())) {
          extensionBundlePaths.put(sourcePath, destinations.getXPCServicesPath().toString());
        } else if (AppleBundleExtension.QLGENERATOR
            .toFileExtension()
            .equals(appleBundle.getExtension())) {
          extensionBundlePaths.put(sourcePath, destinations.getQuickLookPath().toString());
        } else if (AppleBundleExtension.PLUGIN
            .toFileExtension()
            .equals(appleBundle.getExtension())) {
          extensionBundlePaths.put(sourcePath, destinations.getPlugInsPath().toString());
        } else if (AppleBundleExtension.PREFPANE
            .toFileExtension()
            .equals(appleBundle.getExtension())) {
          extensionBundlePaths.put(sourcePath, destinations.getResourcesPath().toString());
        }
      }
    }

    return extensionBundlePaths.build();
  }

  private static ImmutableSet<BuildRule> collectFirstLevelExtraBinariesFromDeps(
      ApplePlatformType platformType,
      ImmutableSortedSet<BuildTarget> depBuildTargets,
      BuildTarget primaryBinaryTarget,
      CxxPlatformsProvider cxxPlatformsProvider,
      TargetGraph targetGraph,
      ImmutableSet<Flavor> flavors,
      ActionGraphBuilder graphBuilder) {
    if (platformType != ApplePlatformType.MAC) {
      // Multiple binaries are only supported on macOS
      return ImmutableSet.of();
    }

    ImmutableSet.Builder<BuildRule> flavoredBinariesBuilder = ImmutableSet.builder();

    for (BuildTarget dep : depBuildTargets) {
      if (dep.equals(primaryBinaryTarget)) {
        continue;
      }

      Optional<TargetNode<?>> depTargetNode = targetGraph.getOptional(dep);
      Optional<TargetNode<AppleBinaryDescriptionArg>> binaryDepNode =
          depTargetNode.flatMap(n -> TargetNodes.castArg(n, AppleBinaryDescriptionArg.class));
      if (!binaryDepNode.isPresent()) {
        // Skip any deps which are not apple_binary
        continue;
      }

      BuildRule flavoredBinary =
          getFlavoredBinaryRule(
              cxxPlatformsProvider,
              targetGraph,
              flavors,
              graphBuilder,
              binaryDepNode.get().getBuildTarget());

      flavoredBinariesBuilder.add(getBinaryFromBuildRuleWithBinary(flavoredBinary));
    }

    return flavoredBinariesBuilder.build();
  }

  /**
   * Strip flavors that only apply to a bundle from build targets that are passed to constituent
   * rules of the bundle, such as its associated binary, asset catalog, etc.
   */
  private static BuildTarget stripBundleSpecificFlavors(BuildTarget buildTarget) {
    return buildTarget.withoutFlavors(BUNDLE_SPECIFIC_FLAVORS);
  }

  public static boolean flavorsDoNotAllowLinkerMapMode(BuildTarget buildTarget) {
    ImmutableSet<Flavor> flavors = buildTarget.getFlavors();
    return flavors.contains(CxxCompilationDatabase.COMPILATION_DATABASE)
        || flavors.contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)
        || flavors.contains(CxxDescriptionEnhancer.STATIC_FLAVOR)
        || flavors.contains(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR)
        || flavors.contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
        || flavors.contains(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR);
  }

  public static boolean targetNodeContainsSwiftSourceCode(
      TargetNode<? extends CxxLibraryDescription.CommonArg> node) {
    for (Path inputPath : node.getInputs()) {
      if (inputPath.toString().endsWith(".swift")) {
        return true;
      }
    }

    return false;
  }
}
