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

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.SymlinkTree;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class CxxPreprocessables {

  private CxxPreprocessables() {}

  private static final BuildRuleType HEADER_SYMLINK_TREE_TYPE =
      new BuildRuleType("header_symlink_tree");

  private static final BuildRuleType HEADERS_TYPE = new BuildRuleType("headers");

  /**
   * Resolve the map of name to {@link SourcePath} to a map of full header name to
   * {@link SourcePath}.
   */
  public static ImmutableMap<Path, SourcePath> resolveHeaderMap(
      BuildTarget target,
      ImmutableMap<String, SourcePath> headers) {

    ImmutableMap.Builder<Path, SourcePath> headerMap = ImmutableMap.builder();

    // Resolve the "names" of the headers to actual paths by prepending the base path
    // specified by the build target.
    for (ImmutableMap.Entry<String, SourcePath> ent : headers.entrySet()) {
      Path path = target.getBasePath().resolve(ent.getKey());
      headerMap.put(path, ent.getValue());
    }

    return headerMap.build();
  }

  /**
   * Find and return the {@link CxxPreprocessorInput} objects from {@link CxxPreprocessorDep}
   * found while traversing the dependencies starting from the {@link BuildRule} objects given.
   */
  public static CxxPreprocessorInput getTransitiveCxxPreprocessorInput(
      Iterable<? extends BuildRule> inputs) {

    // Build up a graph of the inputs and their transitive dependencies.
    final MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<>();
    AbstractDependencyVisitor visitor = new AbstractDependencyVisitor(inputs) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        graph.addNode(rule);
        for (BuildRule dep : rule.getDeps()) {
          graph.addEdge(rule, dep);
        }
        return rule.getDeps();
      }
    };
    visitor.start();

    // Grab the cxx preprocessor inputs and return them.
    return CxxPreprocessorInput.concat(
        FluentIterable
            // We don't really care about the order we get back here, since headers shouldn't
            // conflict.  However, we want something that's deterministic, so sort by build
            // target.
            .from(ImmutableSortedSet.orderedBy(HasBuildTarget.BUILD_TARGET_COMPARATOR)
                .addAll(graph.getNodes())
                .build())
            .filter(CxxPreprocessorDep.class)
            .transform(CxxPreprocessorDep.GET_CXX_PREPROCESSOR_INPUT)
            .toList());
  }

  /**
   * Build the {@link SymlinkTree} rule using the original build params from a target node.
   * In particular, make sure to drop all dependencies from the original build rule params,
   * as these are modeled via {@link CxxHeader}.
   */
  public static SymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildTarget target,
      BuildRuleParams params,
      Path root,
      ImmutableMap<Path, SourcePath> links) {

    return new SymlinkTree(
        params.copyWithChanges(
            HEADER_SYMLINK_TREE_TYPE,
            target,
            // Symlink trees never need to depend on anything.
            ImmutableSortedSet.<BuildRule>of(),
            ImmutableSortedSet.<BuildRule>of()),
        root,
        links);
  }

  /**
   * Setup a build rule that updates whenever any header or header dependency changes.
   * This includes the hash of the header contents and all corresponding transitive
   * header dependencies.  This should be depended on by any compile rules generated
   * for this higher level rule to make sure we re-compile if any headers change.
   */
  public static CxxHeader createHeaderBuildRule(
      BuildTarget target,
      BuildRuleParams params,
      ImmutableMap<Path, SourcePath> headers) {

    // TODO(agallagher): In the common case, C/C++ sources only actually use a small
    // subset of all the headers in their transitive include search space, so this setup
    // will cause a lot of false rebuilds.  Long-term, we should add some sort of dep-file
    // support to avoid this.
    BuildRuleParams headerParams = params.copyWithChanges(
        HEADERS_TYPE,
        target,
        /* declaredDeps */ ImmutableSortedSet.copyOf(
            SourcePaths.filterBuildRuleInputs(headers.values())),
        /* declaredDeps */ ImmutableSortedSet.<BuildRule>of());
    return new CxxHeader(headerParams, headers);
  }

  /**
   * Construct all the rule needed to handles headers for the target node represented by the
   * given {@link com.facebook.buck.rules.BuildRuleParams}.
   */
  public static ImmutableSortedSet<BuildRule> createHeaderBuildRules(
      BuildTarget headersTarget,
      BuildTarget symlinkTarget,
      Path symlinkRoot,
      BuildRuleParams params,
      ImmutableMap<Path, SourcePath> headers) {

    return ImmutableSortedSet.<BuildRule>of(
        createHeaderBuildRule(headersTarget, params, headers),
        createHeaderSymlinkTreeBuildRule(symlinkTarget, params, symlinkRoot, headers));
  }

}
