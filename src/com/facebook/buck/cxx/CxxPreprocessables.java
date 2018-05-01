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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTreeWithModuleMap;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

public class CxxPreprocessables {

  private CxxPreprocessables() {}

  public enum IncludeType {

    /** Headers should be included with `-I`. */
    LOCAL {
      @Override
      public Iterable<String> includeArgs(Preprocessor pp, Iterable<String> includeRoots) {
        return pp.localIncludeArgs(includeRoots);
      }
    },

    /** Headers should be included with `-isystem`. */
    SYSTEM {
      @Override
      public Iterable<String> includeArgs(Preprocessor pp, Iterable<String> includeRoots) {
        return pp.systemIncludeArgs(includeRoots);
      }
    },

    /** Headers should be included with `-iquote`. */
    IQUOTE {
      @Override
      public Iterable<String> includeArgs(Preprocessor pp, Iterable<String> includeRoots) {
        return pp.quoteIncludeArgs(includeRoots);
      }
    },

    /** Headers are not added by buck */
    RAW {
      @Override
      public Iterable<String> includeArgs(
          Preprocessor preprocessor, Iterable<String> includeRoots) {
        return ImmutableList.of();
      }
    },
    ;

    public abstract Iterable<String> includeArgs(Preprocessor pp, Iterable<String> includeRoots);
  }

  /**
   * Resolve the map of name to {@link SourcePath} to a map of full header name to {@link
   * SourcePath}.
   */
  public static ImmutableMap<Path, SourcePath> resolveHeaderMap(
      Path basePath, ImmutableMap<String, SourcePath> headers) {

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
   * Find and return the {@link CxxPreprocessorInput} objects from {@link CxxPreprocessorDep} found
   * while traversing the dependencies starting from the {@link BuildRule} objects given.
   */
  public static Collection<CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      BuildRuleResolver ruleResolver,
      Iterable<? extends BuildRule> inputs,
      Predicate<Object> traverse) {

    // We don't really care about the order we get back here, since headers shouldn't
    // conflict.  However, we want something that's deterministic, so sort by build
    // target.
    Map<BuildTarget, CxxPreprocessorInput> deps = new LinkedHashMap<>();

    // Build up the map of all C/C++ preprocessable dependencies.
    new AbstractBreadthFirstTraversal<BuildRule>(inputs) {
      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        if (rule instanceof CxxPreprocessorDep) {
          CxxPreprocessorDep dep = (CxxPreprocessorDep) rule;
          deps.putAll(dep.getTransitiveCxxPreprocessorInput(cxxPlatform, ruleResolver));
          return ImmutableSet.of();
        }
        return traverse.test(rule) ? rule.getBuildDeps() : ImmutableSet.of();
      }
    }.start();

    // Grab the cxx preprocessor inputs and return them.
    return deps.values();
  }

  public static Collection<CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      BuildRuleResolver ruleResolver,
      Iterable<? extends BuildRule> inputs) {
    return getTransitiveCxxPreprocessorInput(cxxPlatform, ruleResolver, inputs, x -> true);
  }

  /**
   * Build the {@link HeaderSymlinkTree} rule using the original build params from a target node. In
   * particular, make sure to drop all dependencies from the original build rule params, as these
   * are modeled via {@link CxxPreprocessAndCompile}.
   */
  public static HeaderSymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildTarget target,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      HeaderMode headerMode) {
    switch (headerMode) {
      case SYMLINK_TREE_WITH_HEADER_MAP:
        return HeaderSymlinkTreeWithHeaderMap.create(target, filesystem, root, links, ruleFinder);
      case SYMLINK_TREE_WITH_MODULEMAP:
        return HeaderSymlinkTreeWithModuleMap.create(target, filesystem, root, links, ruleFinder);
      case HEADER_MAP_ONLY:
        return new DirectHeaderMap(target, filesystem, root, links, ruleFinder);
      default:
      case SYMLINK_TREE_ONLY:
        return new HeaderSymlinkTree(target, filesystem, root, links, ruleFinder);
    }
  }

  /**
   * @return adds a the header {@link com.facebook.buck.rules.SymlinkTree} for the given rule to the
   *     {@link CxxPreprocessorInput}.
   */
  public static CxxPreprocessorInput.Builder addHeaderSymlinkTree(
      CxxPreprocessorInput.Builder builder,
      BuildTarget target,
      BuildRuleResolver ruleResolver,
      CxxPlatform platform,
      HeaderVisibility headerVisibility,
      IncludeType includeType) {
    BuildRule rule =
        ruleResolver.requireRule(
            target.withAppendedFlavors(
                platform.getFlavor(),
                CxxDescriptionEnhancer.getHeaderSymlinkTreeFlavor(headerVisibility)));
    Preconditions.checkState(
        rule instanceof HeaderSymlinkTree,
        "Attempt to add %s of type %s and class %s to %s",
        rule.getFullyQualifiedName(),
        rule.getType(),
        rule.getClass().getName(),
        target);
    HeaderSymlinkTree symlinkTree = (HeaderSymlinkTree) rule;
    builder.addIncludes(CxxSymlinkTreeHeaders.from(symlinkTree, includeType));
    return builder;
  }

  /** Builds a {@link CxxPreprocessorInput} for a rule. */
  public static CxxPreprocessorInput getCxxPreprocessorInput(
      BuildTarget buildTarget,
      BuildRuleResolver ruleResolver,
      boolean hasHeaderSymlinkTree,
      CxxPlatform platform,
      HeaderVisibility headerVisibility,
      IncludeType includeType,
      Multimap<CxxSource.Type, String> exportedPreprocessorFlags,
      Iterable<FrameworkPath> frameworks) {
    CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
    if (hasHeaderSymlinkTree) {
      addHeaderSymlinkTree(
          builder, buildTarget, ruleResolver, platform, headerVisibility, includeType);
    }
    return builder
        .putAllPreprocessorFlags(
            ImmutableListMultimap.copyOf(
                Multimaps.transformValues(exportedPreprocessorFlags, StringArg::of)))
        .addAllFrameworks(frameworks)
        .build();
  }
}
