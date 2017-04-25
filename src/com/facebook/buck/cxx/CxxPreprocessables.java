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

import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import org.immutables.value.Value;

public class CxxPreprocessables {

  private CxxPreprocessables() {}

  public enum HeaderMode implements FlavorConvertible {

    /** Creates the tree of symbolic links of headers. */
    SYMLINK_TREE_ONLY,
    /** Creates the header map that references the headers directly in the source tree. */
    HEADER_MAP_ONLY,
    /**
     * Creates the tree of symbolic links of headers and creates the header map that references the
     * symbolic links to the headers.
     */
    SYMLINK_TREE_WITH_HEADER_MAP,
    ;

    private final Flavor flavor;

    HeaderMode() {
      this.flavor =
          InternalFlavor.of(
              String.format(
                  "%s-%s",
                  CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, getClass().getSimpleName()),
                  CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, toString())));
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

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
      final CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      final Predicate<Object> traverse)
      throws NoSuchBuildTargetException {

    // We don't really care about the order we get back here, since headers shouldn't
    // conflict.  However, we want something that's deterministic, so sort by build
    // target.
    final Map<BuildTarget, CxxPreprocessorInput> deps = Maps.newLinkedHashMap();

    // Build up the map of all C/C++ preprocessable dependencies.
    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(inputs) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) throws NoSuchBuildTargetException {
        if (rule instanceof CxxPreprocessorDep) {
          CxxPreprocessorDep dep = (CxxPreprocessorDep) rule;
          deps.putAll(dep.getTransitiveCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PUBLIC));
          return ImmutableSet.of();
        }
        return traverse.apply(rule) ? rule.getBuildDeps() : ImmutableSet.of();
      }
    }.start();

    // Grab the cxx preprocessor inputs and return them.
    return deps.values();
  }

  public static Collection<CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      final CxxPlatform cxxPlatform, Iterable<? extends BuildRule> inputs)
      throws NoSuchBuildTargetException {
    return getTransitiveCxxPreprocessorInput(cxxPlatform, inputs, x -> true);
  }

  /**
   * Build the {@link HeaderSymlinkTree} rule using the original build params from a target node. In
   * particular, make sure to drop all dependencies from the original build rule params, as these
   * are modeled via {@link CxxPreprocessAndCompile}.
   */
  public static HeaderSymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      HeaderMode headerMode,
      SourcePathRuleFinder ruleFinder) {
    switch (headerMode) {
      case SYMLINK_TREE_WITH_HEADER_MAP:
        return HeaderSymlinkTreeWithHeaderMap.create(target, filesystem, root, links, ruleFinder);
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
      IncludeType includeType)
      throws NoSuchBuildTargetException {
    BuildRule rule =
        ruleResolver.requireRule(
            BuildTarget.builder(target)
                .addFlavors(
                    platform.getFlavor(),
                    CxxDescriptionEnhancer.getHeaderSymlinkTreeFlavor(headerVisibility))
                .build());
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
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      boolean hasHeaderSymlinkTree,
      CxxPlatform platform,
      HeaderVisibility headerVisibility,
      IncludeType includeType,
      Multimap<CxxSource.Type, String> exportedPreprocessorFlags,
      Iterable<FrameworkPath> frameworks)
      throws NoSuchBuildTargetException {
    CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
    if (hasHeaderSymlinkTree) {
      addHeaderSymlinkTree(
          builder, params.getBuildTarget(), ruleResolver, platform, headerVisibility, includeType);
    }
    return builder
        .putAllPreprocessorFlags(exportedPreprocessorFlags)
        .addAllFrameworks(frameworks)
        .build();
  }

  public static LoadingCache<
          CxxPreprocessorInputCacheKey, ImmutableMap<BuildTarget, CxxPreprocessorInput>>
      getTransitiveCxxPreprocessorInputCache(final CxxPreprocessorDep preprocessorDep) {
    return CacheBuilder.newBuilder()
        .build(
            new CacheLoader<
                CxxPreprocessorInputCacheKey, ImmutableMap<BuildTarget, CxxPreprocessorInput>>() {
              @Override
              public ImmutableMap<BuildTarget, CxxPreprocessorInput> load(
                  @Nonnull CxxPreprocessorInputCacheKey key) throws Exception {
                Map<BuildTarget, CxxPreprocessorInput> builder = new LinkedHashMap<>();
                builder.put(
                    preprocessorDep.getBuildTarget(),
                    preprocessorDep.getCxxPreprocessorInput(
                        key.getPlatform(), key.getVisibility()));
                for (CxxPreprocessorDep dep :
                    preprocessorDep.getCxxPreprocessorDeps(key.getPlatform())) {
                  builder.putAll(
                      dep.getTransitiveCxxPreprocessorInput(
                          key.getPlatform(), key.getVisibility()));
                }
                return ImmutableMap.copyOf(builder);
              }
            });
  }

  @Value.Immutable
  public abstract static class CxxPreprocessorInputCacheKey
      implements Comparable<CxxPreprocessorInputCacheKey> {

    @Value.Parameter
    public abstract CxxPlatform getPlatform();

    @Value.Parameter
    public abstract HeaderVisibility getVisibility();

    @Override
    public int compareTo(@Nonnull CxxPreprocessorInputCacheKey o) {
      return ComparisonChain.start()
          .compare(getPlatform().getFlavor(), o.getPlatform().getFlavor())
          .compare(getVisibility(), o.getVisibility())
          .result();
    }
  }
}
