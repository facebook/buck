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

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.DependencyAggregation;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
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
public class CxxPrecompiledHeaderTemplate extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements NativeLinkable, CxxPreprocessorDep {

  private static final Flavor AGGREGATED_PREPROCESS_DEPS_FLAVOR =
      InternalFlavor.of("preprocessor-deps");

  public final SourcePath sourcePath;

  /** @param buildRuleParams the params for this PCH rule, <b>including</b> {@code deps} */
  CxxPrecompiledHeaderTemplate(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePath sourcePath) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.sourcePath = sourcePath;
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
   * Linkage doesn't matter for PCHs, but use care not to change it from the rest of the builds'
   * rules' preferred linkage.
   */
  @Override
  public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Linkage.ANY;
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
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ImmutableSet<LanguageExtensions> languageExtensions) {
    return NativeLinkables.getTransitiveNativeLinkableInput(
        cxxPlatform,
        getBuildDeps(),
        Linker.LinkableDepType.SHARED,
        NativeLinkable.class::isInstance);
  }

  @Override
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(CxxPlatform cxxPlatform) {
    return RichStream.from(getBuildDeps()).filter(CxxPreprocessorDep.class).toImmutableList();
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(CxxPlatform cxxPlatform) {
    return CxxPreprocessorInput.EMPTY;
  }

  private final LoadingCache<CxxPlatform, ImmutableMap<BuildTarget, CxxPreprocessorInput>>
      transitiveCxxPreprocessorInputCache =
          CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform) {
    return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform);
  }

  private ImmutableList<CxxPreprocessorInput> getCxxPreprocessorInputs(CxxPlatform cxxPlatform) {
    ImmutableList.Builder<CxxPreprocessorInput> builder = ImmutableList.builder();
    for (Map.Entry<BuildTarget, CxxPreprocessorInput> entry :
        getTransitiveCxxPreprocessorInput(cxxPlatform).entrySet()) {
      builder.add(entry.getValue());
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

  private ImmutableSortedSet<BuildRule> getPreprocessDeps(
      BuildRuleResolver ruleResolver, SourcePathRuleFinder ruleFinder, CxxPlatform cxxPlatform) {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    for (CxxPreprocessorInput input : getCxxPreprocessorInputs(cxxPlatform)) {
      builder.addAll(input.getDeps(ruleResolver, ruleFinder));
    }
    for (CxxHeaders cxxHeaders : getIncludes(cxxPlatform)) {
      cxxHeaders.getDeps(ruleFinder).forEachOrdered(builder::add);
    }
    for (FrameworkPath frameworkPath : getFrameworks(cxxPlatform)) {
      builder.addAll(frameworkPath.getDeps(ruleFinder));
    }

    builder.addAll(getBuildDeps());
    builder.addAll(getExportedDeps());

    return builder.build();
  }

  private BuildTarget createAggregatedDepsTarget(CxxPlatform cxxPlatform) {
    return getBuildTarget()
        .withAppendedFlavors(cxxPlatform.getFlavor(), AGGREGATED_PREPROCESS_DEPS_FLAVOR);
  }

  public DependencyAggregation requireAggregatedDepsRule(
      BuildRuleResolver ruleResolver, SourcePathRuleFinder ruleFinder, CxxPlatform cxxPlatform) {
    BuildTarget depAggTarget = createAggregatedDepsTarget(cxxPlatform);

    Optional<DependencyAggregation> existingRule =
        ruleResolver.getRuleOptionalWithType(depAggTarget, DependencyAggregation.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    DependencyAggregation depAgg =
        new DependencyAggregation(
            depAggTarget,
            getProjectFilesystem(),
            getPreprocessDeps(ruleResolver, ruleFinder, cxxPlatform));
    ruleResolver.addToIndex(depAgg);
    return depAgg;
  }

  public PreprocessorDelegate buildPreprocessorDelegate(
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Preprocessor preprocessor,
      CxxToolFlags preprocessorFlags) {
    ImmutableList<CxxHeaders> includes = getIncludes(cxxPlatform);
    try {
      CxxHeaders.checkConflictingHeaders(includes);
    } catch (CxxHeaders.ConflictingHeadersException e) {
      throw e.getHumanReadableExceptionForBuildTarget(getBuildTarget());
    }
    return new PreprocessorDelegate(
        pathResolver,
        cxxPlatform.getCompilerDebugPathSanitizer(),
        cxxPlatform.getHeaderVerification(),
        getProjectFilesystem().getRootPath(),
        preprocessor,
        PreprocessorFlags.of(
            /* getPrefixHeader() */ Optional.empty(),
            preprocessorFlags,
            getIncludes(cxxPlatform),
            getFrameworks(cxxPlatform)),
        CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, pathResolver),
        /* getSandboxTree() */ Optional.empty(),
        /* leadingIncludePaths */ Optional.empty());
  }
}
