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
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.DependencyAggregation;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Suppliers;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;
import java.util.Optional;

/**
 * Represents a precompilable header file, along with dependencies.
 *
 * <p>Rules which depend on this will inherit this rule's of dependencies. For example if a given
 * rule R uses a precompiled header rule P, then all of P's {@code deps} will get merged into R's
 * {@code deps} list.
 */
public class CxxPrecompiledHeaderTemplate extends NoopBuildRule
    implements NativeLinkable, CxxPreprocessorDep {

  private static final Flavor AGGREGATED_PREPROCESS_DEPS_FLAVOR =
      InternalFlavor.of("preprocessor-deps");

  public final BuildRuleParams params;
  public final BuildRuleResolver ruleResolver;
  public final SourcePath sourcePath;
  public final SourcePathRuleFinder ruleFinder;
  public final SourcePathResolver pathResolver;

  /** @param buildRuleParams the params for this PCH rule, <b>including</b> {@code deps} */
  CxxPrecompiledHeaderTemplate(
      BuildRuleParams buildRuleParams, BuildRuleResolver ruleResolver, SourcePath sourcePath) {
    super(buildRuleParams);
    this.params = buildRuleParams;
    this.ruleResolver = ruleResolver;
    this.sourcePath = sourcePath;
    this.ruleFinder = new SourcePathRuleFinder(ruleResolver);
    this.pathResolver = new SourcePathResolver(ruleFinder);
  }

  private ImmutableSortedSet<BuildRule> getExportedDeps() {
    return BuildRules.getExportedRules(getBuildDeps());
  }

  /**
   * Returns our {@link #getBuildDeps()}, limited to the subset of those which are {@link
   * NativeLinkable}.
   */
  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableDeps() {
    return RichStream.from(getBuildDeps()).filter(NativeLinkable.class).toImmutableList();
  }

  /**
   * Returns our {@link #getExportedDeps()}, limited to the subset of those which are {@link
   * NativeLinkable}.
   */
  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps() {
    return RichStream.from(getExportedDeps()).filter(NativeLinkable.class).toImmutableList();
  }

  /**
   * Pick a linkage, any linkage. Just pick your favorite. This will be overridden by config anyway.
   */
  @Override
  public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Linkage.SHARED;
  }

  /** Doesn't really apply to us. No shared libraries to add here. */
  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    return ImmutableMap.of();
  }

  /**
   * This class doesn't add any native linkable code of its own, it just has deps which need to be
   * passed along and up to the top-level (e.g. a `cxx_binary`) rule. Take all our linkable deps,
   * then, and pass it along as our linker input.
   */
  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform, Linker.LinkableDepType type) throws NoSuchBuildTargetException {
    return NativeLinkables.getTransitiveNativeLinkableInput(
        cxxPlatform,
        getBuildDeps(),
        Linker.LinkableDepType.SHARED,
        NativeLinkable.class::isInstance);
  }

  @Override
  public Iterable<? extends CxxPreprocessorDep> getCxxPreprocessorDeps(CxxPlatform cxxPlatform) {
    return RichStream.from(getBuildDeps()).filter(CxxPreprocessorDep.class).toImmutableList();
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, HeaderVisibility headerVisibility)
      throws NoSuchBuildTargetException {
    return CxxPreprocessorInput.EMPTY;
  }

  private final LoadingCache<
          CxxPreprocessables.CxxPreprocessorInputCacheKey,
          ImmutableMap<BuildTarget, CxxPreprocessorInput>>
      transitiveCxxPreprocessorInputCache =
          CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, HeaderVisibility headerVisibility)
      throws NoSuchBuildTargetException {
    return transitiveCxxPreprocessorInputCache.getUnchecked(
        ImmutableCxxPreprocessorInputCacheKey.of(cxxPlatform, headerVisibility));
  }

  private ImmutableList<CxxPreprocessorInput> getCxxPreprocessorInputs(CxxPlatform cxxPlatform) {
    ImmutableList.Builder<CxxPreprocessorInput> builder = ImmutableList.builder();
    try {
      for (Map.Entry<BuildTarget, CxxPreprocessorInput> entry :
          getTransitiveCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PUBLIC).entrySet()) {
        builder.add(entry.getValue());
      }
    } catch (NoSuchBuildTargetException e) {
      throw new RuntimeException(e);
    }
    return builder.build();
  }

  private ImmutableList<CxxHeaders> getIncludes(CxxPlatform cxxPlatform) {
    return getCxxPreprocessorInputs(cxxPlatform)
        .stream()
        .flatMap(input -> input.getIncludes().stream())
        .collect(MoreCollectors.toImmutableList());
  }

  private ImmutableSet<FrameworkPath> getFrameworks(CxxPlatform cxxPlatform) {
    return getCxxPreprocessorInputs(cxxPlatform)
        .stream()
        .flatMap(input -> input.getFrameworks().stream())
        .collect(MoreCollectors.toImmutableSet());
  }

  private ImmutableSortedSet<BuildRule> getPreprocessDeps(CxxPlatform cxxPlatform) {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    for (CxxPreprocessorInput input : getCxxPreprocessorInputs(cxxPlatform)) {
      builder.addAll(input.getDeps(ruleResolver, ruleFinder));
    }
    for (CxxHeaders cxxHeaders : getIncludes(cxxPlatform)) {
      builder.addAll(cxxHeaders.getDeps(ruleFinder));
    }
    for (FrameworkPath frameworkPath : getFrameworks(cxxPlatform)) {
      builder.addAll(frameworkPath.getDeps(ruleFinder));
    }

    builder.addAll(getBuildDeps());
    builder.addAll(getExportedDeps());

    return builder.build();
  }

  private BuildTarget createAggregatedDepsTarget(CxxPlatform cxxPlatform) {
    return params
        .getBuildTarget()
        .withAppendedFlavors(cxxPlatform.getFlavor(), AGGREGATED_PREPROCESS_DEPS_FLAVOR);
  }

  public DependencyAggregation requireAggregatedDepsRule(CxxPlatform cxxPlatform) {
    BuildTarget depAggTarget = createAggregatedDepsTarget(cxxPlatform);

    Optional<DependencyAggregation> existingRule =
        ruleResolver.getRuleOptionalWithType(depAggTarget, DependencyAggregation.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    BuildRuleParams depAggParams =
        params
            .withBuildTarget(depAggTarget)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(getPreprocessDeps(cxxPlatform)),
                Suppliers.ofInstance(ImmutableSortedSet.of()));

    DependencyAggregation depAgg = new DependencyAggregation(depAggParams);
    ruleResolver.addToIndex(depAgg);
    return depAgg;
  }

  public PreprocessorDelegate buildPreprocessorDelegate(
      CxxPlatform cxxPlatform, Preprocessor preprocessor, CxxToolFlags preprocessorFlags) {
    try {
      return new PreprocessorDelegate(
          pathResolver,
          cxxPlatform.getCompilerDebugPathSanitizer(),
          cxxPlatform.getHeaderVerification(),
          params.getProjectFilesystem().getRootPath(),
          preprocessor,
          PreprocessorFlags.of(
              /* getPrefixHeader() */ Optional.empty(),
              preprocessorFlags,
              getIncludes(cxxPlatform),
              getFrameworks(cxxPlatform)),
          CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, pathResolver),
          /* getSandboxTree() */ Optional.empty(),
          /* leadingIncludePaths */ Optional.empty());
    } catch (PreprocessorDelegate.ConflictingHeadersException e) {
      throw new RuntimeException(e);
    }
  }
}
