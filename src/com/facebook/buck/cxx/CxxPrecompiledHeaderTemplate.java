/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

/**
 * Represents a precompilable header file, along with dependencies.
 *
 * Rules which depend on this will inherit this rule's of dependencies.  For example if a given
 * rule R uses a precompiled header rule P, then all of P's {@code deps} will get merged into
 * R's {@code deps} list.
 */
public class CxxPrecompiledHeaderTemplate
    extends NoopBuildRule
    implements NativeLinkable, CxxPreprocessorDep {

  public final BuildRuleParams params;
  public final BuildRuleResolver ruleResolver;
  public final SourcePath sourcePath;

  /**
   * @param buildRuleParams the params for this PCH rule, <b>including</b> {@code deps}
   */
  CxxPrecompiledHeaderTemplate(
      BuildRuleParams buildRuleParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePath sourcePath) {
    super(buildRuleParams, pathResolver);
    this.params = buildRuleParams;
    this.ruleResolver = ruleResolver;
    this.sourcePath = sourcePath;
  }

  private ImmutableSortedSet<BuildRule> getExportedDeps() {
    return BuildRules.getExportedRules(getDeps());
  }

  /**
   * Returns our {@link #getDeps()},
   * limited to the subset of those which are {@link NativeLinkable}.
   */
  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableDeps() {
    return RichStream.from(getDeps()).filter(NativeLinkable.class).toImmutableList();
  }

  /**
   * Returns our {@link #getExportedDeps()},
   * limited to the subset of those which are {@link NativeLinkable}.
   */
  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps() {
    return RichStream.from(getExportedDeps()).filter(NativeLinkable.class).toImmutableList();
  }

  /**
   * Pick a linkage, any linkage.  Just pick your favorite.  This will be overridden
   * by config anyway.
   */
  @Override
  public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Linkage.SHARED;
  }

  /**
   * Doesn't really apply to us.  No shared libraries to add here.
   */
  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    return ImmutableMap.of();
  }

  /**
   * This class doesn't add any native linkable code of its own, it just has deps
   * which need to be passed along and up to the top-level (e.g. a `cxx_binary`) rule.
   * Take all our linkable deps, then, and pass it along as our linker input.
   */
  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) throws NoSuchBuildTargetException {
    return NativeLinkables.getTransitiveNativeLinkableInput(
        cxxPlatform,
        getDeps(),
        Linker.LinkableDepType.SHARED,
        NativeLinkable.class::isInstance);
  }

  @Override
  public Iterable<? extends CxxPreprocessorDep> getCxxPreprocessorDeps(CxxPlatform cxxPlatform) {
    return RichStream.from(getDeps()).filter(CxxPreprocessorDep.class).toImmutableList();
  }

  @Override
  public Optional<HeaderSymlinkTree> getExportedHeaderSymlinkTree(CxxPlatform cxxPlatform) {
    return Optional.empty();
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
        CxxPlatform cxxPlatform,
        HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
    return CxxPreprocessorInput.EMPTY;
  }

  private final LoadingCache<
          CxxPreprocessables.CxxPreprocessorInputCacheKey,
          ImmutableMap<BuildTarget, CxxPreprocessorInput>
        > transitiveCxxPreprocessorInputCache =
      CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
        CxxPlatform cxxPlatform,
        HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
    return transitiveCxxPreprocessorInputCache.getUnchecked(
        ImmutableCxxPreprocessorInputCacheKey.of(cxxPlatform, headerVisibility));
  }

}
