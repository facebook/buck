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
import com.facebook.buck.io.filesystem.ProjectFilesystem;
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
import com.facebook.buck.util.RichStream;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Represents a precompilable header file, along with dependencies.
 *
 * <p>Rules which depend on this will inherit this rule's of dependencies. For example if a given
 * rule R uses a precompiled header rule P, then all of P's {@code deps} will get merged into R's
 * {@code deps} list.
 */
public abstract class PreInclude extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements NativeLinkable, CxxPreprocessorDep {

  private static final Flavor AGGREGATED_PREPROCESS_DEPS_FLAVOR =
      InternalFlavor.of("preprocessor-deps");

  /**
   * The source path which was expressed as either: (1) the `prefix_header` attribute in a
   * `cxx_binary` or `cxx_library` (or similar) rule, or (2) the `src` in a `cxx_precompiled_header`
   * rule.
   *
   * <p>This could be any {@link SourcePath}: a target, a file, etc.
   */
  private final SourcePath sourcePath;

  /**
   * The path to the header file itself; i.e. the path which {@link #sourcePath} resolves to. This
   * is guaranteed to be an absolute path.
   */
  private final Path absoluteHeaderPath;

  /** @param buildRuleParams the params for this PCH rule, <b>including</b> {@code deps} */
  PreInclude(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathResolver pathResolver,
      SourcePath sourcePath) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.sourcePath = sourcePath;
    this.absoluteHeaderPath = pathResolver.getAbsolutePath(sourcePath);
  }

  /**
   * @return source path as specified in a {@code cxx_precompiled_header}'s {@code src} attribute,
   *     or of a rule's {@code prefix_header}.
   */
  public SourcePath getHeaderSourcePath() {
    return sourcePath;
  }

  /** @return path to the header file, guaranteed absolute */
  public Path getAbsoluteHeaderPath() {
    return absoluteHeaderPath;
  }

  /** @return path to the header file, relativized against the given project filesystem (cell) */
  public Path getRelativeHeaderPath(ProjectFilesystem relativizedTo) {
    return relativizedTo.relativize(getAbsoluteHeaderPath());
  }

  protected static BuildRuleParams makeBuildRuleParams(ImmutableSortedSet<BuildRule> deps) {
    return new BuildRuleParams(() -> deps, () -> ImmutableSortedSet.of(), ImmutableSortedSet.of());
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
   * passed along (see {@link #getNativeLinkableDeps()} and {@link #getNativeLinkableExportedDeps()}
   * for the handling of those linkables).
   *
   * @return empty {@link NativeLinkableInput}
   */
  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ImmutableSet<LanguageExtensions> languageExtensions) {
    return NativeLinkableInput.of();
  }

  @Override
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(CxxPlatform cxxPlatform) {
    return RichStream.from(getBuildDeps()).filter(CxxPreprocessorDep.class).toImmutableList();
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(CxxPlatform cxxPlatform) {
    return CxxPreprocessorInput.of();
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
        .collect(ImmutableList.toImmutableList());
  }

  private ImmutableSet<FrameworkPath> getFrameworks(CxxPlatform cxxPlatform) {
    return getCxxPreprocessorInputs(cxxPlatform)
        .stream()
        .flatMap(input -> input.getFrameworks().stream())
        .collect(ImmutableSet.toImmutableSet());
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

  /**
   * Find or create a {@link DependencyAggregation} rule, representing a grouping of dependencies:
   * generally, those deps from the current {@link CxxPlatform}.
   */
  protected DependencyAggregation requireAggregatedDepsRule(
      BuildRuleResolver ruleResolver, SourcePathRuleFinder ruleFinder, CxxPlatform cxxPlatform) {
    return (DependencyAggregation)
        ruleResolver.computeIfAbsent(
            createAggregatedDepsTarget(cxxPlatform),
            depAggTarget ->
                new DependencyAggregation(
                    depAggTarget,
                    getProjectFilesystem(),
                    getPreprocessDeps(ruleResolver, ruleFinder, cxxPlatform)));
  }

  /** @return newly-built delegate for this PCH build (if precompiling enabled) */
  protected PreprocessorDelegate buildPreprocessorDelegate(
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Preprocessor preprocessor,
      CxxToolFlags preprocessorFlags) {
    if (!CxxHeadersExperiment.runExperiment()) {
      ImmutableList<CxxHeaders> includes = getIncludes(cxxPlatform);
      try {
        CxxHeaders.checkConflictingHeaders(includes);
      } catch (CxxHeaders.ConflictingHeadersException e) {
        throw e.getHumanReadableExceptionForBuildTarget(getBuildTarget());
      }
    }
    return new PreprocessorDelegate(
        pathResolver,
        cxxPlatform.getCompilerDebugPathSanitizer(),
        cxxPlatform.getHeaderVerification(),
        getProjectFilesystem().getRootPath(),
        preprocessor,
        PreprocessorFlags.of(
            Optional.of(getHeaderSourcePath()),
            preprocessorFlags,
            getIncludes(cxxPlatform),
            getFrameworks(cxxPlatform)),
        CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, pathResolver),
        /* getSandboxTree() */ Optional.empty(),
        /* leadingIncludePaths */ Optional.empty());
  }
}
