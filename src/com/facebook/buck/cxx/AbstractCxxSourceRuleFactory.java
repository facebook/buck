/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.DependencyAggregation;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.Compiler;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxFlavorSanitizer;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.keys.AbstractRuleKeyBuilder;
import com.facebook.buck.rules.keys.AlterRuleKeys;
import com.facebook.buck.rules.keys.NoopRuleKeyScopedHasher;
import com.facebook.buck.rules.keys.hasher.GuavaRuleKeyHasher;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Hashing;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
@NotThreadSafe
abstract class AbstractCxxSourceRuleFactory {

  private static final Logger LOG = Logger.get(AbstractCxxSourceRuleFactory.class);
  private static final String COMPILE_FLAVOR_PREFIX = "compile-";
  private static final Flavor AGGREGATED_PREPROCESS_DEPS_FLAVOR =
      InternalFlavor.of("preprocessor-deps");

  @Value.Parameter
  protected abstract ProjectFilesystem getProjectFilesystem();

  @Value.Parameter
  protected abstract BuildTarget getBaseBuildTarget();

  @Value.Parameter
  protected abstract ActionGraphBuilder getActionGraphBuilder();

  @Value.Parameter
  protected abstract SourcePathResolver getPathResolver();

  @Value.Parameter
  protected abstract SourcePathRuleFinder getRuleFinder();

  @Value.Parameter
  protected abstract CxxBuckConfig getCxxBuckConfig();

  @Value.Parameter
  protected abstract CxxPlatform getCxxPlatform();

  @Value.Parameter
  protected abstract ImmutableList<CxxPreprocessorInput> getCxxPreprocessorInput();

  @Value.Parameter
  protected abstract ImmutableMultimap<CxxSource.Type, Arg> getCompilerFlags();

  /** NOTE: {@code prefix_header} is incompatible with {@code precompiled_header}. */
  @Value.Parameter
  protected abstract Optional<SourcePath> getPrefixHeader();

  /** NOTE: {@code precompiled_header} is incompatible with {@code prefix_header}. */
  @Value.Parameter
  protected abstract Optional<SourcePath> getPrecompiledHeader();

  @Value.Parameter
  protected abstract PicType getPicType();

  @Value.Check
  protected void checkPrefixAndPrecompiledHeaderArgs() {
    if (getPrefixHeader().isPresent() && getPrecompiledHeader().isPresent()) {
      throw new HumanReadableException(
          "Cannot use `prefix_header` and `precompiled_header` in the same rule.");
    }
  }

  /** Can PCH headers be used with the current configuration and type of compiler? */
  @VisibleForTesting
  @Value.Lazy
  boolean canUsePrecompiledHeaders(CxxSource.Type sourceType) {
    return getCxxBuckConfig().isPCHEnabled()
        && sourceType.getPrecompiledHeaderLanguage().isPresent()
        && CxxSourceTypes.getPreprocessor(getCxxPlatform(), sourceType)
            .resolve(getActionGraphBuilder())
            .supportsPrecompiledHeaders();
  }

  /**
   * Get (possibly creating) the {@link PreInclude} instance corresponding to this rule's {@code
   * prefix_header} or {@code precompiled_header}, whichever is applicable, or empty if neither is
   * used.
   *
   * @see AbstractPreIncludeFactory
   */
  @Value.Lazy
  protected Optional<PreInclude> getPreInclude() {
    return PreIncludeFactory.of(
            getProjectFilesystem(),
            getBaseBuildTarget(),
            getActionGraphBuilder(),
            getPathResolver(),
            getPrefixHeader(),
            getPrecompiledHeader())
        .getPreInclude();
  }

  @Value.Lazy
  protected ImmutableSortedSet<BuildRule> getPreprocessDeps() {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    for (CxxPreprocessorInput input : getCxxPreprocessorInput()) {
      builder.addAll(input.getDeps(getActionGraphBuilder(), getRuleFinder()));
    }
    if (getPreInclude().isPresent()) {
      builder.addAll(
          getRuleFinder().filterBuildRuleInputs(getPreInclude().get().getHeaderSourcePath()));
      builder.addAll(getPreInclude().get().getBuildDeps());
    }
    return builder.build();
  }

  @Value.Lazy
  protected ImmutableSet<FrameworkPath> getFrameworks() {
    return getCxxPreprocessorInput()
        .stream()
        .flatMap(input -> input.getFrameworks().stream())
        .collect(ImmutableSet.toImmutableSet());
  }

  @Value.Lazy
  protected ImmutableList<CxxHeaders> getIncludes() {
    return getCxxPreprocessorInput()
        .stream()
        .flatMap(input -> input.getIncludes().stream())
        .collect(ImmutableList.toImmutableList());
  }

  private final Function<CxxSource.Type, ImmutableList<Arg>> rulePreprocessorFlags =
      memoize(
          type ->
              getCxxPreprocessorInput()
                  .stream()
                  .flatMap(input -> input.getPreprocessorFlags().get(type).stream())
                  .collect(ImmutableList.toImmutableList()));

  private final Function<PreprocessorDelegateCacheKey, PreprocessorDelegateCacheValue>
      preprocessorDelegates =
          memoize(
              key -> {
                Preprocessor preprocessor =
                    CxxSourceTypes.getPreprocessor(getCxxPlatform(), key.getSourceType())
                        .resolve(getActionGraphBuilder());
                // TODO(cjhopman): The aggregated deps logic should move into PreprocessorDelegate
                // itself.
                BuildRule aggregatedDeps = requireAggregatedPreprocessDepsRule();
                PreprocessorDelegate delegate =
                    new PreprocessorDelegate(
                        getCxxPlatform().getHeaderVerification(),
                        PathSourcePath.of(getProjectFilesystem(), Paths.get("")),
                        preprocessor,
                        PreprocessorFlags.of(
                            getPreInclude().map(PreInclude::getHeaderSourcePath),
                            computePreprocessorFlags(key.getSourceType(), key.getSourceFlags()),
                            getIncludes(),
                            getFrameworks()),
                        CxxDescriptionEnhancer.frameworkPathToSearchPath(
                            getCxxPlatform(), getPathResolver()),
                        /* leadingIncludePaths */ Optional.empty(),
                        Optional.of(aggregatedDeps),
                        getCxxPlatform().getConflictingHeaderBasenameWhitelist());
                return new PreprocessorDelegateCacheValue(
                    delegate, getSanitizerForSourceType(key.getSourceType()));
              });

  /**
   * Returns the no-op rule that aggregates the preprocessor dependencies.
   *
   * <p>Individual compile rules can depend on it, instead of having to depend on every preprocessor
   * dep themselves. This turns O(n*m) dependencies into O(n+m) dependencies, where n is number of
   * files in a target, and m is the number of targets.
   */
  private BuildRule requireAggregatedPreprocessDepsRule() {
    return getActionGraphBuilder()
        .computeIfAbsent(
            createAggregatedPreprocessDepsBuildTarget(),
            target ->
                new DependencyAggregation(target, getProjectFilesystem(), getPreprocessDeps()));
  }

  @VisibleForTesting
  BuildTarget createAggregatedPreprocessDepsBuildTarget() {
    return getBaseBuildTarget()
        .withAppendedFlavors(getCxxPlatform().getFlavor(), AGGREGATED_PREPROCESS_DEPS_FLAVOR);
  }

  private String getOutputName(String name) {
    List<String> parts = new ArrayList<>();
    for (String part : Splitter.on(File.separator).omitEmptyStrings().split(name)) {
      // TODO(#7877540): Remove once we prevent disabling package boundary checks.
      parts.add(part.equals("..") ? "__PAR__" : part);
    }
    return Joiner.on(File.separator).join(parts);
  }

  /** @return the object file name for the given source name. */
  private String getCompileOutputName(String name) {
    Linker ld = getCxxPlatform().getLd().resolve(getActionGraphBuilder());
    String outName = ld.hasFilePathSizeLimitations() ? "out" : getOutputName(name);
    return outName + "." + getCxxPlatform().getObjectFileExtension();
  }

  private String getCompileFlavorSuffix(String name) {
    return getOutputName(name) + "." + getCxxPlatform().getObjectFileExtension();
  }

  /** @return the output path for an object file compiled from the source with the given name. */
  @VisibleForTesting
  Path getCompileOutputPath(BuildTarget target, String name) {
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), target, "%s")
        .resolve(getCompileOutputName(name));
  }

  /**
   * @return a build target for a {@link CxxPreprocessAndCompile} rule for the source with the given
   *     name.
   */
  @VisibleForTesting
  public BuildTarget createCompileBuildTarget(String name) {
    String outputName = CxxFlavorSanitizer.sanitize(getCompileFlavorSuffix(name));
    return getBaseBuildTarget()
        .withAppendedFlavors(
            getCxxPlatform().getFlavor(),
            InternalFlavor.of(
                String.format(
                    COMPILE_FLAVOR_PREFIX + "%s%s",
                    getPicType() == PicType.PIC ? "pic-" : "",
                    outputName)));
  }

  public BuildTarget createInferCaptureBuildTarget(String name) {
    String outputName = CxxFlavorSanitizer.sanitize(getCompileFlavorSuffix(name));
    return getBaseBuildTarget()
        .withAppendedFlavors(
            getCxxPlatform().getFlavor(),
            InternalFlavor.of(
                String.format(
                    "%s-%s", CxxInferEnhancer.INFER_CAPTURE_FLAVOR.toString(), outputName)));
  }

  // Use a "lazy" method here to memoize the sanitizer function.  This is necessary as it's used to
  // construct `SanitizedArg` objects for flags which get put in `CxxToolFlags` objects which, in
  // turn, are used to index a cache for computing precompiled header hashes.  Therefore, the hash
  // code for this object is important, and since `Function`s use object equality/hash-codes, we
  // need a stable object each time.
  @Value.Lazy
  protected Function<String, String> getSanitizeFunction() {
    return getCxxPlatform().getCompilerDebugPathSanitizer().sanitize(Optional.empty());
  }

  private ImmutableList<Arg> sanitizedArgs(Iterable<String> flags) {
    return SanitizedArg.from(getSanitizeFunction(), flags);
  }

  private ImmutableList<Arg> getPlatformPreprocessorFlags(CxxSource.Type type) {
    return sanitizedArgs(CxxSourceTypes.getPlatformPreprocessFlags(getCxxPlatform(), type));
  }

  private ImmutableList<Arg> getPlatformCompileFlags(CxxSource.Type type) {
    ImmutableList.Builder<Arg> args = ImmutableList.builder();

    // Add in the source-type specific platform compiler flags.
    args.addAll(sanitizedArgs(CxxSourceTypes.getPlatformCompilerFlags(getCxxPlatform(), type)));

    // These source types require assembling, so add in platform-specific assembler flags.
    //
    // TODO(agallagher): We shouldn't care about lower-level assembling.  If the user has assembler
    // flags in mind which they want to propagate to other languages, they should pass them in via
    // some other means (e.g. `.buckconfig`).
    if (type == CxxSource.Type.C_CPP_OUTPUT
        || type == CxxSource.Type.OBJC_CPP_OUTPUT
        || type == CxxSource.Type.CXX_CPP_OUTPUT
        || type == CxxSource.Type.OBJCXX_CPP_OUTPUT
        || type == CxxSource.Type.CUDA_CPP_OUTPUT
        || type == CxxSource.Type.HIP_CPP_OUTPUT) {
      args.addAll(sanitizedArgs(getCxxPlatform().getAsflags()));
    }

    return args.build();
  }

  private Iterable<Arg> getRuleCompileFlags(CxxSource.Type type) {
    return getCompilerFlags().get(type);
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} rule that preprocesses, compiles, and assembles the
   *     given {@link CxxSource}.
   */
  private CxxPreprocessAndCompile createCompileBuildRule(String name, CxxSource source) {

    Preconditions.checkArgument(CxxSourceTypes.isCompilableType(source.getType()));

    BuildTarget target = createCompileBuildTarget(name);

    Compiler compiler =
        CxxSourceTypes.getCompiler(getCxxPlatform(), source.getType())
            .resolve(getActionGraphBuilder());

    // Build up the list of compiler flags.
    CxxToolFlags flags =
        CxxToolFlags.explicitBuilder()
            // If we're using pic, add in the appropriate flag.
            .addAllPlatformFlags(StringArg.from(getPicType().getFlags(compiler)))
            // Add in the platform specific compiler flags.
            .addAllPlatformFlags(getPlatformCompileFlags(source.getType()))
            // Add custom compiler flags.
            .addAllRuleFlags(getRuleCompileFlags(source.getType()))
            // Add custom per-file flags.
            .addAllRuleFlags(sanitizedArgs(source.getFlags()))
            .build();

    CompilerDelegate compilerDelegate =
        new CompilerDelegate(
            getCxxPlatform().getCompilerDebugPathSanitizer(),
            compiler,
            flags,
            getCxxPlatform().getUseArgFile());

    // TODO(steveo): this does not account for `precompiledHeaderRule`.

    // Build the CxxCompile rule and add it to our sorted set of build rules.
    return CxxPreprocessAndCompile.compile(
        target,
        getProjectFilesystem(),
        getRuleFinder(),
        compilerDelegate,
        getCompileOutputName(name),
        source.getPath(),
        source.getType(),
        getSanitizerForSourceType(source.getType()));
  }

  @VisibleForTesting
  public CxxPreprocessAndCompile requireCompileBuildRule(String name, CxxSource source) {
    CxxPreprocessAndCompile rule =
        (CxxPreprocessAndCompile)
            getActionGraphBuilder()
                .computeIfAbsent(
                    createCompileBuildTarget(name), target -> createCompileBuildRule(name, source));
    Preconditions.checkState(
        rule.getInput().equals(source.getPath()),
        "Hash collision for %s; a build rule would have been ignored.",
        name);
    return rule;
  }

  private CxxToolFlags computePreprocessorFlags(
      CxxSource.Type type, ImmutableList<String> sourceFlags) {
    Compiler compiler =
        CxxSourceTypes.getCompiler(getCxxPlatform(), CxxSourceTypes.getPreprocessorOutputType(type))
            .resolve(getActionGraphBuilder());
    return CxxToolFlags.explicitBuilder()
        .addAllPlatformFlags(StringArg.from(getPicType().getFlags(compiler)))
        .addAllPlatformFlags(getPlatformPreprocessorFlags(type))
        .addAllRuleFlags(rulePreprocessorFlags.apply(type))
        // Add custom per-file flags.
        .addAllRuleFlags(sanitizedArgs(sourceFlags))
        .build();
  }

  private CxxToolFlags computeCompilerFlags(
      CxxSource.Type type, ImmutableList<String> sourceFlags) {
    AbstractCxxSource.Type outputType = CxxSourceTypes.getPreprocessorOutputType(type);
    return CxxToolFlags.explicitBuilder()
        // If we're using pic, add in the appropriate flag.
        .addAllPlatformFlags(
            StringArg.from(
                getPicType()
                    .getFlags(
                        CxxSourceTypes.getCompiler(getCxxPlatform(), outputType)
                            .resolve(getActionGraphBuilder()))))
        // Add in the platform specific compiler flags.
        .addAllPlatformFlags(getPlatformCompileFlags(outputType))
        .addAllRuleFlags(getRuleCompileFlags(outputType))
        .addAllRuleFlags(sanitizedArgs(sourceFlags))
        .build();
  }

  private CxxInferCapture requireInferCaptureBuildRule(
      String name, CxxSource source, InferBuckConfig inferConfig) {
    return (CxxInferCapture)
        getActionGraphBuilder()
            .computeIfAbsent(
                createInferCaptureBuildTarget(name),
                target -> {
                  Preconditions.checkArgument(
                      CxxSourceTypes.isPreprocessableType(source.getType()));

                  LOG.verbose(
                      "Creating preprocessed InferCapture build rule %s for %s", target, source);

                  DepsBuilder depsBuilder = new DepsBuilder(getRuleFinder());
                  depsBuilder.add(requireAggregatedPreprocessDepsRule());

                  PreprocessorDelegateCacheValue preprocessorDelegateValue =
                      preprocessorDelegates.apply(
                          PreprocessorDelegateCacheKey.of(source.getType(), source.getFlags()));
                  depsBuilder.add(preprocessorDelegateValue.getPreprocessorDelegate());

                  CxxToolFlags ppFlags =
                      CxxToolFlags.copyOf(
                          getPlatformPreprocessorFlags(source.getType()),
                          rulePreprocessorFlags.apply(source.getType()));

                  CxxToolFlags cFlags = computeCompilerFlags(source.getType(), source.getFlags());

                  depsBuilder.add(source);

                  return new CxxInferCapture(
                      target,
                      getProjectFilesystem(),
                      depsBuilder.build(),
                      ppFlags,
                      cFlags,
                      source.getPath(),
                      source.getType(),
                      getPreInclude(),
                      getCompileOutputName(name),
                      preprocessorDelegateValue.getPreprocessorDelegate(),
                      inferConfig);
                });
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} rule that preprocesses, compiles, and assembles the
   *     given {@link CxxSource}.
   */
  private CxxPreprocessAndCompile createPreprocessAndCompileBuildRule(
      String name, CxxSource source) {

    BuildTarget target = createCompileBuildTarget(name);
    LOG.verbose("Creating preprocess and compile %s for %s", target, source);
    Preconditions.checkArgument(CxxSourceTypes.isPreprocessableType(source.getType()));

    CompilerDelegate compilerDelegate =
        new CompilerDelegate(
            getCxxPlatform().getCompilerDebugPathSanitizer(),
            CxxSourceTypes.getCompiler(
                    getCxxPlatform(), CxxSourceTypes.getPreprocessorOutputType(source.getType()))
                .resolve(getActionGraphBuilder()),
            computeCompilerFlags(source.getType(), source.getFlags()),
            getCxxPlatform().getUseArgFile());

    PreprocessorDelegateCacheValue preprocessorDelegateValue =
        preprocessorDelegates.apply(
            PreprocessorDelegateCacheKey.of(source.getType(), source.getFlags()));
    PreprocessorDelegate preprocessorDelegate = preprocessorDelegateValue.getPreprocessorDelegate();

    Optional<CxxPrecompiledHeader> precompiledHeaderRule =
        getOptionalPrecompiledHeader(preprocessorDelegateValue, source);
    if (precompiledHeaderRule.isPresent() && getPrecompiledHeader().isPresent()) {
      // For a precompiled header (and not a prefix header), we may need extra include paths.
      // The PCH build might have involved some deps that this rule does not have, so we
      // would need to pull in its include paths to ensure any includes that happen during this
      // build play out the same way as they did for the PCH.
      preprocessorDelegate =
          preprocessorDelegate.withLeadingIncludePaths(
              precompiledHeaderRule.get().getCxxIncludePaths());
    }

    return CxxPreprocessAndCompile.preprocessAndCompile(
        target,
        getProjectFilesystem(),
        getRuleFinder(),
        preprocessorDelegate,
        compilerDelegate,
        getCompileOutputName(name),
        source.getPath(),
        source.getType(),
        precompiledHeaderRule,
        getSanitizerForSourceType(source.getType()));
  }

  Optional<CxxPrecompiledHeader> getOptionalPrecompiledHeader(
      PreprocessorDelegateCacheValue preprocessorDelegateValue, CxxSource source) {

    if (!getPreInclude().isPresent()) {
      // Nothing to do.
      return Optional.empty();
    }

    CxxSource.Type sourceType = source.getType();
    if (sourceType.isAssembly()) {
      // Asm files do not use precompiled headers; indeed, CxxPrecompiledHeader will throw if
      // created for an assembly source.
      //
      // It's unclear why this is distinct from canUsePrecompiledHeaders(), or why a
      // CxxPrecompiledHeader can be created with canPrecompile = false.
      return Optional.empty();
    }

    return Optional.of(
        requirePrecompiledHeaderBuildRule(
            preprocessorDelegateValue,
            sourceType,
            source.getFlags(),
            getActionGraphBuilder(),
            getRuleFinder(),
            getPathResolver()));
  }

  @VisibleForTesting
  public CxxPreprocessAndCompile requirePreprocessAndCompileBuildRule(
      String name, CxxSource source) {
    CxxPreprocessAndCompile rule =
        (CxxPreprocessAndCompile)
            getActionGraphBuilder()
                .computeIfAbsent(
                    createCompileBuildTarget(name),
                    target -> createPreprocessAndCompileBuildRule(name, source));
    Preconditions.checkState(
        rule.getInput().equals(source.getPath()),
        "Hash collision for %s; a build rule would have been ignored.",
        name);
    return rule;
  }

  /**
   * Look up or build a precompiled header build rule which this build rule is requesting.
   *
   * <p>The PCH is requested either via a {@code prefix_header='<em>pathToHeaderFileOrTarget</em>'},
   * transparently converting the prefix header to a precompiled header, or a precompiled header
   * requested with {@code precompiled_header='<em>//:ruleToPCHTemplate</em>'}.
   *
   * <p>Compilers only accept precompiled headers generated with the same flags and language
   * options. As such, each prefix header may generate multiple pch files, and need unique build
   * targets to be differentiated in the build graph.
   *
   * <p>The {@code sourceType} and {@code sourceFlags} come from one of the source in the rule which
   * is using the PCH. This is so we can obtain certain flags (language options and such) so the PCH
   * is compatible with the rule requesting it.
   *
   * @param preprocessorDelegateCacheValue
   * @param sourceType
   * @param sourceFlags
   */
  private CxxPrecompiledHeader requirePrecompiledHeaderBuildRule(
      PreprocessorDelegateCacheValue preprocessorDelegateCacheValue,
      CxxSource.Type sourceType,
      ImmutableList<String> sourceFlags,
      ActionGraphBuilder graphBuilder,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver) {

    // This method should be called only if prefix/precompiled header param present.
    Preconditions.checkState(getPreInclude().isPresent());
    PreInclude pre = getPreInclude().get();

    return pre.getPrecompiledHeader(
        /* canPrecompile */ canUsePrecompiledHeaders(sourceType),
        preprocessorDelegateCacheValue.getPreprocessorDelegate(),
        (DependencyAggregation) requireAggregatedPreprocessDepsRule(),
        computeCompilerFlags(sourceType, sourceFlags),
        preprocessorDelegateCacheValue::getHash,
        preprocessorDelegateCacheValue::getBaseHash,
        getCxxPlatform(),
        sourceType,
        sourceFlags,
        graphBuilder,
        ruleFinder,
        pathResolver);
  }

  public ImmutableSet<CxxInferCapture> requireInferCaptureBuildRules(
      ImmutableMap<String, CxxSource> sources, InferBuckConfig inferConfig) {

    ImmutableSet.Builder<CxxInferCapture> objects = ImmutableSet.builder();

    CxxInferSourceFilter sourceFilter = new CxxInferSourceFilter(inferConfig);
    for (Map.Entry<String, CxxSource> entry : sources.entrySet()) {
      String name = entry.getKey();
      CxxSource source = entry.getValue();

      if (sourceFilter.isBlacklisted(source)) {
        continue;
      }

      Preconditions.checkState(
          CxxSourceTypes.isPreprocessableType(source.getType()),
          "Only preprocessable source types are currently supported");

      CxxInferCapture rule = requireInferCaptureBuildRule(name, source, inferConfig);
      objects.add(rule);
    }

    return objects.build();
  }

  public ImmutableMap<CxxPreprocessAndCompile, SourcePath> requirePreprocessAndCompileRules(
      ImmutableMap<String, CxxSource> sources) {

    return sources
        .entrySet()
        .stream()
        .map(
            entry -> {
              String name = entry.getKey();
              CxxSource source = entry.getValue();

              Preconditions.checkState(
                  CxxSourceTypes.isPreprocessableType(source.getType())
                      || CxxSourceTypes.isCompilableType(source.getType()));

              // If it's a preprocessable source, use a combine preprocess-and-compile build rule.
              // Otherwise, use a regular compile rule.
              if (CxxSourceTypes.isPreprocessableType(source.getType())) {
                return requirePreprocessAndCompileBuildRule(name, source);
              } else {
                return requireCompileBuildRule(name, source);
              }
            })
        .collect(
            ImmutableMap.toImmutableMap(
                Function.identity(), CxxPreprocessAndCompile::getSourcePathToOutput));
  }

  private DebugPathSanitizer getSanitizerForSourceType(CxxSource.Type type) {
    return type.isAssembly()
        ? getCxxPlatform().getAssemblerDebugPathSanitizer()
        : getCxxPlatform().getCompilerDebugPathSanitizer();
  }

  @Value.Immutable
  @BuckStyleTuple
  interface AbstractPreprocessorDelegateCacheKey {
    CxxSource.Type getSourceType();

    ImmutableList<String> getSourceFlags();
  }

  private class PreprocessorDelegateCacheValue {
    private final Function<AddsToRuleKey, String> commandHashCache = memoize(this::computeHash);
    private final PreprocessorDelegate preprocessorDelegate;
    private final Supplier<String> preprocessorHash;
    private final Supplier<String> preprocessorFullHash;

    private String computeHash(AddsToRuleKey object) {
      HashBuilder builder = new HashBuilder(commandHashCache);
      AlterRuleKeys.amendKey(builder, object);
      return builder.build();
    }

    PreprocessorDelegateCacheValue(
        PreprocessorDelegate preprocessorDelegate, DebugPathSanitizer sanitizer) {
      this.preprocessorDelegate = preprocessorDelegate;
      this.preprocessorHash =
          MoreSuppliers.memoize(
              () ->
                  computeHash(
                      new AddsToRuleKey() {
                        @AddToRuleKey
                        Preprocessor preprocessor = preprocessorDelegate.getPreprocessor();

                        @AddToRuleKey
                        CxxToolFlags nonIncludePathFlags =
                            preprocessorDelegate.getNonIncludePathFlags(getPathResolver());
                      }));
      this.preprocessorFullHash =
          MoreSuppliers.memoize(
              () ->
                  computeHash(
                      preprocessorDelegate.getSanitizedIncludePathFlags(
                          getPathResolver(), sanitizer)));
    }

    PreprocessorDelegate getPreprocessorDelegate() {
      return preprocessorDelegate;
    }

    @VisibleForTesting
    public String get(CxxToolFlags flags) {
      return this.commandHashCache.apply(flags);
    }

    public String getHash(CxxToolFlags compilerFlags) {
      return preprocessorHash.get()
          + "-"
          + preprocessorFullHash.get()
          + "-"
          + commandHashCache.apply(compilerFlags);
    }

    public String getBaseHash(CxxToolFlags compilerFlags) {
      return preprocessorHash.get() + "-" + commandHashCache.apply(compilerFlags);
    }
  }

  /** Quick and dirty memoized function. */
  private static <K, V> Function<K, V> memoize(Function<K, V> mappingFunction) {
    HashMap<K, V> cache = new HashMap<>();
    return k -> {
      V value = cache.get(k);
      if (value != null) {
        return value;
      }
      value = mappingFunction.apply(k);
      cache.put(k, value);
      return value;
    };
  }

  private class HashBuilder extends AbstractRuleKeyBuilder<String> {
    private final GuavaRuleKeyHasher hasher =
        new GuavaRuleKeyHasher(Hashing.murmur3_32().newHasher());
    private final Function<AddsToRuleKey, String> commandHashCache;

    public HashBuilder(Function<AddsToRuleKey, String> commandHashCache) {
      super(new NoopRuleKeyScopedHasher());
      this.commandHashCache = commandHashCache;
    }

    @Override
    public RuleKeyObjectSink setPath(Path absolutePath, Path ideallyRelative) {
      // This matches default rulekey computation (skipping the hash, though).
      if (ideallyRelative.isAbsolute()) {
        hasher.putString(ideallyRelative.getFileName().toString());
      } else {
        hasher.putString(ideallyRelative.toString());
      }
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<String> setSingleValue(@Nullable Object val) {
      if (val == null) { // Null value first
        hasher.putNull();
      } else if (val instanceof Boolean) { // JRE types
        hasher.putBoolean((boolean) val);
      } else if (val instanceof Enum) {
        hasher.putString(String.valueOf(val));
      } else if (val instanceof Number) {
        hasher.putNumber((Number) val);
      } else if (val instanceof String) {
        hasher.putString((String) val);
      } else if (val instanceof Pattern) {
        hasher.putPattern((Pattern) val);
      } else if (val instanceof byte[]) {
        hasher.putBytes((byte[]) val);
      } else {
        throw new RuntimeException("Unsupported value type: " + val.getClass());
      }
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<String> setBuildRule(BuildRule rule) {
      throw new IllegalStateException();
    }

    @Override
    protected AbstractRuleKeyBuilder<String> setAddsToRuleKey(AddsToRuleKey appendable) {
      hasher.putString(commandHashCache.apply(appendable));
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<String> setSourcePath(SourcePath sourcePath) {
      setPath(
          getPathResolver().getAbsolutePath(sourcePath),
          getPathResolver().getIdeallyRelativePath(sourcePath));
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<String> setNonHashingSourcePath(SourcePath sourcePath) {
      return setSourcePath(sourcePath);
    }

    @Override
    public String build() {
      return hasher.hash().toString();
    }
  }
}
