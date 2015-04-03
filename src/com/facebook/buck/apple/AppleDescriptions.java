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
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal.CycleException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
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
      BuildRuleResolver resolver,
      A args,
      AppleConfig appleConfig,
      SourcePathResolver pathResolver) {
    BuildTarget target = params.getBuildTarget();
    if (target.getFlavors().contains(CompilationDatabase.COMPILATION_DATABASE)) {
      BuildRule compilationDatabase = createCompilationDatabase(
          params,
          resolver,
          args,
          appleConfig,
          pathResolver);
      return Optional.of(compilationDatabase);
    } else if (target.getFlavors().contains(HEADERS)) {
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

    boolean useBuckHeaderMaps = args.useBuckHeaderMaps.or(Boolean.FALSE);

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

  /**
   * @return A compilation database with entries for the files in the specified
   *     {@code targetSources}.
   */
  private static CompilationDatabase createCompilationDatabase(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      AppleNativeTargetDescriptionArg args,
      AppleConfig appleConfig,
      SourcePathResolver pathResolver) {
    CompilationDatabaseTraversal traversal = new CompilationDatabaseTraversal(
        params.getTargetGraph(),
        buildRuleResolver);
    Iterable<TargetNode<AppleNativeTargetDescriptionArg>> startNodes = filterAppleNativeTargetNodes(
        FluentIterable
            .from(params.getDeclaredDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .transform(params.getTargetGraph().get())
            .append(params.getTargetGraph().get(params.getBuildTarget())));
    try {
      traversal.traverse(startNodes);
    } catch (CycleException | IOException | InterruptedException e) {
      throw new RuntimeException(e);
    }

    BuildRuleParams compilationDatabaseParams = params.copyWithDeps(
        /* declaredDeps */ Suppliers.ofInstance(traversal.deps.build()),
        Suppliers.ofInstance(params.getExtraDeps()));

    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(args, params.getBuildTarget());

    return new CompilationDatabase(
        compilationDatabaseParams,
        pathResolver,
        appleConfig,
        ImmutableSortedSet.copyOf(args.srcs.get()),
        ImmutableSortedSet.copyOf(
            convertAppleHeadersToPublicCxxHeaders(
                pathResolver.getPathFunction(),
                headerPathPrefix,
                args).values()),
        ImmutableSortedSet.copyOf(
            convertAppleHeadersToPrivateCxxHeaders(
                pathResolver.getPathFunction(),
                headerPathPrefix,
                args).values()),
        args.frameworks.get(),
        traversal.includePaths.build(),
        args.prefixHeader);
  }

  private static FluentIterable<TargetNode<AppleNativeTargetDescriptionArg>>
      filterAppleNativeTargetNodes(FluentIterable<TargetNode<?>> fluentIterable) {
    return fluentIterable
        .filter(
            new Predicate<TargetNode<?>>() {
              @Override
              public boolean apply(TargetNode<?> input) {
                return ImmutableSet
                    .of(AppleBinaryDescription.TYPE, AppleLibraryDescription.TYPE)
                    .contains(input.getType());
              }
            })
        .transform(
            new Function<TargetNode<?>, TargetNode<AppleNativeTargetDescriptionArg>>() {
              @Override
              @SuppressWarnings("unchecked")
              public TargetNode<AppleNativeTargetDescriptionArg> apply(TargetNode<?> input) {
                return (TargetNode<AppleNativeTargetDescriptionArg>) input;
              }
            });
  }

  private static class CompilationDatabaseTraversal
      extends AbstractAcyclicDepthFirstPostOrderTraversal<
      TargetNode<AppleNativeTargetDescriptionArg>> {

    private final TargetGraph targetGraph;
    private final BuildRuleResolver buildRuleResolver;
    private final ImmutableSet.Builder<Path> includePaths;
    private final ImmutableSortedSet.Builder<BuildRule> deps;

    private CompilationDatabaseTraversal(
        TargetGraph targetGraph,
        BuildRuleResolver buildRuleResolver) {
      this.targetGraph = targetGraph;
      this.buildRuleResolver = buildRuleResolver;
      this.includePaths = ImmutableSet.builder();
      this.deps = ImmutableSortedSet.naturalOrder();
    }

    @Override
    protected Iterator<TargetNode<AppleNativeTargetDescriptionArg>> findChildren(
        TargetNode<AppleNativeTargetDescriptionArg> node)
        throws IOException, InterruptedException {
      return filterAppleNativeTargetNodes(
          FluentIterable.from(node.getDeclaredDeps()).transform(targetGraph.get())).iterator();
    }

    @Override
    protected void onNodeExplored(TargetNode<AppleNativeTargetDescriptionArg> node)
        throws IOException, InterruptedException {
      if (node.getConstructorArg().getUseBuckHeaderMaps()) {
        // TODO(user): Currently, header maps are created by `buck project`. Eventually they
        // should become build dependencies. When that happens, rule should be added to this.deps.
        Path headerMap = getPathToHeaderMap(node, HeaderMapType.PUBLIC_HEADER_MAP).get();
        includePaths.add(headerMap);
      } else {
        // In this case, we need the #headers flavor of node so the path to its public headers
        // directory can be included. First, we perform a defensive check to make sure that node is
        // an unflavored node because it may not be safe to request the #headers of a flavored node.
        if (node.getBuildTarget().isFlavored()) {
          return;
        }
        UnflavoredBuildTarget buildTarget = node.getBuildTarget().checkUnflavored();

        // Next, we get the #headers flavor of the rule.
        BuildTarget targetForHeaders = BuildTargets.createFlavoredBuildTarget(
            buildTarget,
            AppleDescriptions.HEADERS);
        Optional<BuildRule> buildRule = buildRuleResolver.getRuleOptional(targetForHeaders);
        if (!buildRule.isPresent()) {
          BuildRule newBuildRule = node.getDescription().createBuildRule(
              new BuildRuleParams(
                  targetForHeaders,
                  Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
                  Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
                  node.getRuleFactoryParams().getProjectFilesystem(),
                  node.getRuleFactoryParams().getRuleKeyBuilderFactory(),
                  node.getType(),
                  targetGraph),
              buildRuleResolver,
              node.getConstructorArg());
          buildRuleResolver.addToIndex(newBuildRule);
          buildRule = Optional.of(newBuildRule);
        }
        SymlinkTree headersRule = (SymlinkTree) buildRule.get();

        // Finally, we make sure the rule has public headers before adding it to includePaths.
        Optional<Path> headersDirectory = headersRule.getRootOfSymlinksDirectory();
        if (headersDirectory.isPresent()) {
          includePaths.add(headersDirectory.get());
          deps.add(headersRule);
        }
      }
    }

    @Override
    protected void onTraversalComplete(
        Iterable<TargetNode<AppleNativeTargetDescriptionArg>> nodesInExplorationOrder) {
      // Nothing to do: work is done in onNodeExplored.
    }
  }

  public static Optional<Path> getPathToHeaderMap(
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode,
      HeaderMapType headerMapType) {
    if (!targetNode.getConstructorArg().useBuckHeaderMaps.get()) {
      return Optional.absent();
    }

    return Optional.of(
        BuildTargets.getGenPath(
          targetNode.getBuildTarget().getUnflavoredBuildTarget(),
          "%s" + headerMapType.getSuffix()));
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
    output.soname = Optional.absent();
    output.linkWhole = Optional.of(linkWhole);

    output.exportedLinkerFlags = Optional.of(ImmutableList.<String>of());
    output.exportedPlatformLinkerFlags =
        Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
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
