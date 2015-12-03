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
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.ClosureException;
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
      final CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      final Predicate<Object> traverse) throws NoSuchBuildTargetException {

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
              try {
                deps.putAll(
                    dep.getTransitiveCxxPreprocessorInput(
                        cxxPlatform,
                        HeaderVisibility.PUBLIC));
              } catch (NoSuchBuildTargetException e) {
                throw new ClosureException(e);
              }
              return ImmutableSet.of();
            }
            return traverse.apply(rule) ? rule.getDeps() : ImmutableSet.<BuildRule>of();
          }
        };
    try {
      visitor.start();
    } catch (ClosureException e) {
      throw (NoSuchBuildTargetException) e.getException();
    }


    // Grab the cxx preprocessor inputs and return them.
    return deps.values();
  }

  public static Collection<CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      final CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs) throws NoSuchBuildTargetException {
    return getTransitiveCxxPreprocessorInput(
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
      throw e.getHumanReadableExceptionForBuildTarget(target.getUnflavoredBuildTarget());
    }
  }

  /**
   * @return adds a the header {@link SymlinkTree} for the given rule to the
   *     {@link CxxPreprocessorInput}.
   */
  public static CxxPreprocessorInput.Builder addHeaderSymlinkTree(
      CxxPreprocessorInput.Builder builder,
      BuildTarget target,
      BuildRuleResolver ruleResolver,
      Flavor flavor,
      HeaderVisibility headerVisibility,
      IncludeType includeType) throws NoSuchBuildTargetException {
    BuildRule rule = ruleResolver.requireRule(
        BuildTarget.builder(target)
            .addFlavors(flavor, CxxDescriptionEnhancer.getHeaderSymlinkTreeFlavor(headerVisibility))
            .build());
    Preconditions.checkState(
        rule instanceof HeaderSymlinkTree,
        "Attempt to add %s of type %s and class %s to %s",
        rule.getFullyQualifiedName(),
        rule.getType(),
        rule.getClass(),
        target);
    HeaderSymlinkTree symlinkTree = (HeaderSymlinkTree) rule;
    builder
        .addRules(symlinkTree.getBuildTarget())
        .setIncludes(
            CxxHeaders.builder()
                .setNameToPathMap(ImmutableSortedMap.copyOf(symlinkTree.getLinks()))
                .setFullNameToPathMap(ImmutableSortedMap.copyOf(symlinkTree.getFullLinks()))
                .build());
    switch(includeType) {
      case LOCAL:
        builder.addIncludeRoots(symlinkTree.getIncludePath());
        builder.addAllHeaderMaps(symlinkTree.getHeaderMap().asSet());
        break;
      case SYSTEM:
        builder.addSystemIncludeRoots(symlinkTree.getSystemIncludePath());
        break;
    }
    return builder;

  }

  /**
   * Builds a {@link CxxPreprocessorInput} for a rule.
   */
  public static CxxPreprocessorInput getCxxPreprocessorInput(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      Flavor flavor,
      HeaderVisibility headerVisibility,
      IncludeType includeType,
      Multimap<CxxSource.Type, String> exportedPreprocessorFlags,
      Iterable<FrameworkPath> frameworks) throws NoSuchBuildTargetException {
    CxxPreprocessorInput.Builder builder =
        addHeaderSymlinkTree(
            CxxPreprocessorInput.builder(),
            params.getBuildTarget(),
            ruleResolver,
            flavor,
            headerVisibility,
            includeType);
    return builder
        .putAllPreprocessorFlags(exportedPreprocessorFlags)
        .addAllFrameworks(frameworks)
        .build();
  }

}
