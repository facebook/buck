/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.common.BuildRules;
import com.facebook.buck.core.rules.impl.DependencyAggregation;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInfo;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.PlatformMappedCache;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Represents a precompilable header file, along with dependencies.
 *
 * <p>Rules which depend on this will inherit this rule's of dependencies. For example if a given
 * rule R uses a precompiled header rule P, then all of P's {@code deps} will get merged into R's
 * {@code deps} list.
 */
public abstract class PreInclude extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements NativeLinkableGroup, CxxPreprocessorDep {

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

  private final PlatformMappedCache<NativeLinkable> linkableCache = new PlatformMappedCache<>();

  /** @param buildRuleParams the params for this PCH rule, <b>including</b> {@code deps} */
  PreInclude(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<BuildRule> deps,
      SourcePath sourcePath,
      Path absoluteHeaderPath) {
    super(buildTarget, projectFilesystem, makeBuildRuleParams(deps));
    this.sourcePath = sourcePath;
    this.absoluteHeaderPath = absoluteHeaderPath;
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
    return relativizedTo.relativize(getAbsoluteHeaderPath()).getPath();
  }

  private static BuildRuleParams makeBuildRuleParams(ImmutableSortedSet<BuildRule> deps) {
    return new BuildRuleParams(() -> deps, () -> ImmutableSortedSet.of(), ImmutableSortedSet.of());
  }

  private ImmutableSortedSet<BuildRule> getExportedDeps() {
    return BuildRules.getExportedRules(getBuildDeps());
  }

  @Override
  public NativeLinkable getNativeLinkable(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return linkableCache.get(
        cxxPlatform,
        () -> {
          ImmutableList<NativeLinkable> deps =
              FluentIterable.from(getBuildDeps())
                  .filter(NativeLinkableGroup.class)
                  .transform(g -> g.getNativeLinkable(cxxPlatform, graphBuilder))
                  .toList();
          ImmutableList<NativeLinkable> exportedDeps =
              FluentIterable.from(getExportedDeps())
                  .filter(NativeLinkableGroup.class)
                  .transform(g -> g.getNativeLinkable(cxxPlatform, graphBuilder))
                  .toList();
          return new NativeLinkableInfo(
              getBuildTarget(),
              getType(),
              deps,
              exportedDeps,
              Linkage.ANY,
              NativeLinkableInfo.fixedDelegate(NativeLinkableInput.of(), ImmutableMap.of()),
              NativeLinkableInfo.defaults());
        });
  }

  @Override
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return RichStream.from(getBuildDeps()).filter(CxxPreprocessorDep.class).toImmutableList();
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return CxxPreprocessorInput.of();
  }

  private final TransitiveCxxPreprocessorInputCache transitiveCxxPreprocessorInputCache =
      new TransitiveCxxPreprocessorInputCache(this);

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform, graphBuilder);
  }

  private ImmutableList<CxxPreprocessorInput> getCxxPreprocessorInputs(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    ImmutableList.Builder<CxxPreprocessorInput> builder = ImmutableList.builder();
    for (Map.Entry<BuildTarget, CxxPreprocessorInput> entry :
        getTransitiveCxxPreprocessorInput(cxxPlatform, graphBuilder).entrySet()) {
      builder.add(entry.getValue());
    }
    return builder.build();
  }

  private ImmutableList<CxxHeaders> getIncludes(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getCxxPreprocessorInputs(cxxPlatform, graphBuilder).stream()
        .flatMap(input -> input.getIncludes().stream())
        .collect(ImmutableList.toImmutableList());
  }

  private ImmutableSet<FrameworkPath> getFrameworks(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getCxxPreprocessorInputs(cxxPlatform, graphBuilder).stream()
        .flatMap(input -> input.getFrameworks().stream())
        .collect(ImmutableSet.toImmutableSet());
  }

  private ImmutableSortedSet<BuildRule> getPreprocessDeps(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    for (CxxPreprocessorInput input : getCxxPreprocessorInputs(cxxPlatform, graphBuilder)) {
      builder.addAll(input.getDeps(graphBuilder));
    }
    for (CxxHeaders cxxHeaders : getIncludes(cxxPlatform, graphBuilder)) {
      cxxHeaders.getDeps(graphBuilder).forEachOrdered(builder::add);
    }
    for (FrameworkPath frameworkPath : getFrameworks(cxxPlatform, graphBuilder)) {
      builder.addAll(frameworkPath.getDeps(graphBuilder));
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
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return (DependencyAggregation)
        graphBuilder.computeIfAbsent(
            createAggregatedDepsTarget(cxxPlatform),
            depAggTarget ->
                new DependencyAggregation(
                    depAggTarget,
                    getProjectFilesystem(),
                    getPreprocessDeps(cxxPlatform, graphBuilder)));
  }

  /** @return newly-built delegate for this PCH build (if precompiling enabled) */
  protected PreprocessorDelegate buildPreprocessorDelegate(
      CxxPlatform cxxPlatform,
      Preprocessor preprocessor,
      CxxToolFlags preprocessorFlags,
      ActionGraphBuilder graphBuilder,
      SourcePathResolverAdapter pathResolver) {
    return new PreprocessorDelegate(
        cxxPlatform.getHeaderVerification(),
        PathSourcePath.of(getProjectFilesystem(), Paths.get("")),
        preprocessor,
        PreprocessorFlags.of(
            Optional.of(getHeaderSourcePath()),
            preprocessorFlags,
            getIncludes(cxxPlatform, graphBuilder),
            ImmutableList.copyOf(getFrameworks(cxxPlatform, graphBuilder))),
        CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, pathResolver),
        /* leadingIncludePaths */ Optional.empty(),
        Optional.empty(),
        cxxPlatform.getConflictingHeaderBasenameWhitelist());
  }

  public abstract CxxPrecompiledHeader getPrecompiledHeader(
      boolean canPrecompile,
      PreprocessorDelegate preprocessorDelegateForCxxRule,
      DependencyAggregation aggregatedPreprocessDepsRule,
      CxxToolFlags computedCompilerFlags,
      Function<CxxToolFlags, String> getHash,
      Function<CxxToolFlags, String> getBaseHash,
      CxxPlatform cxxPlatform,
      CxxSource.Type sourceType,
      ImmutableList<String> sourceFlags,
      ActionGraphBuilder graphBuilder,
      SourcePathResolverAdapter pathResolver);

  /**
   * Look up or build a precompiled header build rule which this build rule is requesting.
   *
   * <p>This method will first try to determine whether a matching PCH was already created; if so,
   * it will be reused. This is done by searching the cache in the {@link ActionGraphBuilder} owned
   * by this class. If this ends up building a new instance of {@link CxxPrecompiledHeader}, it will
   * be added to the graphBuilder cache.
   */
  protected CxxPrecompiledHeader requirePrecompiledHeader(
      boolean canPrecompile,
      PreprocessorDelegate preprocessorDelegate,
      CxxPlatform cxxPlatform,
      CxxSource.Type sourceType,
      CxxToolFlags compilerFlags,
      DepsBuilder depsBuilder,
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder) {
    return (CxxPrecompiledHeader)
        graphBuilder.computeIfAbsent(
            buildTarget,
            target -> {
              // Give the PCH a filename that looks like a header file with .gch appended to it,
              // GCC-style.
              // GCC accepts an "-include" flag with the .h file as its arg, and auto-appends
              // ".gch" to
              // automagically use the precompiled header in place of the original header.  Of
              // course in
              // our case we'll only have the ".gch" file, which is alright; the ".h" isn't
              // truly needed.
              Path output = BuildTargetPaths.getGenPath(getProjectFilesystem(), target, "%s.h.gch");

              CompilerDelegate compilerDelegate =
                  new CompilerDelegate(
                      cxxPlatform.getCompilerDebugPathSanitizer(),
                      CxxSourceTypes.getCompiler(
                              cxxPlatform, CxxSourceTypes.getPreprocessorOutputType(sourceType))
                          .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
                      compilerFlags,
                      cxxPlatform.getUseArgFile());
              depsBuilder.add(compilerDelegate);

              depsBuilder.add(getHeaderSourcePath());

              return new CxxPrecompiledHeader(
                  canPrecompile,
                  target,
                  getProjectFilesystem(),
                  depsBuilder.build(),
                  output,
                  preprocessorDelegate,
                  compilerDelegate,
                  compilerFlags,
                  getHeaderSourcePath(),
                  sourceType,
                  cxxPlatform.getCompilerDebugPathSanitizer());
            });
  }
}
