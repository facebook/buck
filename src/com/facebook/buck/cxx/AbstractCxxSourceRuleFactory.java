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

import com.facebook.buck.cxx.platform.Compiler;
import com.facebook.buck.cxx.platform.CxxPlatform;
import com.facebook.buck.cxx.platform.DebugPathSanitizer;
import com.facebook.buck.cxx.platform.Linker;
import com.facebook.buck.cxx.platform.Preprocessor;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DependencyAggregation;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Function;
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
  protected abstract BuildRuleResolver getResolver();

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

  @Value.Parameter
  protected abstract Optional<SymlinkTree> getSandboxTree();

  @Value.Check
  protected void checkPrefixAndPrecompiledHeaderArgs() {
    if (getPrefixHeader().isPresent() && getPrecompiledHeader().isPresent()) {
      throw new HumanReadableException(
          "Cannot use `prefix_header` and `precompiled_header` in the same rule.");
    }
  }

  @Value.Lazy
  protected ImmutableSortedSet<BuildRule> getPreprocessDeps() {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    for (CxxPreprocessorInput input : getCxxPreprocessorInput()) {
      builder.addAll(input.getDeps(getResolver(), getRuleFinder()));
    }
    if (getPrefixHeader().isPresent()) {
      builder.addAll(getRuleFinder().filterBuildRuleInputs(getPrefixHeader().get()));
    }
    if (getPrecompiledHeader().isPresent()) {
      builder.addAll(getRuleFinder().filterBuildRuleInputs(getPrecompiledHeader().get()));
    }
    if (getSandboxTree().isPresent()) {
      SymlinkTree tree = getSandboxTree().get();
      builder.add(tree);
      builder.addAll(getRuleFinder().filterBuildRuleInputs(tree.getLinks().values()));
    }
    return builder.build();
  }

  @Value.Lazy
  protected ImmutableSet<FrameworkPath> getFrameworks() {
    return getCxxPreprocessorInput()
        .stream()
        .flatMap(input -> input.getFrameworks().stream())
        .collect(MoreCollectors.toImmutableSet());
  }

  @Value.Lazy
  protected ImmutableList<CxxHeaders> getIncludes() {
    ImmutableList<CxxHeaders> result =
        getCxxPreprocessorInput()
            .stream()
            .flatMap(input -> input.getIncludes().stream())
            .collect(MoreCollectors.toImmutableList());
    try {
      CxxHeaders.checkConflictingHeaders(result);
    } catch (CxxHeaders.ConflictingHeadersException e) {
      throw e.getHumanReadableExceptionForBuildTarget(getBaseBuildTarget());
    }
    return result;
  }

  private final Function<CxxSource.Type, ImmutableList<Arg>> rulePreprocessorFlags =
      memoize(
          type ->
              getCxxPreprocessorInput()
                  .stream()
                  .flatMap(input -> input.getPreprocessorFlags().get(type).stream())
                  .collect(MoreCollectors.toImmutableList()));

  private final Function<PreprocessorDelegateCacheKey, PreprocessorDelegateCacheValue>
      preprocessorDelegates =
          memoize(
              key -> {
                Preprocessor preprocessor =
                    CxxSourceTypes.getPreprocessor(getCxxPlatform(), key.getSourceType())
                        .resolve(getResolver());
                PreprocessorDelegate delegate =
                    new PreprocessorDelegate(
                        getPathResolver(),
                        getCxxPlatform().getCompilerDebugPathSanitizer(),
                        getCxxPlatform().getHeaderVerification(),
                        getProjectFilesystem().getRootPath(),
                        preprocessor,
                        PreprocessorFlags.of(
                            getPrefixHeader(),
                            computePreprocessorFlags(key.getSourceType(), key.getSourceFlags()),
                            getIncludes(),
                            getFrameworks()),
                        CxxDescriptionEnhancer.frameworkPathToSearchPath(
                            getCxxPlatform(), getPathResolver()),
                        getSandboxTree(),
                        /* leadingIncludePaths */ Optional.empty());
                return new PreprocessorDelegateCacheValue(delegate);
              });

  /**
   * Returns the no-op rule that aggregates the preprocessor dependencies.
   *
   * <p>Individual compile rules can depend on it, instead of having to depend on every preprocessor
   * dep themselves. This turns O(n*m) dependencies into O(n+m) dependencies, where n is number of
   * files in a target, and m is the number of targets.
   */
  private BuildRule requireAggregatedPreprocessDepsRule() {
    return getResolver()
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
    Linker ld = getCxxPlatform().getLd().resolve(getResolver());
    String outName = ld.hasFilePathSizeLimitations() ? "out" : getOutputName(name);
    return outName + "." + getCxxPlatform().getObjectFileExtension();
  }

  private String getCompileFlavorSuffix(String name) {
    return getOutputName(name) + "." + getCxxPlatform().getObjectFileExtension();
  }

  /** @return the output path for an object file compiled from the source with the given name. */
  @VisibleForTesting
  Path getCompileOutputPath(BuildTarget target, String name) {
    return BuildTargets.getGenPath(getProjectFilesystem(), target, "%s")
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
  protected com.google.common.base.Function<String, String> getSanitizeFunction() {
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
        || type == CxxSource.Type.CUDA_CPP_OUTPUT) {
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
    DepsBuilder depsBuilder = new DepsBuilder(getRuleFinder());

    Compiler compiler =
        CxxSourceTypes.getCompiler(getCxxPlatform(), source.getType()).resolve(getResolver());

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
            getPathResolver(), getCxxPlatform().getCompilerDebugPathSanitizer(), compiler, flags);
    depsBuilder.add(compilerDelegate);

    depsBuilder.add(source);

    // Build the CxxCompile rule and add it to our sorted set of build rules.
    return CxxPreprocessAndCompile.compile(
        target,
        getProjectFilesystem(),
        buildRuleParamsWithAndDeps(depsBuilder.build()),
        compilerDelegate,
        getCompileOutputPath(target, name),
        source.getPath(),
        source.getType(),
        getSanitizerForSourceType(source.getType()),
        getSandboxTree());
  }

  @VisibleForTesting
  public CxxPreprocessAndCompile requireCompileBuildRule(String name, CxxSource source) {
    CxxPreprocessAndCompile rule =
        (CxxPreprocessAndCompile)
            getResolver()
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
            .resolve(getResolver());
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
                            .resolve(getResolver()))))
        // Add in the platform specific compiler flags.
        .addAllPlatformFlags(getPlatformCompileFlags(outputType))
        .addAllRuleFlags(getRuleCompileFlags(outputType))
        .addAllRuleFlags(sanitizedArgs(sourceFlags))
        .build();
  }

  private CxxInferCapture requireInferCaptureBuildRule(
      String name, CxxSource source, InferBuckConfig inferConfig) {
    return (CxxInferCapture)
        getResolver()
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
                      buildRuleParamsWithAndDeps(depsBuilder.build()),
                      ppFlags,
                      cFlags,
                      source.getPath(),
                      source.getType(),
                      getCompileOutputPath(target, name),
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

    DepsBuilder depsBuilder = new DepsBuilder(getRuleFinder());
    depsBuilder.add(requireAggregatedPreprocessDepsRule());

    CompilerDelegate compilerDelegate =
        new CompilerDelegate(
            getPathResolver(),
            getCxxPlatform().getCompilerDebugPathSanitizer(),
            CxxSourceTypes.getCompiler(
                    getCxxPlatform(), CxxSourceTypes.getPreprocessorOutputType(source.getType()))
                .resolve(getResolver()),
            computeCompilerFlags(source.getType(), source.getFlags()));
    depsBuilder.add(compilerDelegate);

    PreprocessorDelegateCacheValue preprocessorDelegateValue =
        preprocessorDelegates.apply(
            PreprocessorDelegateCacheKey.of(source.getType(), source.getFlags()));
    PreprocessorDelegate preprocessorDelegate = preprocessorDelegateValue.getPreprocessorDelegate();
    depsBuilder.add(preprocessorDelegate);

    depsBuilder.add(source);

    Preprocessor preprocessor = preprocessorDelegate.getPreprocessor();

    if (getPrecompiledHeader().isPresent()
        && !canUsePrecompiledHeaders(getCxxBuckConfig(), preprocessor, source.getType())) {
      throw new HumanReadableException(
          "Precompiled header was requested for rule \""
              + this.getBaseBuildTarget().toString()
              + "\", but PCH's are not possible under "
              + "the current environment (preprocessor/compiler, source file's language, "
              + "and/or 'cxx.pch_enabled' option).");
    }

    Optional<CxxPrecompiledHeader> precompiledHeaderRule = Optional.empty();
    if (canUsePrecompiledHeaders(getCxxBuckConfig(), preprocessor, source.getType())
        && (getPrefixHeader().isPresent() || getPrecompiledHeader().isPresent())) {
      precompiledHeaderRule =
          Optional.of(
              requirePrecompiledHeaderBuildRule(
                  preprocessorDelegateValue, source.getType(), source.getFlags()));
      depsBuilder.add(precompiledHeaderRule.get());
      if (getPrecompiledHeader().isPresent()) {
        // For a precompiled header (and not a prefix header), we may need extra include paths.
        // The PCH build might have involved some deps that this rule does not have, so we
        // would need to pull in its include paths to ensure any includes that happen during this
        // build play out the same way as they did for the PCH.
        preprocessorDelegate =
            preprocessorDelegate.withLeadingIncludePaths(
                precompiledHeaderRule.get().getCxxIncludePaths());
      }
    }

    return CxxPreprocessAndCompile.preprocessAndCompile(
        target,
        getProjectFilesystem(),
        buildRuleParamsWithAndDeps(depsBuilder.build()),
        preprocessorDelegate,
        compilerDelegate,
        getCompileOutputPath(target, name),
        source.getPath(),
        source.getType(),
        precompiledHeaderRule,
        getSanitizerForSourceType(source.getType()),
        getSandboxTree());
  }

  @VisibleForTesting
  public CxxPreprocessAndCompile requirePreprocessAndCompileBuildRule(
      String name, CxxSource source) {
    CxxPreprocessAndCompile rule =
        (CxxPreprocessAndCompile)
            getResolver()
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
      ImmutableList<String> sourceFlags) {

    // This method is called only if one of these is present; guarantee that for the if/else below.
    Preconditions.checkState(getPrefixHeader().isPresent() ^ getPrecompiledHeader().isPresent());

    return getPrefixHeader().isPresent()
        ? buildPrecompiledHeaderFromPrefixHeader(
            preprocessorDelegateCacheValue, sourceType, sourceFlags, getPrefixHeader().get())
        : buildPrecompiledHeaderFromTemplateRule(
            preprocessorDelegateCacheValue, sourceType, sourceFlags, getPrecompiledHeader().get());
  }

  private CxxPrecompiledHeader buildPrecompiledHeaderFromPrefixHeader(
      PreprocessorDelegateCacheValue preprocessorDelegateCacheValue,
      CxxSource.Type sourceType,
      ImmutableList<String> sourceFlags,
      SourcePath headerPath) {

    DepsBuilder depsBuilder = new DepsBuilder(getRuleFinder());

    // We need the preprocessor deps for this rule, for its prefix header.
    depsBuilder.add(preprocessorDelegateCacheValue.getPreprocessorDelegate());
    depsBuilder.add(requireAggregatedPreprocessDepsRule());

    CxxToolFlags compilerFlags = computeCompilerFlags(sourceType, sourceFlags);

    // Language needs to be part of the key, PCHs built under a different language are incompatible.
    // (Replace `c++` with `cxx`; avoid default scrubbing which would make it the cryptic `c__`.)
    final String langCode = sourceType.getLanguage().replaceAll("c\\+\\+", "cxx");

    final String pchBaseID =
        "pch-" + langCode + "-" + preprocessorDelegateCacheValue.getBaseHash(compilerFlags);
    final String pchFullID =
        pchBaseID + "-" + preprocessorDelegateCacheValue.getFullHash(compilerFlags);

    return requirePrecompiledHeader(
        preprocessorDelegateCacheValue.getPreprocessorDelegate(),
        sourceType,
        compilerFlags,
        headerPath,
        depsBuilder,
        getBaseBuildTarget().getUnflavoredBuildTarget(),
        ImmutableSortedSet.of(
            getCxxPlatform().getFlavor(),
            InternalFlavor.of(Flavor.replaceInvalidCharacters(pchFullID))));
  }

  /**
   * Build a PCH rule, given a {@code cxx_precompiled_header} rule.
   *
   * <p>We'll "instantiate" this PCH from this template, using the parameters (src, dependencies)
   * from the template itself, plus the build flags that are used in the current build rule (so that
   * this instantiated version uses compatible build flags and thus the PCH is guaranteed usable
   * with this rule).
   */
  private CxxPrecompiledHeader buildPrecompiledHeaderFromTemplateRule(
      PreprocessorDelegateCacheValue preprocessorDelegateCacheValue,
      CxxSource.Type sourceType,
      ImmutableList<String> sourceFlags,
      SourcePath headerTargetPath) {

    DepsBuilder depsBuilder = new DepsBuilder(getRuleFinder());

    PreprocessorDelegate preprocessorDelegateForCxxRule =
        preprocessorDelegateCacheValue.getPreprocessorDelegate();

    Preprocessor preprocessor = preprocessorDelegateForCxxRule.getPreprocessor();

    BuildTarget pchTemplateTarget = ((BuildTargetSourcePath) headerTargetPath).getTarget();
    Optional<CxxPrecompiledHeaderTemplate> pchTemplateRuleOpt =
        getResolver()
            .getRuleOptionalWithType(pchTemplateTarget, CxxPrecompiledHeaderTemplate.class);
    Preconditions.checkState(pchTemplateRuleOpt.isPresent());
    CxxPrecompiledHeaderTemplate pchTemplate = pchTemplateRuleOpt.get();

    // Build compiler flags, taking from the source rule, but leaving out its deps.
    // We just need the flags pertaining to PCH compatibility: language, PIC, macros, etc.
    // and nothing related to the deps of this particular rule (hence 'getNonIncludePathFlags').
    CxxToolFlags compilerFlags =
        CxxToolFlags.concat(
            preprocessorDelegateForCxxRule.getNonIncludePathFlags(/* no pch */ Optional.empty()),
            computeCompilerFlags(sourceType, sourceFlags));

    // Now build a new pp-delegate specially for this PCH rule.
    PreprocessorDelegate preprocessorDelegate =
        pchTemplate.buildPreprocessorDelegate(
            getPathResolver(), getCxxPlatform(), preprocessor, compilerFlags);

    // Language needs to be part of the key, PCHs built under a different language are incompatible.
    // (Replace `c++` with `cxx`; avoid default scrubbing which would make it the cryptic `c__`.)
    final String langCode = sourceType.getLanguage().replaceAll("c\\+\\+", "cxx");
    final String pchBaseID =
        "pch-" + langCode + "-" + preprocessorDelegateCacheValue.getBaseHash(compilerFlags);

    for (BuildRule rule : pchTemplate.getBuildDeps()) {
      depsBuilder.add(rule);
    }

    depsBuilder.add(
        pchTemplate.requireAggregatedDepsRule(getResolver(), getRuleFinder(), getCxxPlatform()));
    depsBuilder.add(preprocessorDelegate);

    return requirePrecompiledHeader(
        preprocessorDelegate,
        sourceType,
        compilerFlags,
        pchTemplate.sourcePath,
        depsBuilder,
        pchTemplateTarget.getUnflavoredBuildTarget(),
        ImmutableSortedSet.of(
            getCxxPlatform().getFlavor(),
            InternalFlavor.of(Flavor.replaceInvalidCharacters(pchBaseID))));
  }

  /**
   * Look up or build a precompiled header build rule which this build rule is requesting.
   *
   * <p>This method will first try to determine whether a matching PCH was already created; if so,
   * it will be reused. This is done by searching the cache in the {@link BuildRuleResolver} owned
   * by this class. If this ends up building a new instance of {@link CxxPrecompiledHeader}, it will
   * be added to the resolver cache.
   */
  private CxxPrecompiledHeader requirePrecompiledHeader(
      PreprocessorDelegate preprocessorDelegate,
      CxxSource.Type sourceType,
      CxxToolFlags compilerFlags,
      SourcePath headerPath,
      DepsBuilder depsBuilder,
      UnflavoredBuildTarget templateTarget,
      ImmutableSortedSet<Flavor> flavors) {
    return (CxxPrecompiledHeader)
        getResolver()
            .computeIfAbsent(
                BuildTarget.of(templateTarget, flavors),
                target -> {
                  // Give the PCH a filename that looks like a header file with .gch appended to it, GCC-style.
                  // GCC accepts an "-include" flag with the .h file as its arg, and auto-appends ".gch" to
                  // automagically use the precompiled header in place of the original header.  Of course in
                  // our case we'll only have the ".gch" file, which is alright; the ".h" isn't truly needed.
                  Path output = BuildTargets.getGenPath(getProjectFilesystem(), target, "%s.h.gch");

                  CompilerDelegate compilerDelegate =
                      new CompilerDelegate(
                          getPathResolver(),
                          getCxxPlatform().getCompilerDebugPathSanitizer(),
                          CxxSourceTypes.getCompiler(
                                  getCxxPlatform(),
                                  CxxSourceTypes.getPreprocessorOutputType(sourceType))
                              .resolve(getResolver()),
                          compilerFlags);
                  depsBuilder.add(compilerDelegate);

                  depsBuilder.add(headerPath);

                  return new CxxPrecompiledHeader(
                      target,
                      getProjectFilesystem(),
                      buildRuleParamsWithAndDeps(depsBuilder.build()),
                      output,
                      preprocessorDelegate,
                      compilerDelegate,
                      compilerFlags,
                      headerPath,
                      sourceType,
                      getCxxPlatform().getCompilerDebugPathSanitizer());
                });
  }

  public ImmutableSet<CxxInferCapture> requireInferCaptureBuildRules(
      ImmutableMap<String, CxxSource> sources, InferBuckConfig inferConfig) {

    ImmutableSet.Builder<CxxInferCapture> objects = ImmutableSet.builder();

    CxxInferSourceFilter sourceFilter = new CxxInferSourceFilter(inferConfig);
    for (Map.Entry<String, CxxSource> entry : sources.entrySet()) {
      String name = entry.getKey();
      CxxSource source = entry.getValue();

      Preconditions.checkState(
          CxxSourceTypes.isPreprocessableType(source.getType()),
          "Only preprocessable source types are currently supported");

      if (sourceFilter.isBlacklisted(source)) {
        continue;
      }

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

              source = getSandboxedCxxSource(source);

              // If it's a preprocessable source, use a combine preprocess-and-compile build rule.
              // Otherwise, use a regular compile rule.
              if (CxxSourceTypes.isPreprocessableType(source.getType())) {
                return requirePreprocessAndCompileBuildRule(name, source);
              } else {
                return requireCompileBuildRule(name, source);
              }
            })
        .collect(
            MoreCollectors.toImmutableMap(
                Function.identity(), CxxPreprocessAndCompile::getSourcePathToOutput));
  }

  private CxxSource getSandboxedCxxSource(CxxSource source) {
    if (getSandboxTree().isPresent()) {
      SymlinkTree sandboxTree = getSandboxTree().get();
      Path sourcePath =
          Paths.get(getPathResolver().getSourcePathName(getBaseBuildTarget(), source.getPath()));
      Path sandboxPath =
          BuildTargets.getGenPath(getProjectFilesystem(), sandboxTree.getBuildTarget(), "%s");
      ExplicitBuildTargetSourcePath path =
          new ExplicitBuildTargetSourcePath(
              sandboxTree.getBuildTarget(), sandboxPath.resolve(sourcePath));
      source = CxxSource.copyOf(source).withPath(path);
    }
    return source;
  }

  /** Can PCH headers be used with the current configuration and type of compiler? */
  @VisibleForTesting
  boolean canUsePrecompiledHeaders(
      CxxBuckConfig cxxBuckConfig, Preprocessor preprocessor, CxxSource.Type sourceType) {
    return cxxBuckConfig.isPCHEnabled()
        && preprocessor.supportsPrecompiledHeaders()
        && sourceType.getPrecompiledHeaderLanguage().isPresent();
  }

  private DebugPathSanitizer getSanitizerForSourceType(CxxSource.Type type) {
    return type.isAssembly()
        ? getCxxPlatform().getAssemblerDebugPathSanitizer()
        : getCxxPlatform().getCompilerDebugPathSanitizer();
  }

  private BuildRuleParams buildRuleParamsWithAndDeps(SortedSet<BuildRule> deps) {
    return new BuildRuleParams(() -> deps, () -> ImmutableSortedSet.of(), ImmutableSortedSet.of());
  }

  public enum PicType {

    // Generate position-independent code (e.g. for use in shared libraries).
    PIC {
      @Override
      public ImmutableList<String> getFlags(Compiler compiler) {
        return compiler.getPicFlags();
      }
    },

    // Generate position-dependent code.
    PDC {
      @Override
      public ImmutableList<String> getFlags(Compiler compiler) {
        return compiler.getPdcFlags();
      }
    };

    abstract ImmutableList<String> getFlags(Compiler compiler);
  }

  @Value.Immutable
  @BuckStyleTuple
  interface AbstractPreprocessorDelegateCacheKey {
    CxxSource.Type getSourceType();

    ImmutableList<String> getSourceFlags();
  }

  @VisibleForTesting
  public ImmutableList<Arg> getFlagsForSource(CxxSource source, boolean allowIncludePathFlags) {
    PreprocessorDelegateCacheValue preprocessorDelegateValue =
        preprocessorDelegates.apply(
            PreprocessorDelegateCacheKey.of(source.getType(), source.getFlags()));
    CxxToolFlags flags = computeCompilerFlags(source.getType(), source.getFlags());
    PreprocessorDelegateCacheValue.HashStrings hashStrings = preprocessorDelegateValue.get(flags);
    return allowIncludePathFlags ? hashStrings.fullFlags : hashStrings.baseFlags;
  }

  private static class PreprocessorDelegateCacheValue {
    private final PreprocessorDelegate preprocessorDelegate;

    // Note: this hash call is mainly for the benefit of precompiled headers, to produce
    // the PCH's hash of build flags.  (Since there's no PCH yet, the PCH argument is
    // passed as empty here.)
    private final Function<CxxToolFlags, HashStrings> commandHashCache = memoize(HashStrings::new);

    class HashStrings {
      /** List of build flags (as strings), except for those related to header search paths. */
      public final ImmutableList<Arg> baseFlags;
      /** Complete list of all build flags (as strings), including header search paths. */
      public final ImmutableList<Arg> fullFlags;

      public final String baseHash;
      public final String fullHash;

      public HashStrings(CxxToolFlags compilerFlags) {
        ImmutableList.Builder<Arg> builder = ImmutableList.builder();

        // Add the build command itself first
        builder.addAll(StringArg.from(preprocessorDelegate.getCommandPrefix()));
        // Then preprocessor + compiler args, not including include path args like -I, -isystem, ...
        builder.addAll(preprocessorDelegate.getNonIncludePathFlags(Optional.empty()).getAllFlags());
        builder.addAll(compilerFlags.getAllFlags());
        // Output what we have so far, to this list, then hash it.
        this.baseFlags = builder.build();
        this.baseHash = preprocessorDelegate.hashCommand(this.baseFlags).substring(0, 10);

        // Continue building.  Using the same builder; add header search paths, to the above flags.
        builder.addAll(preprocessorDelegate.getIncludePathFlags().getAllFlags());
        // Output this super-set of flags to this list, then hash it.
        this.fullFlags = builder.build();
        this.fullHash = preprocessorDelegate.hashCommand(this.fullFlags).substring(0, 10);
      }
    }

    PreprocessorDelegateCacheValue(PreprocessorDelegate preprocessorDelegate) {
      this.preprocessorDelegate = preprocessorDelegate;
    }

    PreprocessorDelegate getPreprocessorDelegate() {
      return preprocessorDelegate;
    }

    @VisibleForTesting
    public HashStrings get(CxxToolFlags flags) {
      return this.commandHashCache.apply(flags);
    }

    String getBaseHash(CxxToolFlags flags) {
      return get(flags).baseHash;
    }

    String getFullHash(CxxToolFlags flags) {
      return get(flags).fullHash;
    }
  }

  /** Quick and dirty memoized function. */
  private static <K, V> Function<K, V> memoize(Function<K, V> mappingFunction) {
    HashMap<K, V> cache = new HashMap<>();
    return k -> cache.computeIfAbsent(k, mappingFunction);
  }
}
