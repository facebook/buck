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

package com.facebook.buck.cxx;

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

public class CxxPreprocessables {

  private CxxPreprocessables() {}

  public enum IncludeType {
    /**
     * Headers should be included with `-I`.
     */
    LOCAL,
    /**
     * Headers should be included with `-isystem`.
     */
    SYSTEM,
  }

  /**
   * Resolve the map of name to {@link SourcePath} to a map of full header name to
   * {@link SourcePath}.
   */
  public static ImmutableMap<Path, SourcePath> resolveHeaderMap(
      Path basePath,
      ImmutableMap<String, SourcePath> headers) {

    ImmutableMap.Builder<Path, SourcePath> headerMap = ImmutableMap.builder();

    // Resolve the "names" of the headers to actual paths by prepending the base path
    // specified by the build target.
    for (ImmutableMap.Entry<String, SourcePath> ent : headers.entrySet()) {
      Path path = basePath.resolve(ent.getKey());
      headerMap.put(path, ent.getValue());
    }

    return headerMap.build();
  }

  /**
   * Find and return the {@link CxxPreprocessorInput} objects from {@link CxxPreprocessorDep}
   * found while traversing the dependencies starting from the {@link BuildRule} objects given.
   */
  public static Collection<CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      final TargetGraph targetGraph,
      final CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      final Predicate<Object> traverse) {

    // We don't really care about the order we get back here, since headers shouldn't
    // conflict.  However, we want something that's deterministic, so sort by build
    // target.
    final Map<BuildTarget, CxxPreprocessorInput> deps = Maps.newLinkedHashMap();

    // Build up the map of all C/C++ preprocessable dependencies.
    AbstractBreadthFirstTraversal<BuildRule> visitor =
        new AbstractBreadthFirstTraversal<BuildRule>(inputs) {
          @Override
          public ImmutableSet<BuildRule> visit(BuildRule rule) {
            if (rule instanceof CxxPreprocessorDep) {
              CxxPreprocessorDep dep = (CxxPreprocessorDep) rule;
              deps.putAll(
                  dep.getTransitiveCxxPreprocessorInput(
                      targetGraph,
                      cxxPlatform,
                      HeaderVisibility.PUBLIC));
              return ImmutableSet.of();
            }
            return traverse.apply(rule) ? rule.getDeps() : ImmutableSet.<BuildRule>of();
          }
        };
    visitor.start();

    // Grab the cxx preprocessor inputs and return them.
    return deps.values();
  }

  public static Collection<CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      TargetGraph targetGraph,
      final CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs) {
    return getTransitiveCxxPreprocessorInput(
        targetGraph,
        cxxPlatform,
        inputs,
        Predicates.alwaysTrue());
  }

  /**
   * Build the {@link HeaderSymlinkTree} rule using the original build params from a target node.
   * In particular, make sure to drop all dependencies from the original build rule params,
   * as these are modeled via {@link CxxPreprocessAndCompile}.
   */
  public static HeaderSymlinkTree createHeaderSymlinkTreeBuildRule(
      SourcePathResolver resolver,
      BuildTarget target,
      BuildRuleParams params,
      Path root,
      Optional<Path> headerMapPath,
      ImmutableMap<Path, SourcePath> links) {
    // Symlink trees never need to depend on anything.
    BuildRuleParams paramsWithoutDeps =
        params.copyWithChanges(
            target,
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));

    try {
      if (headerMapPath.isPresent()) {
        return new HeaderSymlinkTreeWithHeaderMap(
            paramsWithoutDeps,
            resolver,
            root,
            headerMapPath.get(),
            links);
      } else {
        return new HeaderSymlinkTree(
            paramsWithoutDeps,
            resolver,
            root,
            links);
      }
    } catch (SymlinkTree.InvalidSymlinkTreeException e) {
      throw e.getHumanReadableExceptionForBuildTarget(target);
    }
  }

  /**
   * Builds a {@link CxxPreprocessorInput} for a rule.
   */
  public static CxxPreprocessorInput getCxxPreprocessorInput(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      Flavor flavor,
      HeaderVisibility headerVisibility,
      IncludeType includeType,
      Multimap<CxxSource.Type, String> exportedPreprocessorFlags,
      Iterable<Path> frameworkSearchPaths) {
    BuildRule rule = CxxDescriptionEnhancer.requireBuildRule(
        targetGraph,
        params,
        ruleResolver,
        flavor,
        CxxDescriptionEnhancer.getHeaderSymlinkTreeFlavor(headerVisibility));
    Preconditions.checkState(rule instanceof HeaderSymlinkTree);
    HeaderSymlinkTree symlinkTree = (HeaderSymlinkTree) rule;
    CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder()
        .addRules(symlinkTree.getBuildTarget())
        .putAllPreprocessorFlags(exportedPreprocessorFlags)
        .setIncludes(
            CxxHeaders.builder()
                .setPrefixHeaders(ImmutableSortedSet.<SourcePath>of())
                .setNameToPathMap(ImmutableSortedMap.copyOf(symlinkTree.getLinks()))
                .setFullNameToPathMap(ImmutableSortedMap.copyOf(symlinkTree.getFullLinks()))
                .build())
        .addAllFrameworkRoots(frameworkSearchPaths);
    switch(includeType) {
      case LOCAL:
        builder.addIncludeRoots(symlinkTree.getIncludePath());
        builder.addAllHeaderMaps(symlinkTree.getHeaderMap().asSet());
        break;
      case SYSTEM:
        builder.addSystemIncludeRoots(symlinkTree.getSystemIncludePath());
        break;
    }
    return builder.build();
  }
}
