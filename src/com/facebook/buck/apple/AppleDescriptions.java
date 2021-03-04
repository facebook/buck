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

import static com.facebook.buck.apple.AppleAssetCatalog.validateAssetCatalogs;
import static com.facebook.buck.apple.AppleCodeSignType.ADHOC;
import static com.facebook.buck.apple.AppleCodeSignType.DISTRIBUTION;
import static com.facebook.buck.swift.SwiftDescriptions.SWIFT_EXTENSION;

import com.facebook.buck.apple.AppleAssetCatalog.ValidationType;
import com.facebook.buck.apple.AppleBuildRules.RecursiveDependenciesMode;
import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.apple.toolchain.UnresolvedAppleCxxPlatform;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildRules;
import com.facebook.buck.core.rules.common.SourcePathSupport;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.CxxBinaryDescriptionArg;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxDiagnosticsEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxLibraryDescriptionArg;
import com.facebook.buck.cxx.CxxLinkGroupMapDatabase;
import com.facebook.buck.cxx.CxxPlatformParseTimeDeps;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.cxx.HasAppleDebugSymbolDeps;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Either;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Common logic for a {@link DescriptionWithTargetGraph} that creates Apple target rules. */
public class AppleDescriptions {

  public static final Flavor FRAMEWORK_FLAVOR = InternalFlavor.of("framework");
  public static final Flavor SWIFT_COMPILE_FLAVOR = InternalFlavor.of("apple-swift-compile");
  public static final Flavor SWIFT_COMMAND_FLAVOR = InternalFlavor.of("apple-swift-command");
  public static final Flavor SWIFT_EXPORTED_OBJC_GENERATED_HEADER_SYMLINK_TREE_FLAVOR =
      InternalFlavor.of("apple-swift-objc-generated-header");
  public static final Flavor SWIFT_OBJC_GENERATED_HEADER_SYMLINK_TREE_FLAVOR =
      InternalFlavor.of("apple-swift-private-objc-generated-header");
  public static final Flavor SWIFT_UNDERLYING_MODULE_FLAVOR =
      InternalFlavor.of("apple-swift-underlying-module");
  public static final Flavor SWIFT_UNDERLYING_VFS_OVERLAY_FLAVOR =
      InternalFlavor.of("apple-swift-underlying-vfs-overlay");

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
      Function<SourcePath, RelPath> pathResolver,
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
      Function<SourcePath, RelPath> pathResolver,
      Path headerPathPrefix,
      SourceSortedSet sourceSet) {
    // The exported headers in the populated cxx constructor arg will contain exported headers from
    // the apple constructor arg with the public include style.
    return AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
        buildTarget, pathResolver, headerPathPrefix, sourceSet);
  }

  public static ImmutableSortedMap<String, SourcePath> convertAppleHeadersToPrivateCxxHeaders(
      BuildTarget buildTarget,
      Function<SourcePath, RelPath> pathResolver,
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
                            .entrySet().stream(),
                        AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
                            buildTarget, pathResolver, arg.getExportedHeaders())
                            .entrySet().stream(),
                        AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
                            buildTarget, pathResolver, headerPathPrefix, arg.getHeaders())
                            .entrySet().stream())
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
      Function<SourcePath, RelPath> pathResolver,
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
                            .entrySet().stream(),
                        AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
                            buildTarget, pathResolver, publicSourceSet)
                            .entrySet().stream(),
                        AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
                            buildTarget, pathResolver, headerPathPrefix, privateSourceSet)
                            .entrySet().stream())
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
      Function<SourcePath, RelPath> pathResolver,
      Path headerPathPrefix,
      SourceSortedSet headers) {
    return headers.match(
        new SourceSortedSet.Matcher<ImmutableSortedMap<String, SourcePath>>() {
          @Override
          public ImmutableSortedMap<String, SourcePath> named(
              ImmutableSortedMap<String, SourcePath> named) {
            // The user specified a map from include paths to header files.
            // Just use the specified map.
            return named;
          }

          @Override
          public ImmutableSortedMap<String, SourcePath> unnamed(
              ImmutableSortedSet<SourcePath> unnamed) {
            // The user specified a set of header files. For use from other targets,
            // prepend their names with the header path prefix.
            return convertToFlatCxxHeaders(buildTarget, headerPathPrefix, pathResolver, unnamed);
          }
        });
  }

  @VisibleForTesting
  static ImmutableSortedMap<String, SourcePath> parseAppleHeadersForUseFromTheSameTarget(
      BuildTarget buildTarget,
      Function<SourcePath, RelPath> pathResolver,
      SourceSortedSet headers) {
    return headers.match(
        new SourceSortedSet.Matcher<ImmutableSortedMap<String, SourcePath>>() {
          @Override
          public ImmutableSortedMap<String, SourcePath> named(
              ImmutableSortedMap<String, SourcePath> named) {
            // The user specified a map from include paths to header files. There is nothing we need
            // to add on top of the exported headers.
            return ImmutableSortedMap.of();
          }

          @Override
          public ImmutableSortedMap<String, SourcePath> unnamed(
              ImmutableSortedSet<SourcePath> unnamed) {
            // The user specified a set of header files. Headers can be included from the same
            // target using only their file name without a prefix.
            return convertToFlatCxxHeaders(buildTarget, Paths.get(""), pathResolver, unnamed);
          }
        });
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
      Function<SourcePath, RelPath> sourcePathResolver,
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

  private static void populateCxxConstructorArg(
      BuildRuleResolver ruleResolver,
      Optional<UnresolvedAppleCxxPlatform> appleCxxPlatform,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget,
      Consumer<ImmutableSortedSet<SourceWithFlags>> setSrcs,
      Consumer<SourceSortedSet> setHeaders,
      Consumer<String> setHeaderNamespace,
      Consumer<StringWithMacros> addCompilerFlags) {
    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(arg, buildTarget);
    SourcePathResolverAdapter resolver = ruleResolver.getSourcePathResolver();
    // The resulting cxx constructor arg will have no exported headers and both headers and exported
    // headers specified in the apple arg will be available with both public and private include
    // styles.
    ImmutableSortedMap<String, SourcePath> headerMap =
        ImmutableSortedMap.<String, SourcePath>naturalOrder()
            .putAll(
                Stream.concat(
                        convertAppleHeadersToPublicCxxHeaders(
                            buildTarget, resolver::getCellUnsafeRelPath, headerPathPrefix, arg)
                            .entrySet().stream(),
                        convertAppleHeadersToPrivateCxxHeaders(
                            buildTarget, resolver::getCellUnsafeRelPath, headerPathPrefix, arg)
                            .entrySet().stream())
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

    addSDKVersionFlagForTargetIfNecessary(ruleResolver, appleCxxPlatform, arg, addCompilerFlags);
  }

  private static void addSDKVersionFlagForTargetIfNecessary(
      BuildRuleResolver ruleResolver,
      Optional<UnresolvedAppleCxxPlatform> appleCxxPlatform,
      AppleNativeTargetDescriptionArg arg,
      Consumer<StringWithMacros> addCompilerFlags) {
    if (appleCxxPlatform.isPresent()) {
      String platformVersion = appleCxxPlatform.get().resolve(ruleResolver).getMinVersion();
      Optional<String> targetVersion = arg.getTargetSdkVersion();

      // If the target has a different target SDK version from the overall platform, we add
      // a compiler flag to override that base version with the per-target version.
      //
      // The overall platform flags (compiler _and_ linker) already contain a deployment
      // target flag for the platform version (i.e., AppleCxxPlatform.getMinVersion()).
      //
      // AppleCxxPlatform.getMinVersion() is determined by using [platform]_target_sdk_version
      // configs if present, otherwise picks up the latest SDK version.
      if (targetVersion.isPresent() && platformVersion != targetVersion.get()) {
        String versionFlag =
            appleCxxPlatform
                    .get()
                    .resolve(ruleResolver)
                    .getAppleSdk()
                    .getApplePlatform()
                    .getMinVersionFlagPrefix()
                + targetVersion.get();
        addCompilerFlags.accept(StringWithMacros.of(ImmutableList.of(Either.ofLeft(versionFlag))));
      }
    }
  }

  public static void populateCxxBinaryDescriptionArg(
      ActionGraphBuilder graphBuilder,
      CxxBinaryDescriptionArg.Builder output,
      Optional<UnresolvedAppleCxxPlatform> appleCxxPlatform,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget,
      boolean addSDKVersionFlagToLinkerFlags) {
    populateCxxConstructorArg(
        graphBuilder,
        appleCxxPlatform,
        arg,
        buildTarget,
        output::setSrcs,
        output::setHeaders,
        output::setHeaderNamespace,
        output::addCompilerFlags);
    output.setDefaultPlatform(arg.getDefaultPlatform());

    if (addSDKVersionFlagToLinkerFlags) {
      addSDKVersionFlagForTargetIfNecessary(
          graphBuilder, appleCxxPlatform, arg, output::addLinkerFlags);
    }
  }

  public static void populateCxxLibraryDescriptionArg(
      BuildRuleResolver ruleResolver,
      CxxLibraryDescriptionArg.Builder output,
      Optional<UnresolvedAppleCxxPlatform> appleCxxPlatform,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget,
      boolean addSDKVersionFlagToLinkerFlags) {
    SourcePathResolverAdapter resolver = ruleResolver.getSourcePathResolver();
    populateCxxConstructorArg(
        ruleResolver,
        appleCxxPlatform,
        arg,
        buildTarget,
        output::setSrcs,
        output::setHeaders,
        output::setHeaderNamespace,
        output::addCompilerFlags);
    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(arg, buildTarget);
    output.setHeaders(
        SourceSortedSet.ofNamedSources(
            convertAppleHeadersToPrivateCxxHeaders(
                buildTarget, resolver::getCellUnsafeRelPath, headerPathPrefix, arg)));
    output.setExportedHeaders(
        SourceSortedSet.ofNamedSources(
            convertAppleHeadersToPublicCxxHeaders(
                buildTarget, resolver::getCellUnsafeRelPath, headerPathPrefix, arg)));
    if (arg.isModular()) {
      output.addCompilerFlags(
          StringWithMacros.ofConstantString(
              "-fmodule-name=" + arg.getHeaderPathPrefix().orElse(buildTarget.getShortName())));
    }

    if (addSDKVersionFlagToLinkerFlags) {
      addSDKVersionFlagForTargetIfNecessary(
          ruleResolver, appleCxxPlatform, arg, output::addLinkerFlags);
    }
  }

  public static Optional<AppleAssetCatalog> createBuildRuleForTransitiveAssetCatalogDependencies(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ApplePlatform applePlatform,
      String targetSDKVersion,
      Optional<String> maybeDeviceFamily,
      Optional<String> maybeUIFrameworkFamily,
      Tool actool,
      AppleAssetCatalog.ValidationType assetCatalogValidation,
      AppleAssetCatalogsCompilationOptions appleAssetCatalogsCompilationOptions,
      Predicate<BuildTarget> filter,
      boolean withDownwardApi) {
    TargetNode<?> targetNode = targetGraph.get(buildTarget);

    ImmutableSet<AppleAssetCatalogDescriptionArg> assetCatalogArgs =
        AppleBuildRules.collectRecursiveAssetCatalogs(
            xcodeDescriptions,
            targetGraph,
            Optional.empty(),
            ImmutableList.of(targetNode),
            RecursiveDependenciesMode.COPYING,
            filter);

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
        ruleFinder.getSourcePathResolver(),
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
            applePlatform,
            targetSDKVersion,
            maybeDeviceFamily,
            maybeUIFrameworkFamily,
            actool,
            assetCatalogDirs,
            appIcon,
            launchImage,
            appleAssetCatalogsCompilationOptions,
            MERGED_ASSET_CATALOG_NAME,
            withDownwardApi));
  }

  public static Optional<CoreDataModel> createBuildRulesForCoreDataDependencies(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      String moduleName,
      AppleCxxPlatform appleCxxPlatform,
      Predicate<BuildTarget> filter,
      boolean withDownwardApi) {
    TargetNode<?> targetNode = targetGraph.get(buildTarget);

    ImmutableSet<AppleWrapperResourceArg> coreDataModelArgs =
        AppleBuildRules.collectTransitiveBuildTargetArg(
            xcodeDescriptions,
            targetGraph,
            Optional.empty(),
            AppleBuildRules.CORE_DATA_MODEL_DESCRIPTION_CLASSES,
            ImmutableList.of(targetNode),
            RecursiveDependenciesMode.COPYING,
            filter);

    BuildTarget coreDataModelBuildTarget = buildTarget.withAppendedFlavors(CoreDataModel.FLAVOR);

    if (coreDataModelArgs.isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.of(
          new CoreDataModel(
              coreDataModelBuildTarget,
              projectFilesystem,
              graphBuilder,
              appleCxxPlatform,
              moduleName,
              coreDataModelArgs.stream()
                  .map(input -> PathSourcePath.of(projectFilesystem, input.getPath()))
                  .collect(ImmutableSet.toImmutableSet()),
              withDownwardApi));
    }
  }

  public static Optional<SceneKitAssets> createBuildRulesForSceneKitAssetsDependencies(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      AppleCxxPlatform appleCxxPlatform,
      Predicate<BuildTarget> filter,
      boolean withDownwardApi) {
    TargetNode<?> targetNode = targetGraph.get(buildTarget);

    ImmutableSet<AppleWrapperResourceArg> sceneKitAssetsArgs =
        AppleBuildRules.collectTransitiveBuildTargetArg(
            xcodeDescriptions,
            targetGraph,
            Optional.empty(),
            AppleBuildRules.SCENEKIT_ASSETS_DESCRIPTION_CLASSES,
            ImmutableList.of(targetNode),
            RecursiveDependenciesMode.COPYING,
            filter);

    BuildTarget sceneKitAssetsBuildTarget = buildTarget.withAppendedFlavors(SceneKitAssets.FLAVOR);

    if (sceneKitAssetsArgs.isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.of(
          new SceneKitAssets(
              sceneKitAssetsBuildTarget,
              projectFilesystem,
              graphBuilder,
              appleCxxPlatform,
              sceneKitAssetsArgs.stream()
                  .map(input -> PathSourcePath.of(projectFilesystem, input.getPath()))
                  .collect(ImmutableSet.toImmutableSet()),
              withDownwardApi));
    }
  }

  static AppleDebuggableBinary createAppleDebuggableBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      BuildRule strippedBinaryRule,
      HasAppleDebugSymbolDeps unstrippedBinaryRule,
      AppleDebugFormat debugFormat,
      ImmutableList<String> dsymutilExtraFlags,
      CxxPlatformsProvider cxxPlatformsProvider,
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatforms,
      boolean shouldCacheStrips,
      boolean withDownwardApi) {
    // Target used as the base target of AppleDebuggableBinary.
    BuildTarget baseTarget = unstrippedBinaryRule.getBuildTarget();

    return (AppleDebuggableBinary)
        graphBuilder.computeIfAbsent(
            // FIXME: we should not be adding build flavors here. It results in some call to
            // `graphBuilder.requireRule` receiving a rule with a different name from the one it
            // asked for. Doing it within a `computeIfAbsent` call papers over the issue a bit
            // because it ensures that the rule can be looked up by either name, but we should
            // really be creating the `AppleDebuggableBinary` with the `BuildTarget` that is
            // passed in. We should get the debug format based on what flavors are present, and
            // either ensuring that every request for a debuggable binary has the
            // `AppleDebuggableBinary.RULE_FLAVOR` on it (or perhaps eliminating that flavor
            // entirely).
            baseTarget.withAppendedFlavors(
                AppleDebuggableBinary.RULE_FLAVOR, debugFormat.getFlavor()),
            binaryTarget -> {
              switch (debugFormat) {
                case DWARF:
                  return AppleDebuggableBinary.createFromUnstrippedBinary(
                      projectFilesystem, binaryTarget, unstrippedBinaryRule);
                case DWARF_AND_DSYM:
                  AppleDsym dsym =
                      requireAppleDsym(
                          buildTarget,
                          projectFilesystem,
                          graphBuilder,
                          unstrippedBinaryRule,
                          cxxPlatformsProvider,
                          appleCxxPlatforms,
                          dsymutilExtraFlags,
                          shouldCacheStrips,
                          withDownwardApi);
                  return AppleDebuggableBinary.createWithDsym(
                      projectFilesystem, binaryTarget, strippedBinaryRule, dsym);
                case NONE:
                  return AppleDebuggableBinary.createWithoutDebugging(
                      projectFilesystem, binaryTarget, strippedBinaryRule);
              }
              throw new IllegalStateException("Unhandled debugFormat");
            });
  }

  private static AppleDsym requireAppleDsym(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      HasAppleDebugSymbolDeps unstrippedBinaryRule,
      CxxPlatformsProvider cxxPlatformsProvider,
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatforms,
      ImmutableList<String> dsymutilExtraFlags,
      boolean isCacheable,
      boolean withDownwardApi) {
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
                      graphBuilder,
                      cxxPlatformsProvider,
                      appleCxxPlatforms,
                      unstrippedBinaryRule.getBuildTarget(),
                      Optional.empty(),
                      MultiarchFileInfos.create(
                          appleCxxPlatforms, unstrippedBinaryRule.getBuildTarget()));
              return new AppleDsym(
                  dsymBuildTarget,
                  projectFilesystem,
                  graphBuilder,
                  appleCxxPlatform.getDsymutil(),
                  dsymutilExtraFlags,
                  appleCxxPlatform.getLldb(),
                  unstrippedBinaryRule.getSourcePathToOutput(),
                  unstrippedBinaryRule
                      .getAppleDebugSymbolDeps()
                      .map(BuildRule::getSourcePathToOutput)
                      .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
                  AppleDsym.getDsymOutputPath(dsymBuildTarget, projectFilesystem).getPath(),
                  isCacheable,
                  withDownwardApi);
            });
  }

  static AppleBundle createAppleBundle(
      XCodeDescriptions xcodeDescriptions,
      CxxPlatformsProvider cxxPlatformsProvider,
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatforms,
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      Optional<BuildTarget> binary,
      Optional<PatternMatchedCollection<BuildTarget>> platformBinary,
      Optional<Flavor> defaultPlatform,
      Either<AppleBundleExtension, String> extension,
      Optional<String> productName,
      SourcePath infoPlistSourcePath,
      ImmutableMap<String, String> infoPlistSubstitutions,
      ImmutableSortedSet<BuildTarget> deps,
      ImmutableSortedSet<BuildTarget> tests,
      AppleDebugFormat debugFormat,
      ImmutableList<String> dsymutilExtraFlags,
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
      boolean cacheStrips,
      boolean useEntitlementsWhenAdhocCodeSigning,
      Predicate<BuildTarget> filter,
      boolean sliceAppPackageSwiftRuntime,
      boolean sliceAppBundleSwiftRuntime,
      boolean withDownwardApi,
      Optional<String> minimumOSVersion,
      boolean incrementalBundlingEnabled,
      Optional<AppleCodeSignType> codeSignTypeOverride,
      boolean bundleInputBasedRulekeyEnabled,
      boolean shouldCacheFileHashes,
      boolean parallelCodeSignOnCopyEnabled) {
    AppleCxxPlatform appleCxxPlatform =
        ApplePlatforms.getAppleCxxPlatformForBuildTarget(
            graphBuilder,
            cxxPlatformsProvider,
            appleCxxPlatforms,
            buildTarget,
            defaultPlatform,
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
              .equals(NativeLinkableGroup.Linkage.STATIC)) {
        frameworksBuilder.add(graphBuilder.requireRule(dep).getSourcePathToOutput());
      }
    }
    ImmutableSet<SourcePath> frameworks = frameworksBuilder.build();

    BuildTarget buildTargetWithoutBundleSpecificFlavors = stripBundleSpecificFlavors(buildTarget);

    Optional<AppleAssetCatalog> assetCatalog =
        createBuildRuleForTransitiveAssetCatalogDependencies(
            xcodeDescriptions,
            targetGraph,
            buildTargetWithoutBundleSpecificFlavors,
            projectFilesystem,
            graphBuilder,
            appleCxxPlatform.getAppleSdk().getApplePlatform(),
            appleCxxPlatform.getMinVersion(),
            appleCxxPlatform.getAppleSdk().getResourcesDeviceFamily(),
            appleCxxPlatform.getAppleSdk().getResourcesUIFrameworkFamily(),
            appleCxxPlatform.getActool(),
            assetCatalogValidation,
            appleAssetCatalogsCompilationOptions,
            filter,
            withDownwardApi);
    addToIndex(graphBuilder, assetCatalog);

    Optional<CoreDataModel> coreDataModel =
        createBuildRulesForCoreDataDependencies(
            xcodeDescriptions,
            targetGraph,
            buildTargetWithoutBundleSpecificFlavors,
            projectFilesystem,
            graphBuilder,
            AppleBundle.getBinaryName(buildTarget, productName),
            appleCxxPlatform,
            filter,
            withDownwardApi);
    addToIndex(graphBuilder, coreDataModel);

    Optional<SceneKitAssets> sceneKitAssets =
        createBuildRulesForSceneKitAssetsDependencies(
            xcodeDescriptions,
            targetGraph,
            buildTargetWithoutBundleSpecificFlavors,
            projectFilesystem,
            graphBuilder,
            appleCxxPlatform,
            filter,
            withDownwardApi);
    addToIndex(graphBuilder, sceneKitAssets);

    // TODO(beng): Sort through the changes needed to make project generation work with
    // binary being optional.
    FlavorSet flavoredBinaryRuleFlavors = buildTargetWithoutBundleSpecificFlavors.getFlavors();
    BuildRule flavoredBinaryRule =
        getFlavoredBinaryRule(
            cxxPlatformsProvider,
            targetGraph,
            flavoredBinaryRuleFlavors.getSet(),
            defaultPlatform,
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
      ProjectFilesystem binaryBuildTargetFilesystem =
          targetGraph
              .get(binaryBuildTarget, DependencyStack.top(binaryBuildTarget))
              .getFilesystem();
      AppleDebuggableBinary debuggableBinary =
          createAppleDebuggableBinary(
              binaryBuildTarget,
              binaryBuildTargetFilesystem,
              graphBuilder,
              getBinaryFromBuildRuleWithBinary(flavoredBinaryRule),
              (HasAppleDebugSymbolDeps) unstrippedBinaryRule,
              debugFormat,
              dsymutilExtraFlags,
              cxxPlatformsProvider,
              appleCxxPlatforms,
              cacheStrips,
              withDownwardApi);
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
            flavoredBinaryRuleFlavors.getSet(),
            defaultPlatform,
            graphBuilder);

    ImmutableList.Builder<AppleBundlePart> bundlePartsReadyToCopy = ImmutableList.builder();

    addExtraBinariesToBundleParts(
        bundlePartsReadyToCopy,
        extraBinaries,
        graphBuilder,
        projectFilesystem,
        incrementalBundlingEnabled,
        shouldCacheFileHashes);

    String unwrappedExtension =
        extension.isLeft() ? extension.getLeft().fileExtension : extension.getRight();

    addPkgInfoIfNeededToBundleParts(
        incrementalBundlingEnabled,
        bundlePartsReadyToCopy,
        buildTarget,
        unwrappedExtension,
        graphBuilder,
        projectFilesystem,
        shouldCacheFileHashes);

    Stream.of(assetCatalog, coreDataModel, sceneKitAssets)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(
            buildRule -> {
              SourcePath sourcePath = Objects.requireNonNull(buildRule.getSourcePathToOutput());
              return DirectoryContentAppleBundlePart.of(
                  sourcePath,
                  AppleBundleDestination.RESOURCES,
                  getSourcePathToContentHashesOfDirectory(
                      incrementalBundlingEnabled,
                      sourcePath,
                      buildRule.getBuildTarget(),
                      graphBuilder,
                      projectFilesystem));
            })
        .forEach(bundlePartsReadyToCopy::add);

    addFirstLevelDependencyBundlesToBundleParts(
        params.getBuildDeps(),
        bundlePartsReadyToCopy,
        graphBuilder,
        projectFilesystem,
        incrementalBundlingEnabled,
        shouldCacheFileHashes);

    BuildRule unwrappedBinary = getBinaryFromBuildRuleWithBinary(flavoredBinaryRule);

    BuildTarget infoPlistBuildTarget =
        stripBundleSpecificFlavors(buildTarget).withAppendedFlavors(AppleInfoPlist.FLAVOR);
    AppleInfoPlist infoPlist =
        (AppleInfoPlist)
            graphBuilder.computeIfAbsent(
                infoPlistBuildTarget,
                plistTarget ->
                    createInfoPlistBuildRule(
                        plistTarget,
                        projectFilesystem,
                        graphBuilder,
                        infoPlistSourcePath,
                        assetCatalog.map(AppleAssetCatalog::getSourcePathToPlist),
                        unwrappedBinary,
                        productName,
                        unwrappedExtension,
                        appleCxxPlatform,
                        infoPlistSubstitutions,
                        minimumOSVersion));

    Optional<HasEntitlementsFile> maybeHasEntitlementsFile =
        graphBuilder.requireMetadata(unwrappedBinary.getBuildTarget(), HasEntitlementsFile.class);
    Optional<SourcePath> maybeEntitlementsFromBinaryParameter =
        maybeHasEntitlementsFile.flatMap(HasEntitlementsFile::getEntitlementsFile);
    Optional<SourcePath> maybeEntitlements;
    // If entitlements are provided via binary's `entitlements_file` parameter use it directly
    if (!maybeEntitlementsFromBinaryParameter.isPresent()) {
      // Fall back to getting CODE_SIGN_ENTITLEMENTS from info_plist_substitutions
      Optional<AbsPath> maybeEntitlementsPathInSubstitutions =
          AppleEntitlementsFromSubstitutions.entitlementsPathInSubstitutions(
              projectFilesystem,
              buildTarget,
              appleCxxPlatform.getAppleSdk().getApplePlatform(),
              infoPlistSubstitutions);
      if (maybeEntitlementsPathInSubstitutions.isPresent()) {
        BuildTarget entitlementFromSubstitutionBuildTarget =
            stripBundleSpecificFlavors(buildTarget)
                .withAppendedFlavors(AppleEntitlementsFromSubstitutions.FLAVOR);
        AppleEntitlementsFromSubstitutions entitlementsFromSubstitutions =
            (AppleEntitlementsFromSubstitutions)
                graphBuilder.computeIfAbsent(
                    entitlementFromSubstitutionBuildTarget,
                    target ->
                        new AppleEntitlementsFromSubstitutions(
                            target,
                            projectFilesystem,
                            graphBuilder,
                            infoPlistSubstitutions,
                            maybeEntitlementsPathInSubstitutions.get()));
        maybeEntitlements =
            Optional.ofNullable(entitlementsFromSubstitutions.getSourcePathToOutput());
      } else {
        maybeEntitlements = maybeEntitlementsFromBinaryParameter;
      }
    } else {
      maybeEntitlements = maybeEntitlementsFromBinaryParameter;
    }

    AppleCodeSignType codeSignType =
        AppleCodeSignType.signTypeForBundle(
            appleCxxPlatform.getAppleSdk().getApplePlatform(),
            unwrappedExtension,
            codeSignTypeOverride);

    Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier;
    SourcePath infoPlistReadyToCopy;
    Optional<SourcePath> entitlementsReadyForCodeSign;
    Optional<SourcePath> codeSignIdentityFingerprint;
    if (codeSignType == DISTRIBUTION) {
      codeSignIdentitiesSupplier = codeSignIdentityStore.getIdentitiesSupplier();
      BuildTarget provisioningProfileTarget =
          stripBundleSpecificFlavors(buildTarget)
              .withAppendedFlavors(AppleCodeSignPreparation.FLAVOR);
      AppleCodeSignPreparation codeSignPrepRule =
          (AppleCodeSignPreparation)
              graphBuilder.computeIfAbsent(
                  provisioningProfileTarget,
                  target ->
                      new AppleCodeSignPreparation(
                          target,
                          projectFilesystem,
                          graphBuilder,
                          appleCxxPlatform.getAppleSdk().getApplePlatform(),
                          infoPlist.getSourcePathToOutput(),
                          maybeEntitlements,
                          provisioningProfileStore,
                          codeSignIdentitiesSupplier,
                          dryRunCodeSigning));
      infoPlistReadyToCopy = codeSignPrepRule.getSourcePathToInfoPlistOutput();
      entitlementsReadyForCodeSign =
          Optional.of(codeSignPrepRule.getSourcePathToEntitlementsOutput());
      codeSignIdentityFingerprint =
          Optional.of(Objects.requireNonNull(codeSignPrepRule.getSourcePathToOutput()));
      codeSignPrepRule
          .getSourcePathToDryRunOutput()
          .ifPresent(
              sourcePath -> {
                bundlePartsReadyToCopy.add(
                    FileAppleBundlePart.of(
                        sourcePath,
                        AppleBundleDestination.BUNDLEROOT,
                        getSourcePathToContentHash(
                            incrementalBundlingEnabled,
                            sourcePath,
                            codeSignPrepRule.getBuildTarget(),
                            graphBuilder,
                            projectFilesystem,
                            shouldCacheFileHashes,
                            false)));
              });
      if (dryRunCodeSigning) {
        final boolean codeSignOnCopy = false;
        final boolean ignoreMissingSource = dryRunCodeSigning;
        SourcePath entitlementsSourcePath = codeSignPrepRule.getSourcePathToEntitlementsOutput();
        bundlePartsReadyToCopy.add(
            FileAppleBundlePart.of(
                entitlementsSourcePath,
                AppleBundleDestination.BUNDLEROOT,
                getSourcePathToContentHash(
                    incrementalBundlingEnabled,
                    entitlementsSourcePath,
                    codeSignPrepRule.getBuildTarget(),
                    graphBuilder,
                    projectFilesystem,
                    shouldCacheFileHashes,
                    false),
                codeSignOnCopy,
                Optional.of("BUCK_code_sign_entitlements.plist"),
                ignoreMissingSource));
      }

      {
        final boolean codeSignOnCopy = false;
        final boolean ignoreIfMissing = dryRunCodeSigning;
        Optional<String> newNameAfterCopy = Optional.empty();
        AppleBundleDestination dest =
            AppleProvisioningProfileUtilities.getProvisioningProfileBundleDestination(
                appleCxxPlatform.getAppleSdk().getApplePlatform());
        SourcePath provisioningProfileSourcePath =
            codeSignPrepRule.getSourcePathToProvisioningProfile();
        bundlePartsReadyToCopy.add(
            FileAppleBundlePart.of(
                provisioningProfileSourcePath,
                dest,
                getSourcePathToContentHash(
                    incrementalBundlingEnabled,
                    provisioningProfileSourcePath,
                    codeSignPrepRule.getBuildTarget(),
                    graphBuilder,
                    projectFilesystem,
                    shouldCacheFileHashes,
                    false),
                codeSignOnCopy,
                newNameAfterCopy,
                ignoreIfMissing));
      }
    } else {
      codeSignIdentitiesSupplier = Suppliers.ofInstance(ImmutableList.of());
      infoPlistReadyToCopy = infoPlist.getSourcePathToOutput();
      if (useEntitlementsWhenAdhocCodeSigning && codeSignType == ADHOC) {
        entitlementsReadyForCodeSign = maybeEntitlements;
      } else {
        entitlementsReadyForCodeSign = Optional.empty();
      }
      codeSignIdentityFingerprint = Optional.empty();
    }

    RelPath infoPlistFileBundlePath;
    {
      AppleBundleDestination destination = AppleBundleDestination.METADATA;
      bundlePartsReadyToCopy.add(
          FileAppleBundlePart.of(
              infoPlistReadyToCopy,
              destination,
              getSourcePathToContentHash(
                  incrementalBundlingEnabled,
                  infoPlistReadyToCopy,
                  buildTarget,
                  graphBuilder,
                  projectFilesystem,
                  shouldCacheFileHashes,
                  true)));
      infoPlistFileBundlePath =
          RelPath.of(destination.getPath(destinations)).resolveRel("Info.plist");
    }

    if (unwrappedBinary.getSourcePathToOutput() != null) {
      SourcePath binarySourcePath = unwrappedBinary.getSourcePathToOutput();
      Optional<SourcePath> binaryHashSourcePath =
          getSourcePathToContentHash(
              incrementalBundlingEnabled,
              binarySourcePath,
              unwrappedBinary.getBuildTarget(),
              graphBuilder,
              projectFilesystem,
              shouldCacheFileHashes,
              true);
      {
        final boolean codeSignOnCopy = false;
        final boolean ignoreIfMissing = false;
        String binaryName = AppleBundle.getBinaryName(buildTarget, productName);
        bundlePartsReadyToCopy.add(
            FileAppleBundlePart.of(
                binarySourcePath,
                AppleBundleDestination.EXECUTABLES,
                binaryHashSourcePath,
                codeSignOnCopy,
                Optional.of(binaryName),
                ignoreIfMissing));
      }

      if (AppleBundleSupport.isWatchKitStubNeeded(
          unwrappedExtension, unwrappedBinary, appleCxxPlatform.getAppleSdk().getApplePlatform())) {
        final boolean codeSignOnCopy = false;
        final boolean ignoreIfMissing = false;
        bundlePartsReadyToCopy.add(
            FileAppleBundlePart.of(
                unwrappedBinary.getSourcePathToOutput(),
                AppleBundleDestination.WATCHKITSTUB,
                binaryHashSourcePath,
                codeSignOnCopy,
                Optional.of("WK"),
                ignoreIfMissing));
      }
    }

    {
      final boolean codeSignOnCopy = true;

      for (SourcePath framework : frameworks) {
        Optional<SourcePath> maybeSourcePathToIncrementalInfo =
            frameworkIncrementalInfo(framework, graphBuilder);
        if (maybeSourcePathToIncrementalInfo.isPresent()) {
          bundlePartsReadyToCopy.add(
              EmbeddedBundleAppleBundlePart.of(
                  framework,
                  AppleBundleDestination.FRAMEWORKS,
                  maybeSourcePathToIncrementalInfo.get(),
                  codeSignOnCopy));
        } else {
          bundlePartsReadyToCopy.add(
              DirectoryAppleBundlePart.of(
                  framework,
                  AppleBundleDestination.FRAMEWORKS,
                  getSourcePathToContentHash(
                      incrementalBundlingEnabled,
                      framework,
                      buildTarget,
                      graphBuilder,
                      projectFilesystem,
                      shouldCacheFileHashes,
                      true),
                  codeSignOnCopy));
        }
      }
    }

    AppleBundleResources collectedResources =
        AppleResources.collectResourceDirsAndFiles(
            xcodeDescriptions,
            targetGraph,
            graphBuilder,
            Optional.empty(),
            targetGraph.get(buildTarget),
            appleCxxPlatform,
            RecursiveDependenciesMode.COPYING,
            filter);

    BuildTarget processResourcesTarget =
        stripBundleSpecificFlavors(buildTarget).withAppendedFlavors(AppleProcessResources.FLAVOR);
    AppleProcessResources processResources =
        (AppleProcessResources)
            graphBuilder.computeIfAbsent(
                processResourcesTarget,
                target ->
                    new AppleProcessResources(
                        target,
                        projectFilesystem,
                        graphBuilder,
                        collectedResources.getResourceFiles(),
                        collectedResources.getResourceVariantFiles(),
                        ibtoolFlagsUnwrapped,
                        AppleBundleSupport.isLegacyWatchApp(unwrappedExtension, unwrappedBinary),
                        appleCxxPlatform.getIbtool(),
                        ibtoolModuleFlag.orElse(false),
                        buildTarget,
                        Optional.of(AppleBundle.getBinaryName(buildTarget, productName)),
                        withDownwardApi,
                        appleCxxPlatform.getAppleSdk().getApplePlatform(),
                        destinations,
                        incrementalBundlingEnabled));

    Optional<SourcePath> nonProcessedResourcesContentHashesFileSourcePath;
    if (incrementalBundlingEnabled) {
      BuildTarget computeNonProcessedResourcesContentHashTarget =
          stripBundleSpecificFlavors(buildTarget)
              .withAppendedFlavors(AppleComputeNonProcessedResourcesContentHashes.FLAVOR);
      AppleComputeNonProcessedResourcesContentHashes computeNonProcessedResourcesContentHashes =
          (AppleComputeNonProcessedResourcesContentHashes)
              graphBuilder.computeIfAbsent(
                  computeNonProcessedResourcesContentHashTarget,
                  target ->
                      new AppleComputeNonProcessedResourcesContentHashes(
                          target,
                          projectFilesystem,
                          graphBuilder,
                          collectedResources,
                          destinations));
      nonProcessedResourcesContentHashesFileSourcePath =
          Optional.of(
              Objects.requireNonNull(
                  computeNonProcessedResourcesContentHashes.getSourcePathToOutput()));
    } else {
      nonProcessedResourcesContentHashesFileSourcePath = Optional.empty();
    }

    BuildRuleParams bundleParamsWithFlavoredBinaryDep =
        getBundleParamsWithUpdatedDeps(
            params,
            binaryTarget,
            ImmutableSet.<BuildRule>builder()
                .add(targetDebuggableBinaryRule)
                .addAll(
                    BuildRules.toBuildRulesFor(
                        buildTarget,
                        graphBuilder,
                        RichStream.from(collectedResources.getAllSourcePaths())
                            .concat(frameworks.stream())
                            .filter(BuildTargetSourcePath.class)
                            .map(BuildTargetSourcePath::getTarget)
                            .collect(ImmutableSet.toImmutableSet())))
                .addAll(RichStream.from(appleDsym).iterator())
                .addAll(extraBinaries)
                .build());

    return new AppleBundle(
        buildTarget,
        projectFilesystem,
        bundleParamsWithFlavoredBinaryDep,
        graphBuilder,
        unwrappedExtension,
        productName,
        infoPlistFileBundlePath,
        unwrappedBinary,
        appleDsym,
        destinations,
        collectedResources,
        bundlePartsReadyToCopy.build(),
        appleCxxPlatform,
        tests,
        codeSignIdentitiesSupplier,
        codeSignType,
        cacheable,
        verifyResources,
        codesignFlags,
        codesignAdhocIdentity,
        codesignTimeout,
        copySwiftStdlibToFrameworks,
        sliceAppPackageSwiftRuntime,
        sliceAppBundleSwiftRuntime,
        withDownwardApi,
        entitlementsReadyForCodeSign,
        dryRunCodeSigning,
        codeSignIdentityFingerprint,
        processResources.getSourcePathToOutput(),
        nonProcessedResourcesContentHashesFileSourcePath,
        processResources.getSourcePathToContentHashes(),
        incrementalBundlingEnabled,
        bundleInputBasedRulekeyEnabled,
        parallelCodeSignOnCopyEnabled);
  }

  private static Optional<SourcePath> frameworkIncrementalInfo(
      SourcePath frameworkSourcePath, ActionGraphBuilder graphBuilder) {
    if (!(frameworkSourcePath instanceof BuildTargetSourcePath)) {
      return Optional.empty();
    }
    BuildTarget frameworkTarget = ((BuildTargetSourcePath) frameworkSourcePath).getTarget();
    BuildRule frameworkRule = graphBuilder.requireRule(frameworkTarget);
    if (!(frameworkRule instanceof AppleBundle)) {
      return Optional.empty();
    }
    AppleBundle frameworkBundle = (AppleBundle) frameworkRule;
    return frameworkBundle.getSourcePathToIncrementalInfo();
  }

  private static void addExtraBinariesToBundleParts(
      ImmutableList.Builder<AppleBundlePart> bundlePartsBuilder,
      ImmutableSet<BuildRule> extraBinaries,
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      boolean incrementalBundlingEnabled,
      boolean shouldCacheFileHashes) {
    bundlePartsBuilder.addAll(
        extraBinaries.stream()
            .map(
                buildRule -> {
                  BuildTarget unflavoredTarget = buildRule.getBuildTarget().withFlavors();
                  String binaryName = AppleBundle.getBinaryName(unflavoredTarget, Optional.empty());
                  final boolean codeSignOnCopy = true;
                  final boolean ignoreIfMissing = false;
                  Optional<String> newNameAfterCopy = Optional.of(binaryName);
                  return FileAppleBundlePart.of(
                      buildRule.getSourcePathToOutput(),
                      AppleBundleDestination.EXECUTABLES,
                      getSourcePathToContentHash(
                          incrementalBundlingEnabled,
                          buildRule.getSourcePathToOutput(),
                          buildRule.getBuildTarget(),
                          graphBuilder,
                          projectFilesystem,
                          shouldCacheFileHashes,
                          true),
                      codeSignOnCopy,
                      newNameAfterCopy,
                      ignoreIfMissing);
                })
            .collect(ImmutableSet.toImmutableSet()));
  }

  private static Optional<SourcePath> getSourcePathToContentHash(
      boolean incrementalBundlingEnabled,
      SourcePath sourcePath,
      BuildTarget baseTarget,
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      boolean shouldCacheFileHashes,
      boolean sourcePathIsTheOnlyFileToBeHashedFromGeneratingTarget) {
    if (!incrementalBundlingEnabled) {
      return Optional.empty();
    }
    BuildTarget hashTarget =
        SourcePathSupport.generateBuildTargetForSourcePathWithoutUniquenessCheck(
            sourcePath,
            baseTarget,
            "ib-hash-",
            sourcePathIsTheOnlyFileToBeHashedFromGeneratingTarget);
    AppleWriteFileHash calculateHash =
        (AppleWriteFileHash)
            graphBuilder.computeIfAbsent(
                hashTarget,
                target ->
                    new AppleWriteFileHash(
                        target,
                        projectFilesystem,
                        graphBuilder,
                        sourcePath,
                        true,
                        shouldCacheFileHashes));
    return Optional.of(calculateHash.getSourcePathToOutput());
  }

  private static Optional<SourcePath> getSourcePathToContentHashesOfDirectory(
      boolean incrementalBundlingEnabled,
      SourcePath directorySourcePath,
      BuildTarget directoryProducingTarget,
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem) {
    if (!incrementalBundlingEnabled) {
      return Optional.empty();
    }

    BuildTarget calculateHashTarget =
        directoryProducingTarget.withAppendedFlavors(AppleComputeDirectoryContentHashes.FLAVOR);

    AppleComputeDirectoryContentHashes calculateHash =
        (AppleComputeDirectoryContentHashes)
            graphBuilder.computeIfAbsent(
                calculateHashTarget,
                target ->
                    new AppleComputeDirectoryContentHashes(
                        target, projectFilesystem, graphBuilder, directorySourcePath));
    return Optional.of(calculateHash.getSourcePathToOutput());
  }

  private static void addPkgInfoIfNeededToBundleParts(
      boolean incrementalBundlingEnabled,
      ImmutableList.Builder<AppleBundlePart> bundlePartsBuilder,
      BuildTarget bundleTarget,
      String extension,
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      boolean shouldCacheFileHashes) {
    if (!ApplePkgInfo.isPkgInfoNeeded(extension)) {
      return;
    }

    BuildTarget pkgInfoBuildTarget =
        stripBundleSpecificFlavors(bundleTarget).withAppendedFlavors(ApplePkgInfo.FLAVOR);
    ApplePkgInfo pkgInfo =
        (ApplePkgInfo)
            graphBuilder.computeIfAbsent(
                pkgInfoBuildTarget,
                pkgInfoTarget ->
                    new ApplePkgInfo(pkgInfoBuildTarget, projectFilesystem, graphBuilder));
    bundlePartsBuilder.add(
        FileAppleBundlePart.of(
            pkgInfo.getSourcePathToOutput(),
            AppleBundleDestination.METADATA,
            getSourcePathToContentHash(
                incrementalBundlingEnabled,
                pkgInfo.getSourcePathToOutput(),
                pkgInfoBuildTarget,
                graphBuilder,
                projectFilesystem,
                shouldCacheFileHashes,
                true)));
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
                                + "Please refer to https://dev.buck.build/rule/apple_bundle.html#binary for "
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
      Optional<Flavor> defaultPlatform,
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
    if (!cxxPlatformsProvider.getUnresolvedCxxPlatforms().containsAnyOf(flavors)) {
      flavors =
          ImmutableSet.<Flavor>builder()
              .addAll(flavors)
              .add(
                  (cxxPlatformsProvider
                          .getUnresolvedCxxPlatforms()
                          .getValue(
                              defaultPlatform
                                  .map(Collections::singleton)
                                  .orElse(Collections.emptySet()))
                          .orElse(cxxPlatformsProvider.getDefaultUnresolvedCxxPlatform()))
                      .getFlavor())
              .build();
    }

    ImmutableSet.Builder<Flavor> binaryFlavorsBuilder = ImmutableSet.builder();
    binaryFlavorsBuilder.addAll(flavors);
    if (!(AppleLibraryDescription.LIBRARY_TYPE.getFlavor(flavors).isPresent())) {
      binaryFlavorsBuilder.addAll(binary.getFlavors().getSet());
    } else {
      binaryFlavorsBuilder.addAll(
          Sets.difference(
              binary.getFlavors().getSet(), AppleLibraryDescription.LIBRARY_TYPE.getFlavors()));
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
                    AppleBundleDescription.SUPPORTED_LIBRARY_FLAVORS,
                    buildTarget.getFlavors().getSet())
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
            params.getExtraDeps().get().stream()
                .filter(notOriginalBinaryRule)
                .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
  }

  private static void addFirstLevelDependencyBundlesToBundleParts(
      SortedSet<BuildRule> dependencies,
      ImmutableList.Builder<AppleBundlePart> bundlePartsBuilder,
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      boolean incrementalBundlingEnabled,
      boolean shouldCacheFileHashes) {
    // We only care about the direct layer of dependencies. ExtensionBundles inside ExtensionBundles
    // do not get pulled in to the top-level Bundle.
    dependencies.stream()
        .filter(d -> d instanceof AppleBundle)
        .map(d -> (AppleBundle) d)
        .forEach(
            appleBundle -> {
              SourcePath sourcePath =
                  Preconditions.checkNotNull(
                      appleBundle.getSourcePathToOutput(),
                      "Path cannot be null for AppleBundle [%s].",
                      appleBundle);

              AppleBundleDestination destination;
              boolean codeSignOnCopy = false;

              if (AppleBundleExtension.APPEX.fileExtension.equals(appleBundle.getExtension())
                  || AppleBundleExtension.APP.fileExtension.equals(appleBundle.getExtension())) {

                String platformName = appleBundle.getPlatformName();

                if ((platformName.equals(ApplePlatform.WATCHOS.getName())
                        || platformName.equals(ApplePlatform.WATCHSIMULATOR.getName()))
                    && appleBundle.getExtension().equals(AppleBundleExtension.APP.fileExtension)) {
                  destination = AppleBundleDestination.WATCHAPP;
                } else if (appleBundle.getIsLegacyWatchApp()) {
                  destination = AppleBundleDestination.RESOURCES;
                } else {
                  destination = AppleBundleDestination.PLUGINS;
                }
              } else if (AppleBundleExtension.FRAMEWORK.fileExtension.equals(
                  appleBundle.getExtension())) {
                destination = AppleBundleDestination.FRAMEWORKS;
                codeSignOnCopy = true;
              } else if (AppleBundleExtension.XPC.fileExtension.equals(
                  appleBundle.getExtension())) {
                destination = AppleBundleDestination.XPCSERVICES;
              } else if (AppleBundleExtension.QLGENERATOR.fileExtension.equals(
                  appleBundle.getExtension())) {
                destination = AppleBundleDestination.QUICKLOOK;
              } else if (AppleBundleExtension.PLUGIN.fileExtension.equals(
                  appleBundle.getExtension())) {
                destination = AppleBundleDestination.PLUGINS;
              } else if (AppleBundleExtension.PREFPANE.fileExtension.equals(
                  appleBundle.getExtension())) {
                destination = AppleBundleDestination.RESOURCES;
              } else {
                return;
              }

              if (appleBundle.getSourcePathToIncrementalInfo().isPresent()) {
                bundlePartsBuilder.add(
                    EmbeddedBundleAppleBundlePart.of(
                        sourcePath,
                        destination,
                        appleBundle.getSourcePathToIncrementalInfo().get(),
                        codeSignOnCopy));
              } else {
                bundlePartsBuilder.add(
                    DirectoryAppleBundlePart.of(
                        sourcePath,
                        destination,
                        getSourcePathToContentHash(
                            incrementalBundlingEnabled,
                            sourcePath,
                            appleBundle.getBuildTarget(),
                            graphBuilder,
                            projectFilesystem,
                            shouldCacheFileHashes,
                            true),
                        codeSignOnCopy));
              }
            });
  }

  private static ImmutableSet<BuildRule> collectFirstLevelExtraBinariesFromDeps(
      ApplePlatformType platformType,
      ImmutableSortedSet<BuildTarget> depBuildTargets,
      BuildTarget primaryBinaryTarget,
      CxxPlatformsProvider cxxPlatformsProvider,
      TargetGraph targetGraph,
      ImmutableSet<Flavor> flavors,
      Optional<Flavor> defaultPlatform,
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
              defaultPlatform,
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
  public static BuildTarget stripBundleSpecificFlavors(BuildTarget buildTarget) {
    return buildTarget.withoutFlavors(BUNDLE_SPECIFIC_FLAVORS);
  }

  public static boolean flavorsDoNotAllowLinkerMapMode(BuildTarget buildTarget) {
    FlavorSet flavors = buildTarget.getFlavors();
    return flavors.contains(CxxCompilationDatabase.COMPILATION_DATABASE)
        || flavors.contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)
        || flavors.contains(CxxLinkGroupMapDatabase.LINK_GROUP_MAP_DATABASE)
        || flavors.contains(CxxDiagnosticsEnhancer.DIAGNOSTIC_AGGREGATION_FLAVOR)
        || flavors.contains(CxxDescriptionEnhancer.STATIC_FLAVOR)
        || flavors.contains(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR)
        || flavors.contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
        || flavors.contains(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR);
  }

  public static boolean targetNodeContainsSwiftSourceCode(
      TargetNode<? extends CxxLibraryDescription.CommonArg> node) {
    for (ForwardRelativePath inputPath : node.getInputs()) {
      if (inputPath.toString().endsWith(".swift")) {
        return true;
      }
    }

    return false;
  }

  static FlavorDomain<UnresolvedAppleCxxPlatform> getAppleCxxPlatformsFlavorDomain(
      ToolchainProvider toolchainProvider, TargetConfiguration toolchainTargetConfiguration) {
    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        toolchainProvider.getByName(
            AppleCxxPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            AppleCxxPlatformsProvider.class);
    return appleCxxPlatformsProvider.getUnresolvedAppleCxxPlatforms();
  }

  static void findToolchainDeps(
      BuildTarget buildTarget,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder,
      ToolchainProvider toolchainProvider) {
    getAppleCxxPlatformsFlavorDomain(toolchainProvider, buildTarget.getTargetConfiguration())
        .getValues()
        .forEach(
            platform ->
                targetGraphOnlyDepsBuilder.addAll(
                    platform.getParseTimeDeps(buildTarget.getTargetConfiguration())));

    // Get any parse time deps from the C/C++ platforms.
    // We need it to properly add cxx_toolchain rule dependency
    // when we build apple_ rules with cxx_toolchain without properly configured apple toolchain.
    targetGraphOnlyDepsBuilder.addAll(
        CxxPlatformParseTimeDeps.getPlatformParseTimeDeps(
            toolchainProvider, buildTarget.getTargetConfiguration()));
  }

  private static AppleInfoPlist createInfoPlistBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePath unprocessedInfoPlistPath,
      Optional<SourcePath> maybeAssetCatalogPlistPath,
      BuildRule binary,
      Optional<String> maybeProductName,
      String extension,
      AppleCxxPlatform appleCxxPlatform,
      Map<String, String> substitutions,
      Optional<String> minimumOSVersion) {
    return new AppleInfoPlist(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        unprocessedInfoPlistPath,
        maybeAssetCatalogPlistPath,
        AppleBundleSupport.isLegacyWatchApp(extension, binary),
        maybeProductName,
        extension,
        appleCxxPlatform,
        substitutions,
        minimumOSVersion);
  }
}
