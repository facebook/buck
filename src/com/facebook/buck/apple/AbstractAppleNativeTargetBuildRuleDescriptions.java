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

import com.facebook.buck.apple.AbstractAppleNativeTargetBuildRule.HeaderMapType;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal.CycleException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetNode;
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
 * Common logic for a {@link com.facebook.buck.rules.Description} that creates
 * {@link AbstractAppleNativeTargetBuildRule} rules.
 */
public class AbstractAppleNativeTargetBuildRuleDescriptions {

  public static final Flavor HEADERS = new Flavor("headers");

  private static final BuildRuleType HEADERS_RULE_TYPE = new BuildRuleType("headers");

  /** Utility class: do not instantiate. */
  private AbstractAppleNativeTargetBuildRuleDescriptions() {}

  /**
   * Tries to create a flavored version of a {@link AbstractAppleNativeTargetBuildRule} based on
   * the flavors of {@code params.getBuildTarget().getFlavors()} and using the specified args.
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
    } else {
      return Optional.absent();
    }
  }

  /**
   * Takes the arguments for the description of an unflavored
   * {@link AbstractAppleNativeTargetBuildRule} and creates the rule/action for the corresponding
   * {@link AbstractAppleNativeTargetBuildRuleDescriptions#HEADERS} flavor if it is not already
   * present in the specified {@code resolver}. (This could happen if another rule has already
   * requested the {@code #headers} flavor for this rule.)
   */
  static BuildRule createHeadersFlavorIfNotAlreadyPresent(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      AppleNativeTargetDescriptionArg args) {
    BuildTarget targetForOriginalRule = params.getBuildTarget();
    if (targetForOriginalRule.isFlavored()) {
      targetForOriginalRule = targetForOriginalRule.getUnflavoredTarget();
    }
    BuildTarget headersTarget = BuildTargets.createFlavoredBuildTarget(
        targetForOriginalRule,
        AbstractAppleNativeTargetBuildRuleDescriptions.HEADERS);

    Optional<BuildRule> headersRuleOption = resolver.getRuleOptional(headersTarget);
    if (headersRuleOption.isPresent()) {
      return headersRuleOption.get();
    }

    BuildRuleParams headerRuleParams = params.copyWithChanges(
        HEADERS_RULE_TYPE,
        headersTarget,
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(),
        params.getExtraDeps());

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    TargetSources targetSources = TargetSources.ofAppleSources(pathResolver, args.srcs.get());
    ImmutableSortedMap<SourcePath, String> perFileFlags = targetSources.perFileFlags;
    boolean useBuckHeaderMaps = args.useBuckHeaderMaps.or(Boolean.FALSE);
    SymlinkTree headersRule = createCopyPublicHeadersAction(
        headerRuleParams,
        pathResolver,
        perFileFlags,
        useBuckHeaderMaps);

    return headersRule;
  }

  private static SymlinkTree createCopyPublicHeadersAction(
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
      // slightly different heuristic, which is buildTarget.getShortNameOnly(), though that is only
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
    Path root = BuildTargets.getBinPath(headerParams.getBuildTarget(), "__%s_public_headers__");
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
    CompilationDatabaseTraversal traversal = new CompilationDatabaseTraversal(buildRuleResolver);
    Iterable<AbstractAppleNativeTargetBuildRule> startNodes = FluentIterable
        .from(params.getDeclaredDeps())
        .filter(AbstractAppleNativeTargetBuildRule.class);
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

  private static class CompilationDatabaseTraversal
      extends AbstractAcyclicDepthFirstPostOrderTraversal<AbstractAppleNativeTargetBuildRule> {

    private final BuildRuleResolver buildRuleResolver;
    private final ImmutableSet.Builder<Path> includePaths;
    private final ImmutableSortedSet.Builder<BuildRule> deps;

    private CompilationDatabaseTraversal(BuildRuleResolver buildRuleResolver) {
      this.buildRuleResolver = buildRuleResolver;
      this.includePaths = ImmutableSet.builder();
      this.deps = ImmutableSortedSet.naturalOrder();
    }

    @Override
    protected Iterator<AbstractAppleNativeTargetBuildRule> findChildren(
        AbstractAppleNativeTargetBuildRule rule)
        throws IOException, InterruptedException {
      return FluentIterable.from(rule.getDeps()).filter(AbstractAppleNativeTargetBuildRule.class)
          .iterator();
    }

    @Override
    protected void onNodeExplored(AbstractAppleNativeTargetBuildRule rule)
        throws IOException, InterruptedException {
      if (rule.getUseBuckHeaderMaps()) {
        // TODO(user): Currently, header maps are created by `buck project`. Eventually they
        // should become build dependencies. When that happens, rule should be added to this.deps.
        Path headerMap = rule.getPathToHeaderMap(HeaderMapType.PUBLIC_HEADER_MAP).get();
        includePaths.add(headerMap);
      } else {
        // In this case, we need the #headers flavor of rule so the path to its public headers
        // directory can be included. First, we perform a defensive check to make sure that rule is
        // an unflavored rule because it may not be safe to request the #headers of a flavored rule.
        BuildTarget buildTarget = rule.getBuildTarget();
        if (buildTarget.isFlavored()) {
          return;
        }

        // Next, we get the #headers flavor of the rule.
        BuildTarget targetForHeaders = BuildTargets.createFlavoredBuildTarget(
            buildTarget,
            AbstractAppleNativeTargetBuildRuleDescriptions.HEADERS);
        SymlinkTree headersRule = (SymlinkTree) buildRuleResolver.getRule(targetForHeaders);

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
        Iterable<AbstractAppleNativeTargetBuildRule> nodesInExplorationOrder) {
      // Nothing to do: work is done in onNodeExplored.
    }
  }

  public static Optional<Path> getPathToHeaderMap(
      TargetNode<AppleNativeTargetDescriptionArg> targetNode,
      HeaderMapType headerMapType) {
    if (!targetNode.getConstructorArg().useBuckHeaderMaps.get()) {
      return Optional.absent();
    }

    return Optional.of(
        BuildTargets.getGenPath(targetNode.getBuildTarget(), "%s" + headerMapType.getSuffix()));
  }

}
