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
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.SourceWithFlagsList;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Common logic for a {@link com.facebook.buck.rules.Description} that creates Apple target rules.
 */
public class AppleDescriptions {

  private static final SourceList EMPTY_HEADERS = SourceList.ofUnnamedSources(
      ImmutableSortedSet.<SourcePath>of());

  static final String XCASSETS_DIRECTORY_EXTENSION = ".xcassets";
  private static final String MERGED_ASSET_CATALOG_NAME = "Merged";

  /** Utility class: do not instantiate. */
  private AppleDescriptions() {}

  public static Optional<Path> getPathToHeaderSymlinkTree(
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode,
      HeaderVisibility headerVisibility) {
    if (!targetNode.getConstructorArg().getUseBuckHeaderMaps()) {
      return Optional.absent();
    }

    return Optional.of(
        BuildTargets.getGenPath(
            targetNode.getBuildTarget().getUnflavoredBuildTarget(),
            "%s" + AppleHeaderVisibilities.getHeaderSymlinkTreeSuffix(headerVisibility)));
  }

  public static Path getHeaderPathPrefix(
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget) {
    return Paths.get(arg.headerPathPrefix.or(buildTarget.getShortName()));
  }

  public static ImmutableMap<String, SourcePath> convertAppleHeadersToPublicCxxHeaders(
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      AppleNativeTargetDescriptionArg arg) {
    // The exported headers in the populated cxx constructor arg will contain exported headers from
    // the apple constructor arg with the public include style.
    return AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
                pathResolver,
                headerPathPrefix,
                arg.exportedHeaders.or(EMPTY_HEADERS));
  }

  public static ImmutableMap<String, SourcePath> convertAppleHeadersToPrivateCxxHeaders(
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      AppleNativeTargetDescriptionArg arg) {
    // The private headers will contain exported headers with the private include style and private
    // headers with both styles.
    return ImmutableMap.<String, SourcePath>builder()
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
  static ImmutableMap<String, SourcePath> parseAppleHeadersForUseFromOtherTargets(
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
  static ImmutableMap<String, SourcePath> convertToFlatCxxHeaders(
      Path headerPathPrefix,
      Function<SourcePath, Path> sourcePathResolver,
      Set<SourcePath> headerPaths) {
    ImmutableMap.Builder<String, SourcePath> cxxHeaders = ImmutableMap.builder();
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
    ImmutableMap<String, SourcePath> headerMap = ImmutableMap.<String, SourcePath>builder()
        .putAll(
            convertAppleHeadersToPublicCxxHeaders(
                resolver.getPathFunction(),
                headerPathPrefix,
                arg))
        .putAll(
            convertAppleHeadersToPrivateCxxHeaders(
                resolver.getPathFunction(),
                headerPathPrefix,
                arg))
        .build();

    output.srcs = Optional.of(SourceWithFlagsList.ofUnnamedSources(arg.srcs.get()));
    output.platformSrcs = Optional.of(PatternMatchedCollection.<SourceWithFlagsList>of());
    output.headers = Optional.of(SourceList.ofNamedSources(headerMap));
    output.platformHeaders = Optional.of(PatternMatchedCollection.<SourceList>of());
    output.prefixHeaders = Optional.of(ImmutableList.copyOf(arg.prefixHeader.asSet()));
    output.compilerFlags = arg.compilerFlags;
    output.platformCompilerFlags = Optional.of(
        PatternMatchedCollection.<ImmutableList<String>>of());
    output.linkerFlags = Optional.of(
        FluentIterable
            .from(arg.frameworks.transform(frameworksToLinkerFlagsFunction(resolver)).get())
            .append(arg.linkerFlags.get())
            .toList());
    output.platformLinkerFlags = Optional.of(PatternMatchedCollection.<ImmutableList<String>>of());
    output.preprocessorFlags = arg.preprocessorFlags;
    output.platformPreprocessorFlags = Optional.of(
        PatternMatchedCollection.<ImmutableList<String>>of());
    output.langPreprocessorFlags = arg.langPreprocessorFlags;
    output.frameworkSearchPaths =
        arg.frameworks.isPresent() ?
            Optional.of(
                FluentIterable.from(arg.frameworks.get())
                    .transform(
                        FrameworkPath.getUnexpandedSearchPathFunction(
                            resolver.getPathFunction(),
                            Functions.<Path>identity()))
                    .toSet()) :
            Optional.<ImmutableSet<Path>>absent();
    output.lexSrcs = Optional.of(ImmutableList.<SourcePath>of());
    output.yaccSrcs = Optional.of(ImmutableList.<SourcePath>of());
    output.deps = arg.deps;
    // This is intentionally an empty string; we put all prefixes into
    // the header map itself.
    output.headerNamespace = Optional.of("");
    output.tests = arg.tests;
    output.cxxRuntimeType = Optional.absent();
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
      BuildTarget buildTarget,
      boolean linkWhole) {
    populateCxxConstructorArg(
        resolver,
        output,
        arg,
        buildTarget);
    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(arg, buildTarget);
    output.headers = Optional.of(
        SourceList.ofNamedSources(
            convertAppleHeadersToPrivateCxxHeaders(
                resolver.getPathFunction(),
                headerPathPrefix,
                arg)));
    output.exportedPreprocessorFlags = arg.exportedPreprocessorFlags;
    output.exportedHeaders = Optional.of(
        SourceList.ofNamedSources(
            convertAppleHeadersToPublicCxxHeaders(
                resolver.getPathFunction(),
                headerPathPrefix,
                arg)));
    output.exportedPlatformHeaders = Optional.of(PatternMatchedCollection.<SourceList>of());
    output.exportedPreprocessorFlags = Optional.of(ImmutableList.<String>of());
    output.exportedPlatformPreprocessorFlags = Optional.of(
        PatternMatchedCollection.<ImmutableList<String>>of());
    output.exportedLangPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    output.exportedLinkerFlags = Optional.of(
        FluentIterable
            .from(arg.frameworks.transform(frameworksToLinkerFlagsFunction(resolver)).get())
            .append(arg.exportedLinkerFlags.get())
            .toList());
    output.exportedPlatformLinkerFlags = Optional.of(
        PatternMatchedCollection.<ImmutableList<String>>of());
    output.soname = Optional.absent();
    output.forceStatic = Optional.of(false);
    output.linkWhole = Optional.of(linkWhole);
    output.supportedPlatformsRegex = Optional.absent();
  }

  @VisibleForTesting
  static Function<
      ImmutableSortedSet<FrameworkPath>,
      ImmutableList<String>> frameworksToLinkerFlagsFunction(final SourcePathResolver resolver) {
    return new Function<ImmutableSortedSet<FrameworkPath>, ImmutableList<String>>() {
      @Override
      public ImmutableList<String> apply(ImmutableSortedSet<FrameworkPath> input) {
        return FluentIterable
            .from(input)
            .transformAndConcat(linkerFlagsForFrameworkPathFunction(resolver.getPathFunction()))
            .toList();
      }
    };
  }

  @VisibleForTesting
  static Function<
      ImmutableSortedSet<FrameworkPath>,
      ImmutableList<Path>> frameworksToSearchPathsFunction(
      final SourcePathResolver resolver,
      final AppleSdkPaths appleSdkPaths) {
    return new Function<ImmutableSortedSet<FrameworkPath>, ImmutableList<Path>>() {
      @Override
      public ImmutableList<Path> apply(ImmutableSortedSet<FrameworkPath> frameworkPaths) {
        return FluentIterable
            .from(frameworkPaths)
            .transform(
                FrameworkPath.getExpandedSearchPathFunction(
                    resolver.getPathFunction(),
                    appleSdkPaths.resolveFunction()))
            .toList();
      }
    };
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

  private static Function<FrameworkPath, Iterable<String>> linkerFlagsForFrameworkPathFunction(
      final Function<SourcePath, Path> resolver) {
    return new Function<FrameworkPath, Iterable<String>>() {
      @Override
      public Iterable<String> apply(FrameworkPath input) {
        FrameworkPath.FrameworkType frameworkType = input.getFrameworkType(resolver);
        switch (frameworkType) {
          case FRAMEWORK:
            return ImmutableList.of("-framework", input.getName(resolver));
          case LIBRARY:
            return ImmutableList.of("-l" + input.getName(resolver));
        }

        throw new RuntimeException("Unsupported framework type: " + frameworkType);
      }
    };
  }

  public static CollectedAssetCatalogs createBuildRulesForTransitiveAssetCatalogDependencies(
      BuildRuleParams params,
      SourcePathResolver sourcePathResolver,
      ApplePlatform applePlatform,
      Tool actool) {
    TargetNode<?> targetNode = Preconditions.checkNotNull(
        params.getTargetGraph().get(params.getBuildTarget()));

    ImmutableSet<AppleAssetCatalogDescription.Arg> assetCatalogArgs =
        AppleBuildRules.collectRecursiveAssetCatalogs(
            params.getTargetGraph(),
            ImmutableList.of(targetNode));

    ImmutableSortedSet.Builder<Path> mergeableAssetCatalogDirsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<Path> unmergeableAssetCatalogDirsBuilder =
        ImmutableSortedSet.naturalOrder();

    for (AppleAssetCatalogDescription.Arg arg : assetCatalogArgs) {
      if (arg.getCopyToBundles()) {
        unmergeableAssetCatalogDirsBuilder.addAll(arg.dirs);
      } else {
        mergeableAssetCatalogDirsBuilder.addAll(arg.dirs);
      }
    }

    ImmutableSortedSet<Path> mergeableAssetCatalogDirs =
        mergeableAssetCatalogDirsBuilder.build();
    ImmutableSortedSet<Path> unmergeableAssetCatalogDirs =
        unmergeableAssetCatalogDirsBuilder.build();

    Optional<AppleAssetCatalog> mergedAssetCatalog = Optional.absent();
    if (!mergeableAssetCatalogDirs.isEmpty()) {
      BuildRuleParams assetCatalogParams = params.copyWithChanges(
          BuildTarget.builder(params.getBuildTarget())
              .addFlavors(AppleAssetCatalog.getFlavor(
                      ActoolStep.BundlingMode.MERGE_BUNDLES,
                      MERGED_ASSET_CATALOG_NAME))
              .build(),
          Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
          Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
      mergedAssetCatalog = Optional.of(
          new AppleAssetCatalog(
              assetCatalogParams,
              sourcePathResolver,
              applePlatform.getName(),
              actool,
              mergeableAssetCatalogDirs,
              ActoolStep.BundlingMode.MERGE_BUNDLES,
              MERGED_ASSET_CATALOG_NAME));
    }

    ImmutableSet.Builder<AppleAssetCatalog> bundledAssetCatalogsBuilder =
        ImmutableSet.builder();
    for (Path assetDir : unmergeableAssetCatalogDirs) {
      String bundleName = getCatalogNameFromPath(assetDir);
      BuildRuleParams assetCatalogParams = params.copyWithChanges(
          BuildTarget.builder(params.getBuildTarget())
              .addFlavors(AppleAssetCatalog.getFlavor(
                      ActoolStep.BundlingMode.SEPARATE_BUNDLES,
                      bundleName))
              .build(),
          Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
          Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
      bundledAssetCatalogsBuilder.add(
          new AppleAssetCatalog(
              assetCatalogParams,
              sourcePathResolver,
              applePlatform.getName(),
              actool,
              ImmutableSortedSet.of(assetDir),
              ActoolStep.BundlingMode.SEPARATE_BUNDLES,
              bundleName));
    }
    ImmutableSet<AppleAssetCatalog> bundledAssetCatalogs =
        bundledAssetCatalogsBuilder.build();

    return CollectedAssetCatalogs.of(
        mergedAssetCatalog,
        bundledAssetCatalogs);
  }

  private static String getCatalogNameFromPath(Path assetCatalogDir) {
    String name = assetCatalogDir.getFileName().toString();
    if (name.endsWith(AppleDescriptions.XCASSETS_DIRECTORY_EXTENSION)) {
      name = name.substring(
          0,
          name.length() - AppleDescriptions.XCASSETS_DIRECTORY_EXTENSION.length());
    }
    return name;
  }

}
