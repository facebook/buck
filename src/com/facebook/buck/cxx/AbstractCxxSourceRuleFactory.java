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

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DependencyAggregation;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.immutables.value.Value;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCxxSourceRuleFactory {

  private static final Logger LOG = Logger.get(AbstractCxxSourceRuleFactory.class);
  private static final String COMPILE_FLAVOR_PREFIX = "compile-";
  private static final String PREPROCESS_FLAVOR_PREFIX = "preprocess-";
  private static final Flavor AGGREGATED_PREPROCESS_DEPS_FLAVOR =
      ImmutableFlavor.of("preprocessor-deps");

  @Value.Parameter
  public abstract BuildRuleParams getParams();
  @Value.Parameter
  public abstract BuildRuleResolver getResolver();
  @Value.Parameter
  public abstract SourcePathResolver getPathResolver();
  @Value.Parameter
  public abstract CxxBuckConfig getCxxBuckConfig();
  @Value.Parameter
  public abstract CxxPlatform getCxxPlatform();
  @Value.Parameter
  public abstract ImmutableList<CxxPreprocessorInput> getCxxPreprocessorInput();
  @Value.Parameter
  public abstract ImmutableMultimap<CxxSource.Type, String> getCompilerFlags();
  @Value.Parameter
  public abstract Optional<SourcePath> getPrefixHeader();
  @Value.Parameter
  public abstract PicType getPicType();
  @Value.Parameter
  public abstract Optional<SymlinkTree> getSandboxTree();

  private ImmutableSortedSet<BuildRule> getPreprocessDeps() {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    for (CxxPreprocessorInput input : getCxxPreprocessorInput()) {
      builder.addAll(input.getDeps(getResolver(), getPathResolver()));
    }
    if (getPrefixHeader().isPresent()) {
      builder.addAll(getPathResolver().filterBuildRuleInputs(getPrefixHeader().get()));
    }
    if (getSandboxTree().isPresent()) {
      SymlinkTree tree = getSandboxTree().get();
      builder.add(tree);
      builder.addAll(getPathResolver().filterBuildRuleInputs(tree.getLinks().values()));
    }
    return builder.build();
  }

  @Value.Lazy
  protected ImmutableSet<FrameworkPath> getFrameworks() {
    return getCxxPreprocessorInput().stream()
        .flatMap(input -> input.getFrameworks().stream())
        .collect(MoreCollectors.toImmutableSet());
  }

  @Value.Lazy
  protected ImmutableList<CxxHeaders> getIncludes() {
    return getCxxPreprocessorInput().stream()
        .flatMap(input -> input.getIncludes().stream())
        .collect(MoreCollectors.toImmutableList());
  }

  @Value.Lazy
  protected ImmutableSet<Path> getSystemIncludeRoots() {
    return getCxxPreprocessorInput().stream()
        .flatMap(input -> input.getSystemIncludeRoots().stream())
        .collect(MoreCollectors.toImmutableSet());
  }

  private final LoadingCache<CxxSource.Type, ImmutableList<String>> preprocessorFlags =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<CxxSource.Type, ImmutableList<String>>() {
                @Override
                public ImmutableList<String> load(@Nonnull CxxSource.Type type) {
                  ImmutableList.Builder<String> builder = ImmutableList.builder();
                  for (CxxPreprocessorInput input : getCxxPreprocessorInput()) {
                    builder.addAll(input.getPreprocessorFlags().get(type));
                  }
                  return builder.build();
                }
              });

  private final LoadingCache<PreprocessorDelegateCacheKey, PreprocessorDelegateCacheValue>
      preprocessorDelegates = CacheBuilder.newBuilder()
      .build(new PreprocessorDelegateCacheLoader());

  /**
   * Returns the no-op rule that aggregates the preprocessor dependencies.
   *
   * Individual compile rules can depend on it, instead of having to depend on every preprocessor
   * dep themselves. This turns O(n*m) dependencies into O(n+m) dependencies, where n is number of
   * files in a target, and m is the number of targets.
   */
  private BuildRule requireAggregatedPreprocessDepsRule() {
    BuildTarget target = createAggregatedPreprocessDepsBuildTarget();
    Optional<DependencyAggregation> existingRule =
        getResolver().getRuleOptionalWithType(target, DependencyAggregation.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    } else {
      BuildRuleParams params = getParams().copyWithChanges(
          target,
          Suppliers.ofInstance(getPreprocessDeps()),
          Suppliers.ofInstance(ImmutableSortedSet.of()));
      DependencyAggregation rule = new DependencyAggregation(params, getPathResolver());
      getResolver().addToIndex(rule);
      return rule;
    }
  }

  @VisibleForTesting
  BuildTarget createAggregatedPreprocessDepsBuildTarget() {
    return BuildTarget.builder(getParams().getBuildTarget())
        .addFlavors(getCxxPlatform().getFlavor(), AGGREGATED_PREPROCESS_DEPS_FLAVOR)
        .build();
  }

  private String getOutputName(String name) {
    List<String> parts = Lists.newArrayList();
    for (String part : Splitter.on(File.separator).omitEmptyStrings().split(name)) {
      // TODO(#7877540): Remove once we prevent disabling package boundary checks.
      parts.add(part.equals("..") ? "__PAR__" : part);
    }
    return Joiner.on(File.separator).join(parts);
  }

  /**
   * @return the preprocessed file name for the given source name.
   */
  private String getPreprocessOutputName(CxxSource.Type type, String name) {
    CxxSource.Type outputType = CxxSourceTypes.getPreprocessorOutputType(type);
    return getOutputName(name) + "." + Iterables.get(outputType.getExtensions(), 0);
  }

  /**
   * @return a {@link BuildTarget} used for the rule that preprocesses the source by the given
   * name and type.
   */
  @VisibleForTesting
  public BuildTarget createPreprocessBuildTarget(String name, CxxSource.Type type) {
    String outputName = CxxFlavorSanitizer.sanitize(
        getPreprocessOutputName(
            type,
            name));
    return BuildTarget
        .builder(getParams().getBuildTarget())
        .addFlavors(getCxxPlatform().getFlavor())
        .addFlavors(
            ImmutableFlavor.of(
                String.format(
                    PREPROCESS_FLAVOR_PREFIX + "%s%s",
                    getPicType() == PicType.PIC ? "pic-" : "",
                    outputName)))
        .build();
  }

  public static boolean isPreprocessFlavoredBuildTarget(BuildTarget target) {
    Set<Flavor> flavors = target.getFlavors();
    for (Flavor flavor : flavors) {
      if (flavor.getName().startsWith(PREPROCESS_FLAVOR_PREFIX)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return the output path for an object file compiled from the source with the given name.
   */
  @VisibleForTesting
  Path getPreprocessOutputPath(BuildTarget target, CxxSource.Type type, String name) {
    return BuildTargets.getGenPath(getParams().getProjectFilesystem(), target, "%s")
        .resolve(getPreprocessOutputName(type, name));
  }

  @VisibleForTesting
  public CxxPreprocessAndCompile createPreprocessBuildRule(String name, CxxSource source) {
    Preconditions.checkArgument(CxxSourceTypes.isPreprocessableType(source.getType()));

    BuildTarget target = createPreprocessBuildTarget(name, source.getType());

    DepsBuilder depsBuilder = new DepsBuilder(getPathResolver());
    depsBuilder.add(requireAggregatedPreprocessDepsRule());

    PreprocessorDelegateCacheValue preprocessorDelegateValue = preprocessorDelegates.getUnchecked(
        PreprocessorDelegateCacheKey.of(source.getType(), source.getFlags()));
    depsBuilder.add(preprocessorDelegateValue.getPreprocessorDelegate());

    CompilerDelegate compilerDelegate =
        new CompilerDelegate(
            getPathResolver(),
            getCxxPlatform().getCompilerDebugPathSanitizer(),
            CxxSourceTypes.getCompiler(
                getCxxPlatform(),
                CxxSourceTypes.getPreprocessorOutputType(source.getType()))
                .resolve(getResolver()),
            computeCompilerFlags(source.getType(), source.getFlags()));
    // We shouldn't really need to depend on the compiler for preprocess-only
    // rules, but the `CxxPreprocessAndCompile` class adds the entire
    // `CompilerDelegate` to the rule key, which means the input-based rule key
    // factory expects to be included in the dep list.
    depsBuilder.add(compilerDelegate);

    depsBuilder.add(source);

    // Build the CxxCompile rule and add it to our sorted set of build rules.
    CxxPreprocessAndCompile result =
        CxxPreprocessAndCompile.preprocess(
            getParams().copyWithChanges(
                target,
                Suppliers.ofInstance(depsBuilder.build()),
                Suppliers.ofInstance(ImmutableSortedSet.of())),
            getPathResolver(),
            preprocessorDelegateValue.getPreprocessorDelegate(),
            compilerDelegate,
            getPreprocessOutputPath(target, source.getType(), name),
            source.getPath(),
            source.getType(),
            getCxxPlatform().getCompilerDebugPathSanitizer(),
            getCxxPlatform().getAssemblerDebugPathSanitizer(),
            getSandboxTree());
    getResolver().addToIndex(result);
    return result;
  }

  @VisibleForTesting
  CxxPreprocessAndCompile requirePreprocessBuildRule(String name, CxxSource source) {
    BuildTarget target = createPreprocessBuildTarget(name, source.getType());
    Optional<CxxPreprocessAndCompile> existingRule = getResolver().getRuleOptionalWithType(
        target, CxxPreprocessAndCompile.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }
    return createPreprocessBuildRule(name, source);
  }

  /**
   * @return the object file name for the given source name.
   */
  private String getCompileOutputName(String name) {
    Linker ld = getCxxPlatform().getLd().resolve(getResolver());
    String outName = ld.hasFilePathSizeLimitations() ? "out" : getOutputName(name);
    return outName + "." + getCxxPlatform().getObjectFileExtension();
  }

  private String getCompileFlavorSuffix(String name) {
    return getOutputName(name) + "." + getCxxPlatform().getObjectFileExtension();
  }

  /**
   * @return the output path for an object file compiled from the source with the given name.
   */
  @VisibleForTesting
  Path getCompileOutputPath(BuildTarget target, String name) {
    return BuildTargets.getGenPath(getParams().getProjectFilesystem(), target, "%s")
        .resolve(getCompileOutputName(name));
  }

  /**
   * @return a build target for a {@link CxxPreprocessAndCompile} rule for the source with the
   * given name.
   */
  @VisibleForTesting
  public BuildTarget createCompileBuildTarget(String name) {
    String outputName = CxxFlavorSanitizer.sanitize(getCompileFlavorSuffix(name));
    return BuildTarget
        .builder(getParams().getBuildTarget())
        .addFlavors(getCxxPlatform().getFlavor())
        .addFlavors(
            ImmutableFlavor.of(
                String.format(
                    COMPILE_FLAVOR_PREFIX + "%s%s",
                    getPicType() == PicType.PIC ? "pic-" : "",
                    outputName)))
        .build();
  }

  public BuildTarget createInferCaptureBuildTarget(String name) {
    String outputName = CxxFlavorSanitizer.sanitize(getCompileFlavorSuffix(name));
    return BuildTarget
        .builder(getParams().getBuildTarget())
        .addAllFlavors(getParams().getBuildTarget().getFlavors())
        .addFlavors(getCxxPlatform().getFlavor())
        .addFlavors(ImmutableFlavor.of(String.format("%s-%s",
                    CxxInferEnhancer.InferFlavors.INFER_CAPTURE.get().toString(),
                    outputName)))
        .build();
  }

  public static boolean isCompileFlavoredBuildTarget(BuildTarget target) {
    return target.getFlavors().stream()
        .anyMatch(flavor -> flavor.getName().startsWith(COMPILE_FLAVOR_PREFIX));
  }

  private ImmutableList<String> getPlatformCompileFlags(CxxSource.Type type) {
    ImmutableList.Builder<String> args = ImmutableList.builder();

    // Add in the source-type specific platform compiler flags.
    args.addAll(CxxSourceTypes.getPlatformCompilerFlags(getCxxPlatform(), type));

    // These source types require assembling, so add in platform-specific assembler flags.
    //
    // TODO(andrewjcg): We shouldn't care about lower-level assembling.  If the user has assembler
    // flags in mind which they want to propagate to other languages, they should pass them in via
    // some other means (e.g. `.buckconfig`).
    if (type == CxxSource.Type.C_CPP_OUTPUT ||
        type == CxxSource.Type.OBJC_CPP_OUTPUT ||
        type == CxxSource.Type.CXX_CPP_OUTPUT ||
        type == CxxSource.Type.OBJCXX_CPP_OUTPUT ||
        type == CxxSource.Type.CUDA_CPP_OUTPUT) {
      args.addAll(getCxxPlatform().getAsflags());
    }

    return args.build();
  }

  private ImmutableList<String> getRuleCompileFlags(CxxSource.Type type) {
    return ImmutableList.copyOf(getCompilerFlags().get(type));
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} rule that preprocesses, compiles, and assembles the
   * given {@link CxxSource}.
   */
  @VisibleForTesting
  public CxxPreprocessAndCompile createCompileBuildRule(
      String name,
      CxxSource source,
      boolean afterPreprocessing) {

    Preconditions.checkArgument(CxxSourceTypes.isCompilableType(source.getType()));

    BuildTarget target = createCompileBuildTarget(name);
    DepsBuilder depsBuilder = new DepsBuilder(getPathResolver());

    Compiler compiler =
        CxxSourceTypes.getCompiler(getCxxPlatform(), source.getType())
            .resolve(getResolver());

    // Build up the list of compiler flags.
    CxxToolFlags flags = CxxToolFlags.explicitBuilder()
        // If we're using pic, add in the appropriate flag.
        .addAllPlatformFlags(getPicType().getFlags(compiler))
        // Add in the platform specific compiler flags.
        .addAllPlatformFlags(getPlatformCompileFlags(source.getType()))
        // Add custom compiler flags.
        .addAllRuleFlags(getRuleCompileFlags(source.getType()))
        // Add custom per-file flags.
        .addAllRuleFlags(source.getFlags())
        .build();

    CompilerDelegate compilerDelegate =
        new CompilerDelegate(
            getPathResolver(),
            getCxxPlatform().getCompilerDebugPathSanitizer(),
            compiler,
            flags);
    depsBuilder.add(compilerDelegate);

    depsBuilder.add(source);

    // Build the CxxCompile rule and add it to our sorted set of build rules.
    CxxPreprocessAndCompile result = CxxPreprocessAndCompile.compile(
        getParams().copyWithChanges(
            target,
            Suppliers.ofInstance(depsBuilder.build()),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        getPathResolver(),
        compilerDelegate,
        getCompileOutputPath(target, name),
        source.getPath(),
        source.getType(),
        getCxxPlatform().getCompilerDebugPathSanitizer(),
        getCxxPlatform().getAssemblerDebugPathSanitizer(),
        afterPreprocessing ? Optional.empty() : getSandboxTree());
    getResolver().addToIndex(result);
    return result;
  }

  @VisibleForTesting
  CxxPreprocessAndCompile requireCompileBuildRule(
      String name,
      CxxSource source,
      boolean afterPreprocessing) {

    BuildTarget target = createCompileBuildTarget(name);
    Optional<CxxPreprocessAndCompile> existingRule = getResolver().getRuleOptionalWithType(
        target, CxxPreprocessAndCompile.class);
    if (existingRule.isPresent()) {
      if (!existingRule.get().getInput().equals(source.getPath())) {
        throw new RuntimeException(
            String.format("Hash collision for %s; a build rule would have been ignored.", name));
      }
      return existingRule.get();
    }

    return createCompileBuildRule(name, source, afterPreprocessing);

  }

  private CxxToolFlags computePreprocessorFlags(
      CxxSource.Type type,
      ImmutableList<String> sourceFlags) {
    Compiler compiler = CxxSourceTypes.getCompiler(
        getCxxPlatform(),
        CxxSourceTypes.getPreprocessorOutputType(type)).resolve(getResolver());
    return CxxToolFlags.explicitBuilder()
        .addAllPlatformFlags(getPicType().getFlags(compiler))
        .addAllPlatformFlags(CxxSourceTypes.getPlatformPreprocessFlags(getCxxPlatform(), type))
        .addAllRuleFlags(preprocessorFlags.getUnchecked(type))
        // Add custom per-file flags.
        .addAllRuleFlags(sourceFlags)
        .build();
  }

  private CxxToolFlags computeCompilerFlags(
      CxxSource.Type type,
      ImmutableList<String> sourceFlags) {
    AbstractCxxSource.Type outputType = CxxSourceTypes.getPreprocessorOutputType(type);
    return CxxToolFlags.explicitBuilder()
        // If we're using pic, add in the appropriate flag.
        .addAllPlatformFlags(
            getPicType().getFlags(CxxSourceTypes.getCompiler(getCxxPlatform(), outputType)
                .resolve(getResolver())))
        // Add in the platform specific compiler flags.
        .addAllPlatformFlags(
            getPlatformCompileFlags(outputType))
        .addAllRuleFlags(getRuleCompileFlags(outputType))
        .addAllRuleFlags(sourceFlags)
        .build();
  }

  public CxxInferCapture requireInferCaptureBuildRule(
      String name,
      CxxSource source,
      InferBuckConfig inferConfig) {
    BuildTarget target = createInferCaptureBuildTarget(name);

    Optional<CxxInferCapture> existingRule = getResolver().getRuleOptionalWithType(
        target, CxxInferCapture.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    return createInferCaptureBuildRule(target, name, source, inferConfig);
  }

  public CxxInferCapture createInferCaptureBuildRule(
      BuildTarget target,
      String name,
      CxxSource source,
      InferBuckConfig inferConfig) {
    Preconditions.checkArgument(CxxSourceTypes.isPreprocessableType(source.getType()));

    LOG.verbose("Creating preprocessed InferCapture build rule %s for %s", target, source);

    DepsBuilder depsBuilder = new DepsBuilder(getPathResolver());
    depsBuilder.add(requireAggregatedPreprocessDepsRule());

    PreprocessorDelegateCacheValue preprocessorDelegateValue = preprocessorDelegates.getUnchecked(
        PreprocessorDelegateCacheKey.of(source.getType(), source.getFlags()));
    depsBuilder.add(preprocessorDelegateValue.getPreprocessorDelegate());

    CxxToolFlags ppFlags =
        CxxToolFlags.copyOf(
            CxxSourceTypes.getPlatformPreprocessFlags(getCxxPlatform(), source.getType()),
            preprocessorFlags.getUnchecked(source.getType()));

    CxxToolFlags cFlags = computeCompilerFlags(source.getType(), source.getFlags());

    depsBuilder.add(source);

    CxxInferCapture result = new CxxInferCapture(
        getParams().copyWithChanges(
            target,
            Suppliers.ofInstance(depsBuilder.build()),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        getPathResolver(),
        ppFlags,
        cFlags,
        source.getPath(),
        source.getType(),
        getCompileOutputPath(target, name),
        preprocessorDelegateValue.getPreprocessorDelegate(),
        inferConfig,
        getCxxPlatform().getCompilerDebugPathSanitizer());
    getResolver().addToIndex(result);
    return result;
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} rule that preprocesses, compiles, and assembles the
   * given {@link CxxSource}.
   */
  @VisibleForTesting
  public CxxPreprocessAndCompile createPreprocessAndCompileBuildRule(
      String name,
      CxxSource source) {

    BuildTarget target = createCompileBuildTarget(name);
    LOG.verbose("Creating preprocess and compile %s for %s", target, source);
    Preconditions.checkArgument(CxxSourceTypes.isPreprocessableType(source.getType()));

    DepsBuilder depsBuilder = new DepsBuilder(getPathResolver());
    depsBuilder.add(requireAggregatedPreprocessDepsRule());

    CompilerDelegate compilerDelegate =
        new CompilerDelegate(
            getPathResolver(),
            getCxxPlatform().getCompilerDebugPathSanitizer(),
            CxxSourceTypes.getCompiler(
                getCxxPlatform(),
                CxxSourceTypes.getPreprocessorOutputType(source.getType()))
                .resolve(getResolver()),
            computeCompilerFlags(source.getType(), source.getFlags()));
    depsBuilder.add(compilerDelegate);

    PreprocessorDelegateCacheValue preprocessorDelegateValue = preprocessorDelegates.getUnchecked(
        PreprocessorDelegateCacheKey.of(source.getType(), source.getFlags()));
    PreprocessorDelegate preprocessorDelegate = preprocessorDelegateValue.getPreprocessorDelegate();
    depsBuilder.add(preprocessorDelegate);

    depsBuilder.add(source);

    Optional<PrecompiledHeaderReference> precompiledHeaderReference = Optional.empty();
    if (shouldUsePrecompiledHeaders(getCxxBuckConfig(), preprocessorDelegate)) {
      CxxPrecompiledHeader precompiledHeader =
          requirePrecompiledHeaderBuildRule(preprocessorDelegateValue, source);
      depsBuilder.add(precompiledHeader);
      precompiledHeaderReference =
          Optional.of(PrecompiledHeaderReference.of(precompiledHeader));
    }

    // Build the CxxCompile rule and add it to our sorted set of build rules.
    CxxPreprocessAndCompile result = CxxPreprocessAndCompile.preprocessAndCompile(
        getParams().copyWithChanges(
            target,
            Suppliers.ofInstance(depsBuilder.build()),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        getPathResolver(),
        preprocessorDelegate,
        compilerDelegate,
        getCompileOutputPath(target, name),
        source.getPath(),
        source.getType(),
        precompiledHeaderReference,
        getCxxPlatform().getCompilerDebugPathSanitizer(),
        getCxxPlatform().getAssemblerDebugPathSanitizer(),
        getSandboxTree());
    getResolver().addToIndex(result);
    return result;
  }

  @VisibleForTesting
  CxxPreprocessAndCompile requirePreprocessAndCompileBuildRule(
      String name,
      CxxSource source) {

    BuildTarget target = createCompileBuildTarget(name);
    Optional<CxxPreprocessAndCompile> existingRule = getResolver().getRuleOptionalWithType(
        target, CxxPreprocessAndCompile.class);
    if (existingRule.isPresent()) {
      if (!existingRule.get().getInput().equals(source.getPath())) {
        throw new RuntimeException(
            String.format("Hash collision for %s; a build rule would have been ignored.", name));
      }
      return existingRule.get();
    }

    return createPreprocessAndCompileBuildRule(name, source);
  }

  @VisibleForTesting
  CxxPrecompiledHeader requirePrecompiledHeaderBuildRule(
      PreprocessorDelegateCacheValue preprocessorDelegateCacheValue,
      CxxSource source) {

    // Detect the rule for which we are building this PCH:
    SourcePath sourcePath = Preconditions.checkNotNull(this.getPrefixHeader().orElse(null));
    BuildTarget targetToBuildFor;
    if (sourcePath instanceof BuildTargetSourcePath) {
      // e.g. a library "//foo:foo" has "prefix_header='//bar:header'"; then clone "//bar:header",
      // flavor it (done below), and then that will become one of "//foo:foo"'s dependencies.
      targetToBuildFor = ((BuildTargetSourcePath) sourcePath).getTarget();
    } else {
      // e.g. a library "//baz:baz" has "prefix_header='bazstuff.h'"; then we clone "//baz:baz",
      // flavor it (done below), and then that will become one of "//baz:baz"'s dependencies.
      targetToBuildFor = getParams().getBuildTarget();
    }

    // Clang will only use precompiled headers generated with the same flags and language settings.
    // As such, each prefix header may generate multiple pch files, and need unique build targets
    // to be differentiated in the build graph.
    CxxToolFlags compilerFlags = computeCompilerFlags(source.getType(), source.getFlags());

    // Language needs to be part of the key, PCHs built under a different language are incompatible.
    // (Replace `c++` with `cxx`; avoid default scrubbing which would make it the cryptic `c__`.)
    final String langCode = source.getType().getLanguage().replaceAll("c\\+\\+", "cxx");

    final String pchBaseID =
        "pch-" + langCode + "-" + preprocessorDelegateCacheValue.getBaseHash(compilerFlags);
    final String pchFullID =
        pchBaseID + "-" + preprocessorDelegateCacheValue.getFullHash(compilerFlags);

    BuildTarget target = BuildTarget
        .builder(targetToBuildFor)
        .addFlavors(getCxxPlatform().getFlavor())
        .addFlavors(ImmutableFlavor.of(Flavor.replaceInvalidCharacters(pchFullID)))
        .build();

    Optional<CxxPrecompiledHeader> existingRule =
        getResolver().getRuleOptionalWithType(target, CxxPrecompiledHeader.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    // Give the PCH a filename that looks like a header file with .gch appended to it, GCC-style.
    // GCC accepts an "-include" flag with the .h file as its arg, and auto-appends ".gch" to
    // automagically use the precompiled header in place of the original header.  Of course in
    // our case we'll only have the ".gch" file, which is alright; the ".h" isn't truly needed.
    Path output = BuildTargets.getGenPath(getParams().getProjectFilesystem(), target, "%s.h.gch");

    DepsBuilder depsBuilder = new DepsBuilder(getPathResolver());
    depsBuilder.add(requireAggregatedPreprocessDepsRule());

    PreprocessorDelegate preprocessorDelegate =
        preprocessorDelegateCacheValue.getPreprocessorDelegate();
    depsBuilder.add(preprocessorDelegate);

    CompilerDelegate compilerDelegate =
        new CompilerDelegate(
            getPathResolver(),
            getCxxPlatform().getCompilerDebugPathSanitizer(),
            CxxSourceTypes.getCompiler(
                getCxxPlatform(),
                CxxSourceTypes.getPreprocessorOutputType(source.getType()))
                .resolve(getResolver()),
            computeCompilerFlags(source.getType(), source.getFlags()));
    depsBuilder.add(compilerDelegate);

    SourcePath path = Preconditions.checkNotNull(preprocessorDelegate.getPrefixHeader().get());
    depsBuilder.add(path);

    CxxPrecompiledHeader rule = new CxxPrecompiledHeader(
        getParams().copyWithChanges(
            target,
            Suppliers.ofInstance(depsBuilder.build()),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        getPathResolver(),
        output,
        preprocessorDelegate,
        compilerDelegate,
        compilerFlags,
        path,
        source.getType(),
        getCxxPlatform().getCompilerDebugPathSanitizer(),
        getCxxPlatform().getAssemblerDebugPathSanitizer(),
        getCxxBuckConfig().isPchIlogEnabled());
    getResolver().addToIndex(rule);
    return rule;
  }

  public ImmutableSet<CxxInferCapture> requireInferCaptureBuildRules(
      ImmutableMap<String, CxxSource> sources,
      InferBuckConfig inferConfig,
      CxxInferSourceFilter sourceFilter) {

    ImmutableSet.Builder<CxxInferCapture> objects = ImmutableSet.builder();

    for (Map.Entry<String, CxxSource> entry : sources.entrySet()) {
      String name = entry.getKey();
      CxxSource source = entry.getValue();

      Preconditions.checkState(
          CxxSourceTypes.isPreprocessableType(source.getType()),
          "Only preprocessable source types are currently supported");

      if (sourceFilter.isBlacklisted(source)) {
        continue;
      }

      CxxInferCapture rule = requireInferCaptureBuildRule(
          name,
          source,
          inferConfig);
      objects.add(rule);
    }

    return objects.build();
  }

  @VisibleForTesting
  ImmutableMap<CxxPreprocessAndCompile, SourcePath> requirePreprocessAndCompileRules(
      CxxPreprocessMode strategy,
      ImmutableMap<String, CxxSource> sources) {

    return sources.entrySet().stream()
        .map(entry -> {
          String name = entry.getKey();
          CxxSource source = entry.getValue();

          Preconditions.checkState(
              CxxSourceTypes.isPreprocessableType(source.getType()) ||
                  CxxSourceTypes.isCompilableType(source.getType()));

          source = getSandboxedCxxSource(source);

          switch (strategy) {

            case COMBINED: {
              CxxPreprocessAndCompile rule;

              // If it's a preprocessable source, use a combine preprocess-and-compile build rule.
              // Otherwise, use a regular compile rule.
              if (CxxSourceTypes.isPreprocessableType(source.getType())) {
                rule = requirePreprocessAndCompileBuildRule(name, source);
              } else {
                rule = requireCompileBuildRule(name, source, false);
              }

              return rule;
            }

            case SEPARATE: {

              // If this is a preprocessable source, first create the preprocess build rule and
              // update the source and name to represent its compilable output.
              if (CxxSourceTypes.isPreprocessableType(source.getType())) {
                CxxPreprocessAndCompile rule = requirePreprocessBuildRule(name, source);
                source = CxxSource.copyOf(source)
                    .withType(CxxSourceTypes.getPreprocessorOutputType(source.getType()))
                    .withPath(
                        new BuildTargetSourcePath(rule.getBuildTarget()));
              }

              // Now build the compile build rule.
              CxxPreprocessAndCompile rule = requireCompileBuildRule(name, source, true);
              return rule;
            }

            // $CASES-OMITTED$
            default:
              throw new IllegalStateException();
          }
        })
        .collect(MoreCollectors.toImmutableMap(
            Function.identity(),
            input -> new BuildTargetSourcePath(input.getBuildTarget())));
  }

  private CxxSource getSandboxedCxxSource(CxxSource source) {
    if (getSandboxTree().isPresent()) {
      SymlinkTree sandboxTree = getSandboxTree().get();
      Path sourcePath = Paths.get(
          getPathResolver().getSourcePathName(
              getParams().getBuildTarget(),
              source.getPath()));
      Path sandboxPath = CxxDescriptionEnhancer.getLinkOutputPath(
          sandboxTree.getBuildTarget(),
          getParams().getProjectFilesystem());
      BuildTargetSourcePath path =
          new BuildTargetSourcePath(
              sandboxTree.getBuildTarget(),
              sandboxPath.resolve(sourcePath));
      source = CxxSource.copyOf(source).withPath(path);
    }
    return source;
  }

  private static boolean shouldUsePrecompiledHeaders(
      CxxBuckConfig cxxBuckConfig,
      Optional<SourcePath> prefixHeaderSourcePath,
      Preprocessor preprocessor) {
    return
        cxxBuckConfig.isPCHEnabled() &&
        prefixHeaderSourcePath.isPresent() &&
        preprocessor.supportsPrecompiledHeaders();
  }

  private static boolean shouldUsePrecompiledHeaders(
      CxxBuckConfig cxxBuckConfig,
      PreprocessorDelegate preprocessorDelegate) {
    return shouldUsePrecompiledHeaders(
        cxxBuckConfig,
        preprocessorDelegate.getPrefixHeader(),
        preprocessorDelegate.getPreprocessor());
  }

  public static ImmutableMap<CxxPreprocessAndCompile, SourcePath> requirePreprocessAndCompileRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput,
      ImmutableMultimap<CxxSource.Type, String> compilerFlags,
      Optional<SourcePath> prefixHeader,
      CxxPreprocessMode strategy,
      ImmutableMap<String, CxxSource> sources,
      PicType pic,
      Optional<SymlinkTree> sandboxTree) {
    CxxSourceRuleFactory factory = CxxSourceRuleFactory.of(
        params,
        resolver,
        pathResolver,
        cxxBuckConfig,
        cxxPlatform,
        cxxPreprocessorInput,
        compilerFlags,
        prefixHeader,
        pic,
        sandboxTree);
    return factory.requirePreprocessAndCompileRules(strategy, sources);
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

  static class PreprocessorDelegateCacheValue {
    private final PreprocessorDelegate preprocessorDelegate;
    private final LoadingCache<CxxToolFlags, HashStrings> commandHashCache;

    class HashStrings {
      public final String baseHash;
      public final String fullHash;

      public HashStrings(CxxToolFlags compilerFlags) {
        ImmutableList<String> allFlags = preprocessorDelegate.getCommand(
            compilerFlags,
            /* no pch object yet */ Optional.empty());
        ImmutableList.Builder<String> iDirsBuilder = ImmutableList.<String>builder();
        ImmutableList.Builder<String> iSystemDirsBuilder = ImmutableList.<String>builder();
        ImmutableList.Builder<String> nonIncludeFlagsBuilder = ImmutableList.<String>builder();
        CxxPrecompiledHeader.separateIncludePathArgs(
            allFlags,
            iDirsBuilder,
            iSystemDirsBuilder,
            nonIncludeFlagsBuilder);

        ImmutableList.Builder<String> flagBuilder = ImmutableList.<String>builder();

        // Compute two different hashes; one for non-include paths, just for PCH compatibility
        // with respect to defines, f-flags, m-flags / other things that must agree in PCH + build.
        // It's possible that targets -- in fact hopefully many targets -- share the same base hash
        // so that it's possible to reuse PCHs with that base hash, even if include path flags
        // differ in (most likely) non-incompatible ways.
        flagBuilder.addAll(nonIncludeFlagsBuilder.build());
        this.baseHash = preprocessorDelegate.hashCommand(flagBuilder.build()).substring(0, 10);

        // The full hash is a globally-unique identifier, using the above mentioned flags followed
        // by other include path dirs.
        flagBuilder.addAll(iDirsBuilder.build());
        flagBuilder.addAll(iSystemDirsBuilder.build());
        this.fullHash = preprocessorDelegate.hashCommand(flagBuilder.build()).substring(0, 10);
      }
    }

    PreprocessorDelegateCacheValue(PreprocessorDelegate preprocessorDelegate) {
      this.preprocessorDelegate = preprocessorDelegate;
      this.commandHashCache = CacheBuilder.newBuilder()
          .build(new CacheLoader<CxxToolFlags, HashStrings>() {
            @Override
            public HashStrings load(CxxToolFlags key) {
              // Note: this hash call is mainly for the benefit of precompiled headers, to produce
              // the PCH's hash of build flags.  (Since there's no PCH yet, the PCH argument is
              // passed as empty here.)
              return new HashStrings(key);
            }
          });
    }

    PreprocessorDelegate getPreprocessorDelegate() {
      return preprocessorDelegate;
    }

    String getBaseHash(CxxToolFlags flags) {
      return this.commandHashCache.getUnchecked(flags).baseHash;
    }

    String getFullHash(CxxToolFlags flags) {
      return this.commandHashCache.getUnchecked(flags).fullHash;
    }
  }

  private class PreprocessorDelegateCacheLoader
      extends CacheLoader<PreprocessorDelegateCacheKey, PreprocessorDelegateCacheValue> {

    @Override
    public PreprocessorDelegateCacheValue load(@Nonnull PreprocessorDelegateCacheKey key)
        throws Exception {
      Preprocessor preprocessor =
          CxxSourceTypes.getPreprocessor(getCxxPlatform(), key.getSourceType())
              .resolve(getResolver());
      PreprocessorDelegate delegate = new PreprocessorDelegate(
          getPathResolver(),
          getCxxPlatform().getCompilerDebugPathSanitizer(),
        getCxxBuckConfig().getHeaderVerification(),
          getParams().getProjectFilesystem().getRootPath(),
          preprocessor,
          PreprocessorFlags.of(
              getPrefixHeader(),
              computePreprocessorFlags(key.getSourceType(), key.getSourceFlags()),
              getIncludes(),
              getFrameworks(),
              getSystemIncludeRoots()),
          CxxDescriptionEnhancer.frameworkPathToSearchPath(getCxxPlatform(), getPathResolver()),
          getIncludes(),
          getSandboxTree());
      return new PreprocessorDelegateCacheValue(delegate);
    }
  }

}
