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

import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal.CycleException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

/**
 * Common logic for a {@link com.facebook.buck.rules.Description} that creates Apple target rules.
 */
public class AbstractAppleNativeTargetBuildRuleDescriptions {

  public static final Flavor HEADERS = new Flavor("headers");

  private static final BuildRuleType HEADERS_RULE_TYPE = new BuildRuleType("headers");

  /** Utility class: do not instantiate. */
  private AbstractAppleNativeTargetBuildRuleDescriptions() {}

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
      SourcePathResolver pathResolver,
      TargetSources targetSources) {
    BuildTarget target = params.getBuildTarget();
    if (target.getFlavors().contains(CompilationDatabase.COMPILATION_DATABASE)) {
      BuildRule compilationDatabase = createCompilationDatabase(
          params,
          resolver,
          args,
          appleConfig,
          pathResolver,
          targetSources);
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
    BuildTarget targetForOriginalRule = params.getBuildTarget();
    if (targetForOriginalRule.isFlavored()) {
      targetForOriginalRule = targetForOriginalRule.getUnflavoredTarget();
    }
    BuildTarget headersTarget = BuildTargets.createFlavoredBuildTarget(
        targetForOriginalRule,
        AbstractAppleNativeTargetBuildRuleDescriptions.HEADERS);

    BuildRuleParams headerRuleParams = params.copyWithChanges(
        HEADERS_RULE_TYPE,
        headersTarget,
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(),
        params.getExtraDeps());

    TargetSources targetSources = TargetSources.ofAppleSources(resolver, args.srcs.get());
    ImmutableSortedMap<SourcePath, String> perFileFlags = targetSources.perFileFlags;
    boolean useBuckHeaderMaps = args.useBuckHeaderMaps.or(Boolean.FALSE);
    return createSymlinkTree(
        headerRuleParams,
        resolver,
        perFileFlags,
        useBuckHeaderMaps);
  }

  public static Path getPathToHeaders(BuildTarget buildTarget) {
    return BuildTargets.getBinPath(buildTarget, "__%s_public_headers__");
  }

  private static SymlinkTree createSymlinkTree(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      ImmutableSortedMap<SourcePath, String> perFileFlags,
      boolean useBuckHeaderMaps) {
    // Note that the set of headersToCopy may be empty. If so, the returned rule will be a no-op.
    // TODO(mbolin): Make headersToCopy an ImmutableSortedMap once we clean up the iOS codebase and
    // can guarantee that the keys are unique.
    Map<Path, SourcePath> headersToCopy;
    if (useBuckHeaderMaps) {
      // No need to copy headers because header maps are used.
      headersToCopy = ImmutableSortedMap.of();
    } else {
      // This is a heuristic to get the right header path prefix. Note that ProjectGenerator uses a
      // slightly different heuristic, which is buildTarget.getShortName(), though that is only
      // a fallback when an apple_library() does not specify a header_path_prefix when
      // use_buck_header_maps is True.
      Path headerPathPrefix = params.getBuildTarget().getBasePath().getFileName();

      headersToCopy = Maps.newHashMap();
      Splitter spaceSplitter = Splitter.on(' ').trimResults().omitEmptyStrings();
      Predicate<String> isPublicHeaderFlag = Predicates.equalTo("public");
      for (Map.Entry<SourcePath, String> entry : perFileFlags.entrySet()) {
        String flags = entry.getValue();
        if (Iterables.any(spaceSplitter.split(flags), isPublicHeaderFlag)) {
          SourcePath sourcePath = entry.getKey();
          Path sourcePathName = pathResolver.getPath(sourcePath).getFileName();
          headersToCopy.put(headerPathPrefix.resolve(sourcePathName), sourcePath);
        }
      }
    }

    BuildRuleParams headerParams = params.copyWithDeps(
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(),
        params.getExtraDeps());
    Path root = getPathToHeaders(params.getBuildTarget());
    return new SymlinkTree(headerParams, pathResolver, root, ImmutableMap.copyOf(headersToCopy));
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
      SourcePathResolver pathResolver,
      TargetSources targetSources) {
    CompilationDatabaseTraversal traversal = new CompilationDatabaseTraversal(
        params.getTargetGraph(),
        buildRuleResolver);
    Iterable<TargetNode<AppleNativeTargetDescriptionArg>> startNodes = filterAppleNativeTargetNodes(
        FluentIterable
            .from(params.getDeclaredDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .transform(params.getTargetGraph().get()));
    try {
      traversal.traverse(startNodes);
    } catch (CycleException | IOException | InterruptedException e) {
      throw new RuntimeException(e);
    }

    BuildRuleParams compilationDatabaseParams = params.copyWithDeps(
        /* declaredDeps */ traversal.deps.build(),
        params.getExtraDeps());

    return new CompilationDatabase(
        compilationDatabaseParams,
        pathResolver,
        appleConfig,
        targetSources,
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
        BuildTarget buildTarget = node.getBuildTarget();
        if (buildTarget.isFlavored()) {
          return;
        }

        // Next, we get the #headers flavor of the rule.
        BuildTarget targetForHeaders = BuildTargets.createFlavoredBuildTarget(
            buildTarget,
            AbstractAppleNativeTargetBuildRuleDescriptions.HEADERS);
        Optional<BuildRule> buildRule = buildRuleResolver.getRuleOptional(targetForHeaders);
        if (!buildRule.isPresent()) {
          BuildRule newBuildRule = node.getDescription().createBuildRule(
              new BuildRuleParams(
                  targetForHeaders,
                  ImmutableSortedSet.<BuildRule>of(),
                  ImmutableSortedSet.<BuildRule>of(),
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
        BuildTargets.getGenPath(targetNode.getBuildTarget(), "%s" + headerMapType.getSuffix()));
  }

}
