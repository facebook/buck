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

import com.facebook.buck.cxx.CxxConstructorArg;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

/**
 * Common logic for a {@link com.facebook.buck.rules.Description} that creates Apple target rules.
 */
public class AppleDescriptions {

  public static final Flavor HEADERS = ImmutableFlavor.of("headers");

  private static final BuildRuleType HEADERS_RULE_TYPE = BuildRuleType.of("headers");

  private static final Either<ImmutableSortedSet<SourcePath>, ImmutableMap<String, SourcePath>>
      EMPTY_HEADERS = Either.ofLeft(ImmutableSortedSet.<SourcePath>of());

  /** Utility class: do not instantiate. */
  private AppleDescriptions() {}

  /**
   * Tries to create a {@link BuildRule} based on the flavors of {@code params.getBuildTarget()} and
   * the specified args.
   * If this method does not know how to handle the specified flavors, it returns {@code null}.
   */
  static <A extends AppleNativeTargetDescriptionArg> Optional<BuildRule> createFlavoredRule(
      BuildRuleParams params,
      A args,
      SourcePathResolver pathResolver) {
    BuildTarget target = params.getBuildTarget();
    if (target.getFlavors().contains(HEADERS)) {
      return Optional.of(createHeadersFlavor(params, pathResolver, args));
    } else {
      return Optional.absent();
    }
  }

  /**
   * @return A rule for making the headers of an Apple target available to other targets.
   */
  static BuildRule createHeadersFlavor(
      BuildRuleParams params,
      SourcePathResolver resolver,
      AppleNativeTargetDescriptionArg args) {
    UnflavoredBuildTarget targetForOriginalRule =
        params.getBuildTarget().getUnflavoredBuildTarget();
    BuildTarget headersTarget = BuildTargets.createFlavoredBuildTarget(
        targetForOriginalRule,
        AppleDescriptions.HEADERS);

    BuildRuleParams headerRuleParams = params.copyWithChanges(
        HEADERS_RULE_TYPE,
        headersTarget,
        /* declaredDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(params.getExtraDeps()));

    boolean useBuckHeaderMaps = args.getUseBuckHeaderMaps();

    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(args, params.getBuildTarget());
    ImmutableMap<String, SourcePath> publicHeaders = convertAppleHeadersToPublicCxxHeaders(
        resolver.getPathFunction(),
        headerPathPrefix,
        args);

    return createSymlinkTree(
        headerRuleParams,
        resolver,
        publicHeaders,
        useBuckHeaderMaps);
  }

  public static Path getPathToHeaders(BuildTarget buildTarget) {
    return BuildTargets.getScratchPath(buildTarget, "__%s_public_headers__");
  }

  private static SymlinkTree createSymlinkTree(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      ImmutableMap<String, SourcePath> publicHeaders,
      boolean useBuckHeaderMaps) {
    // Note that the set of headersToCopy may be empty. If so, the returned rule will be a no-op.
    // TODO(mbolin): Make headersToCopy an ImmutableSortedMap once we clean up the iOS codebase and
    // can guarantee that the keys are unique.
    ImmutableMap<Path, SourcePath> headersToCopy;
    if (useBuckHeaderMaps) {
      // No need to copy headers because header maps are used.
      headersToCopy = ImmutableMap.of();
    } else {
      ImmutableMap.Builder<Path, SourcePath> headersToCopyBuilder = ImmutableMap.builder();
      for (Map.Entry<String, SourcePath> entry : publicHeaders.entrySet()) {
        headersToCopyBuilder.put(Paths.get(entry.getKey()), entry.getValue());
      }
      headersToCopy = headersToCopyBuilder.build();
    }

    BuildRuleParams headerParams = params.copyWithDeps(
        /* declaredDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(params.getExtraDeps()));
    Path root = getPathToHeaders(params.getBuildTarget());
    return new SymlinkTree(headerParams, pathResolver, root, headersToCopy);
  }

  public static Optional<Path> getPathToHeaderMap(
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode,
      HeaderVisibility headerVisibility) {
    if (!targetNode.getConstructorArg().getUseBuckHeaderMaps()) {
      return Optional.absent();
    }

    return Optional.of(
        BuildTargets.getGenPath(
            targetNode.getBuildTarget().getUnflavoredBuildTarget(),
            "%s" + AppleHeaderVisibilities.getHeaderMapFileSuffix(headerVisibility)));
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
      Either<ImmutableSortedSet<SourcePath>, ImmutableMap<String, SourcePath>> headers) {
    if (headers.isLeft()) {
      // The user specified a set of header files. For use from other targets, prepend their names
      // with the header path prefix.
      return convertToFlatCxxHeaders(headerPathPrefix, pathResolver, headers.getLeft());
    } else {
      // The user specified a map from include paths to header files. Just use the specified map.
      return headers.getRight();
    }
  }

  @VisibleForTesting
  static ImmutableMap<String, SourcePath> parseAppleHeadersForUseFromTheSameTarget(
      Function<SourcePath, Path> pathResolver,
      Either<ImmutableSortedSet<SourcePath>, ImmutableMap<String, SourcePath>> headers) {
    if (headers.isLeft()) {
      // The user specified a set of header files. Headers can be included from the same target
      // using only their file name without a prefix.
      return convertToFlatCxxHeaders(Paths.get(""), pathResolver, headers.getLeft());
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
      BuildTarget buildTarget,
      final Optional<AppleSdkPaths> appleSdkPaths) {
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

    output.srcs = Optional.of(
        Either.<ImmutableList<SourceWithFlags>, ImmutableMap<String, SourceWithFlags>>ofLeft(
            arg.srcs.get()));
    output.headers = Optional.of(
        Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofRight(
            headerMap));
    output.prefixHeaders = Optional.of(ImmutableList.copyOf(arg.prefixHeader.asSet()));
    output.compilerFlags = arg.compilerFlags;
    output.platformCompilerFlags =
        Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    output.linkerFlags = Optional.of(
        FluentIterable
            .from(arg.frameworks.transform(frameworksToLinkerFlagsFunction(resolver)).get())
            .append(arg.linkerFlags.get())
            .toList());
    output.platformLinkerFlags = Optional.of(
        ImmutableList.<Pair<String, ImmutableList<String>>>of());
    output.preprocessorFlags = arg.preprocessorFlags;
    output.platformPreprocessorFlags =
        Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    output.langPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    if (appleSdkPaths.isPresent()) {
      output.frameworkSearchPaths = arg.frameworks.transform(
          frameworksToSearchPathsFunction(resolver, appleSdkPaths.get()));
    } else {
      output.frameworkSearchPaths = Optional.of(ImmutableList.<Path>of());
    }
    output.lexSrcs = Optional.of(ImmutableList.<SourcePath>of());
    output.yaccSrcs = Optional.of(ImmutableList.<SourcePath>of());
    output.deps = arg.deps;
    // This is intentionally an empty string; we put all prefixes into
    // the header map itself.
    output.headerNamespace = Optional.of("");
    output.tests = arg.tests;
    output.cxxRuntimeType = Optional.absent();
  }

  public static void populateCxxLibraryDescriptionArg(
      SourcePathResolver resolver,
      CxxLibraryDescription.Arg output,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget,
      final Optional<AppleSdkPaths> appleSdkPaths,
      boolean linkWhole) {
    populateCxxConstructorArg(
        resolver,
        output,
        arg,
        buildTarget,
        appleSdkPaths);
    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(arg, buildTarget);

    output.headers = Optional.of(
        Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofRight(
            convertAppleHeadersToPrivateCxxHeaders(
                resolver.getPathFunction(),
                headerPathPrefix,
                arg)));
    output.exportedPreprocessorFlags = arg.exportedPreprocessorFlags;
    output.exportedHeaders = Optional.of(
        Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofRight(
            convertAppleHeadersToPublicCxxHeaders(
                resolver.getPathFunction(),
                headerPathPrefix,
                arg)));
    output.exportedPreprocessorFlags = Optional.of(ImmutableList.<String>of());
    output.exportedPlatformPreprocessorFlags =
        Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    output.exportedLangPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    output.exportedLinkerFlags = Optional.of(
        FluentIterable
            .from(arg.frameworks.transform(frameworksToLinkerFlagsFunction(resolver)).get())
            .append(arg.exportedLinkerFlags.get())
            .toList());
    output.exportedPlatformLinkerFlags =
        Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    output.soname = Optional.absent();
    output.forceStatic = Optional.of(false);
    output.linkWhole = Optional.of(linkWhole);
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
          default:
            throw new RuntimeException("Unsupported framework type: " + frameworkType);
        }
      }
    };
  }

}
