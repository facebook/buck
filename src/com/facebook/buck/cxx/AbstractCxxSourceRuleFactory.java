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
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.immutables.value.Value;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCxxSourceRuleFactory {

  private static final Logger LOG = Logger.get(AbstractCxxSourceRuleFactory.class);
  private static final String COMPILE_FLAVOR_PREFIX = "compile-";
  private static final String PREPROCESS_FLAVOR_PREFIX = "preprocess-";

  @Value.Parameter
  public abstract BuildRuleParams getParams();
  @Value.Parameter
  public abstract BuildRuleResolver getResolver();
  @Value.Parameter
  public abstract SourcePathResolver getPathResolver();
  @Value.Parameter
  public abstract CxxPlatform getCxxPlatform();
  @Value.Parameter
  public abstract ImmutableList<CxxPreprocessorInput> getCxxPreprocessorInput();
  @Value.Parameter
  public abstract ImmutableList<String> getCompilerFlags();
  @Value.Parameter
  public abstract Optional<SourcePath> getPrefixHeader();
  @Value.Parameter
  public abstract PicType getPicType();

  @Value.Lazy
  protected ImmutableList<BuildRule> getPreprocessDeps() {
    ImmutableList.Builder<BuildRule> builder = ImmutableList.builder();
    for (CxxPreprocessorInput input : getCxxPreprocessorInput()) {
      // Depend on the rules that generate the sources and headers we're compiling.
      builder.addAll(
          getPathResolver().filterBuildRuleInputs(
              ImmutableList.<SourcePath>builder()
                  .addAll(input.getIncludes().getNameToPathMap().values())
                  .build()));
      // Also add in extra deps from the preprocessor input, such as the symlink tree rules.
      builder.addAll(
          BuildRules.toBuildRulesFor(
              getParams().getBuildTarget(),
              getResolver(),
              input.getRules()));
    }
    return builder.build();
  }

  @Value.Lazy
  protected ImmutableSet<Path> getIncludeRoots() {
    return FluentIterable.from(getCxxPreprocessorInput())
        .transformAndConcat(CxxPreprocessorInput.GET_INCLUDE_ROOTS)
        .toSet();
  }

  @Value.Lazy
  protected ImmutableSet<Path> getSystemIncludeRoots() {
    return FluentIterable.from(getCxxPreprocessorInput())
        .transformAndConcat(CxxPreprocessorInput.GET_SYSTEM_INCLUDE_ROOTS)
        .toSet();
  }

  @Value.Lazy
  protected ImmutableSet<Path> getHeaderMaps() {
    return FluentIterable.from(getCxxPreprocessorInput())
        .transformAndConcat(CxxPreprocessorInput.GET_HEADER_MAPS)
        .toSet();
  }

  @Value.Lazy
  protected ImmutableSet<FrameworkPath> getFrameworks() {
    return FluentIterable.from(getCxxPreprocessorInput())
        .transformAndConcat(CxxPreprocessorInput.GET_FRAMEWORKS)
        .toSet();
  }

  @Value.Lazy
  protected ImmutableList<CxxHeaders> getIncludes() {
    return FluentIterable.from(getCxxPreprocessorInput())
        .transform(CxxPreprocessorInput.GET_INCLUDES)
        .toList();
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

  private final LoadingCache<PreprocessAndCompilePreprocessorDelegateKey, PreprocessorDelegate>
      preprocessorDelegates = CacheBuilder.newBuilder()
      .build(new PreprocessorDelegateCacheLoader());

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
    String outputName = Flavor.replaceInvalidCharacters(getPreprocessOutputName(type, name));
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
    return BuildTargets.getGenPath(target, "%s").resolve(getPreprocessOutputName(type, name));
  }

  @VisibleForTesting
  public CxxPreprocessAndCompile createPreprocessBuildRule(
      String name,
      CxxSource source) {

    Preconditions.checkArgument(CxxSourceTypes.isPreprocessableType(source.getType()));

    BuildTarget target = createPreprocessBuildTarget(name, source.getType());
    PreprocessorDelegate preprocessorDelegate = preprocessorDelegates.getUnchecked(
        PreprocessAndCompilePreprocessorDelegateKey.of(source.getType(), source.getFlags()));

    // Build the CxxCompile rule and add it to our sorted set of build rules.
    CxxPreprocessAndCompile result =
        CxxPreprocessAndCompile.preprocess(
            getParams().copyWithChanges(
                target,
                new DepsBuilder()
                    .addPreprocessDeps()
                    .add(preprocessorDelegate.getPreprocessor())
                    .add(source),
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
            getPathResolver(),
            preprocessorDelegate,
            new CompilerDelegate(
                getPathResolver(),
                getCxxPlatform().getDebugPathSanitizer(),
                getCompiler(source.getType()),
                computeCompilerFlags(source.getType(), source.getFlags())),
            getPreprocessOutputPath(target, source.getType(), name),
            source.getPath(),
            source.getType(),
            getCxxPlatform().getDebugPathSanitizer());
    getResolver().addToIndex(result);
    return result;
  }

  @VisibleForTesting
  CxxPreprocessAndCompile requirePreprocessBuildRule(
      String name,
      CxxSource source) {

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
    return getOutputName(name) + ".o";
  }

  /**
   * @return the output path for an object file compiled from the source with the given name.
   */
  @VisibleForTesting
  Path getCompileOutputPath(BuildTarget target, String name) {
    return BuildTargets.getGenPath(target, "%s").resolve(getCompileOutputName(name));
  }

  /**
   * @return a build target for a {@link CxxPreprocessAndCompile} rule for the source with the
   * given name.
   */
  @VisibleForTesting
  public BuildTarget createCompileBuildTarget(String name) {
    String outputName = Flavor.replaceInvalidCharacters(getCompileOutputName(name));
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
    String outputName = Flavor.replaceInvalidCharacters(getCompileOutputName(name));
    return BuildTarget
        .builder(getParams().getBuildTarget())
        .addAllFlavors(getParams().getBuildTarget().getFlavors())
        .addFlavors(getCxxPlatform().getFlavor())
        .addFlavors(ImmutableFlavor.of(String.format("infer-capture-%s", outputName)))
        .build();
  }

  public static boolean isCompileFlavoredBuildTarget(BuildTarget target) {
    Set<Flavor> flavors = target.getFlavors();
    for (Flavor flavor : flavors) {
      if (flavor.getName().startsWith(COMPILE_FLAVOR_PREFIX)) {
        return true;
      }
    }
    return false;
  }

  // Pick the compiler to use.  Basically, if we're dealing with C++ sources, use the C++
  // compiler, and the C compiler for everything.
  private Compiler getCompiler(CxxSource.Type type) {
    return CxxSourceTypes.needsCxxCompiler(type) ?
        getCxxPlatform().getCxx() :
        getCxxPlatform().getCc();
  }

  private ImmutableList<String> getPlatformCompileFlags(CxxSource.Type type) {
    ImmutableList.Builder<String> args = ImmutableList.builder();

    // If we're dealing with a C source that can be compiled, add the platform C compiler flags.
    if (type == CxxSource.Type.C_CPP_OUTPUT ||
        type == CxxSource.Type.OBJC_CPP_OUTPUT) {
      args.addAll(getCxxPlatform().getCflags());
    }

    // If we're dealing with a C++ source that can be compiled, add the platform C++ compiler
    // flags.
    if (type == CxxSource.Type.CXX_CPP_OUTPUT ||
        type == CxxSource.Type.OBJCXX_CPP_OUTPUT) {
      args.addAll(getCxxPlatform().getCxxflags());
    }

    // All source types require assembling, so add in platform-specific assembler flags.
    args.addAll(getCxxPlatform().getAsflags());

    return args.build();
  }

  private ImmutableList<String> getRuleCompileFlags(CxxSource.Type type) {
    ImmutableList.Builder<String> args = ImmutableList.builder();

    // Add in explicit additional compiler flags, if we're compiling.
    if (type == CxxSource.Type.C_CPP_OUTPUT ||
        type == CxxSource.Type.OBJC_CPP_OUTPUT ||
        type == CxxSource.Type.CXX_CPP_OUTPUT ||
        type == CxxSource.Type.OBJCXX_CPP_OUTPUT) {
      args.addAll(getCompilerFlags());
    }

    return args.build();
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} rule that preprocesses, compiles, and assembles the
   * given {@link CxxSource}.
   */
  @VisibleForTesting
  public CxxPreprocessAndCompile createCompileBuildRule(
      String name,
      CxxSource source) {

    Preconditions.checkArgument(CxxSourceTypes.isCompilableType(source.getType()));

    BuildTarget target = createCompileBuildTarget(name);
    Compiler compiler = getCompiler(source.getType());

    // Build up the list of compiler flags.
    CxxToolFlags flags = CxxToolFlags.explicitBuilder()
        // If we're using pic, add in the appropriate flag.
        .addAllPlatformFlags(getPicType().getFlags())
        // Add in the platform specific compiler flags.
        .addAllPlatformFlags(getPlatformCompileFlags(source.getType()))
        // Add custom compiler flags.
        .addAllRuleFlags(getRuleCompileFlags(source.getType()))
        // Add custom per-file flags.
        .addAllRuleFlags(source.getFlags())
        .build();

    // Build the CxxCompile rule and add it to our sorted set of build rules.
    CxxPreprocessAndCompile result = CxxPreprocessAndCompile.compile(
        getParams().copyWithChanges(
            target,
            new DepsBuilder().add(compiler).add(source),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        getPathResolver(),
        new CompilerDelegate(
            getPathResolver(),
            getCxxPlatform().getDebugPathSanitizer(),
            compiler,
            flags),
        getCompileOutputPath(target, name),
        source.getPath(),
        source.getType(),
        getCxxPlatform().getDebugPathSanitizer());
    getResolver().addToIndex(result);
    return result;
  }

  @VisibleForTesting
  CxxPreprocessAndCompile requireCompileBuildRule(String name, CxxSource source) {

    BuildTarget target = createCompileBuildTarget(name);
    Optional<CxxPreprocessAndCompile> existingRule = getResolver().getRuleOptionalWithType(
        target, CxxPreprocessAndCompile.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    return createCompileBuildRule(name, source);
  }

  private CxxToolFlags computePreprocessorFlags(
      CxxSource.Type type,
      ImmutableList<String> sourceFlags) {
    return CxxToolFlags.explicitBuilder()
        .addAllPlatformFlags(getPicType().getFlags())
        .addAllPlatformFlags(CxxSourceTypes.getPlatformPreprocessFlags(getCxxPlatform(), type))
        .addAllRuleFlags(preprocessorFlags.getUnchecked(type))
        // Add custom compiler flags.
        .addAllRuleFlags(getRuleCompileFlags(CxxSourceTypes.getPreprocessorOutputType(type)))
        // Add custom per-file flags.
        .addAllRuleFlags(sourceFlags)
        .build();
  }

  private CxxToolFlags computeCompilerFlags(
      CxxSource.Type type,
      ImmutableList<String> sourceFlags) {
    return CxxToolFlags.explicitBuilder()
        // If we're using pic, add in the appropriate flag.
        .addAllPlatformFlags(getPicType().getFlags())
        // Add in the platform specific compiler flags.
        .addAllPlatformFlags(
            getPlatformCompileFlags(CxxSourceTypes.getPreprocessorOutputType(type)))
        .addAllRuleFlags(getRuleCompileFlags(CxxSourceTypes.getPreprocessorOutputType(type)))
        .addAllRuleFlags(sourceFlags)
        .build();
  }

  public CxxInferCapture requireInferCaptureBuildRule(
      String name,
      CxxSource source,
      CxxInferTools inferTools) {
    BuildTarget target = createInferCaptureBuildTarget(name);

    Optional<CxxInferCapture> existingRule = getResolver().getRuleOptionalWithType(
        target, CxxInferCapture.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    return createInferCaptureBuildRule(target, name, source, inferTools);
  }

  public CxxInferCapture createInferCaptureBuildRule(
      BuildTarget target,
      String name,
      CxxSource source,
      CxxInferTools inferTools) {
    Preconditions.checkArgument(CxxSourceTypes.isPreprocessableType(source.getType()));

    LOG.verbose("Creating preprocessed InferCapture build rule %s for %s", target, source);

    CxxInferCapture result = new CxxInferCapture(
        getParams().copyWithChanges(
            target,
            new DepsBuilder().addPreprocessDeps().add(source),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        getPathResolver(),
        CxxToolFlags.copyOf(
            CxxSourceTypes.getPlatformPreprocessFlags(getCxxPlatform(), source.getType()),
            preprocessorFlags.getUnchecked(source.getType())),
        computeCompilerFlags(source.getType(), source.getFlags()),
        source.getPath(),
        source.getType(),
        getCompileOutputPath(target, name),
        getIncludeRoots(),
        getSystemIncludeRoots(),
        getHeaderMaps(),
        getFrameworks(),
        CxxDescriptionEnhancer.frameworkPathToSearchPath(getCxxPlatform(), getPathResolver()),
        getPrefixHeader(),
        inferTools,
        getCxxPlatform().getDebugPathSanitizer());
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
      CxxSource source,
      CxxPreprocessMode strategy) {

    Preconditions.checkArgument(CxxSourceTypes.isPreprocessableType(source.getType()));

    BuildTarget target = createCompileBuildTarget(name);
    Compiler compiler = getCompiler(source.getType());

    LOG.verbose("Creating preprocess and compile %s for %s", target, source);

    PreprocessorDelegate preprocessorDelegate = preprocessorDelegates.getUnchecked(
        PreprocessAndCompilePreprocessorDelegateKey.of(source.getType(), source.getFlags()));
    // Build the CxxCompile rule and add it to our sorted set of build rules.
    CxxPreprocessAndCompile result = CxxPreprocessAndCompile.preprocessAndCompile(
        getParams().copyWithChanges(
            target,
            // compiler handles both preprocessing and compiling
            new DepsBuilder().addPreprocessDeps().add(compiler).add(source),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        getPathResolver(),
        preprocessorDelegate,
        new CompilerDelegate(
            getPathResolver(),
            getCxxPlatform().getDebugPathSanitizer(),
            compiler,
            computeCompilerFlags(source.getType(), source.getFlags())),
        getCompileOutputPath(target, name),
        source.getPath(),
        source.getType(),
        getCxxPlatform().getDebugPathSanitizer(),
        strategy);
    getResolver().addToIndex(result);
    return result;
  }

  @VisibleForTesting
  CxxPreprocessAndCompile requirePreprocessAndCompileBuildRule(
      String name,
      CxxSource source,
      CxxPreprocessMode strategy) {

    BuildTarget target = createCompileBuildTarget(name);
    Optional<CxxPreprocessAndCompile> existingRule = getResolver().getRuleOptionalWithType(
        target, CxxPreprocessAndCompile.class);
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    return createPreprocessAndCompileBuildRule(name, source, strategy);
  }


  public ImmutableSet<CxxInferCapture> createInferCaptureBuildRules(
      ImmutableMap<String, CxxSource> sources,
      CxxInferTools inferTools,
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
          inferTools);
      objects.add(rule);
    }

    return objects.build();
  }

  protected ImmutableMap<CxxPreprocessAndCompile, SourcePath> requirePreprocessAndCompileRules(
      CxxPreprocessMode strategy,
      ImmutableMap<String, CxxSource> sources) {

    ImmutableList.Builder<CxxPreprocessAndCompile> objects = ImmutableList.builder();

    for (Map.Entry<String, CxxSource> entry : sources.entrySet()) {
      String name = entry.getKey();
      CxxSource source = entry.getValue();

      Preconditions.checkState(
          CxxSourceTypes.isPreprocessableType(source.getType()) ||
              CxxSourceTypes.isCompilableType(source.getType()));

      switch (strategy) {

        case PIPED:
        case COMBINED: {
          CxxPreprocessAndCompile rule;

          // If it's a preprocessable source, use a combine preprocess-and-compile build rule.
          // Otherwise, use a regular compile rule.
          if (CxxSourceTypes.isPreprocessableType(source.getType())) {
            rule = requirePreprocessAndCompileBuildRule(name, source, strategy);
          } else {
            rule = requireCompileBuildRule(name, source);
          }

          objects.add(rule);
          break;
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
          CxxPreprocessAndCompile rule = requireCompileBuildRule(name, source);
          objects.add(rule);

          break;
        }

        // $CASES-OMITTED$
        default:
          throw new IllegalStateException();
      }
    }

    return FluentIterable
        .from(objects.build())
        .toMap(new Function<CxxPreprocessAndCompile, SourcePath>() {
          @Override
          public SourcePath apply(CxxPreprocessAndCompile input) {
            return new BuildTargetSourcePath(input.getBuildTarget());
          }
        });
  }

  public static ImmutableMap<CxxPreprocessAndCompile, SourcePath> requirePreprocessAndCompileRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput,
      ImmutableList<String> compilerFlags,
      Optional<SourcePath> prefixHeader,
      CxxPreprocessMode strategy,
      ImmutableMap<String, CxxSource> sources,
      PicType pic) {
    CxxSourceRuleFactory factory = CxxSourceRuleFactory.of(
        params,
        resolver,
        pathResolver,
        cxxPlatform,
        cxxPreprocessorInput,
        compilerFlags,
        prefixHeader,
        pic);
    return factory.requirePreprocessAndCompileRules(strategy, sources);
  }

  public enum PicType {

    // Generate position-independent code (e.g. for use in shared libraries).
    PIC("-fPIC"),

    // Generate position-dependent code.
    PDC;

    private final ImmutableList<String> flags;

    PicType(String... flags) {
      this.flags = ImmutableList.copyOf(flags);
    }

    public ImmutableList<String> getFlags() {
      return flags;
    }

  }

  @Value.Immutable
  @BuckStyleTuple
  interface AbstractPreprocessAndCompilePreprocessorDelegateKey {
    CxxSource.Type getSourceType();

    ImmutableList<String> getSourceFlags();
  }

  private class PreprocessorDelegateCacheLoader
      extends CacheLoader<PreprocessAndCompilePreprocessorDelegateKey, PreprocessorDelegate> {

    @Override
    public PreprocessorDelegate load(@Nonnull PreprocessAndCompilePreprocessorDelegateKey key)
        throws Exception {
      return new PreprocessorDelegate(
          getPathResolver(),
          getCxxPlatform().getDebugPathSanitizer(),
          getParams().getProjectFilesystem().getRootPath(),
          CxxSourceTypes.getPreprocessor(getCxxPlatform(), key.getSourceType()),
          computePreprocessorFlags(key.getSourceType(), key.getSourceFlags()),
          getIncludeRoots(),
          getSystemIncludeRoots(),
          getHeaderMaps(),
          getFrameworks(),
          CxxDescriptionEnhancer.frameworkPathToSearchPath(getCxxPlatform(), getPathResolver()),
          getPrefixHeader(),
          getIncludes());
    }

  }

  /**
   * Supplier suitable for generating the dependency list of a build rule.
   */
  private class DepsBuilder implements Supplier<ImmutableSortedSet<BuildRule>> {
    private final ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();

    @Override
    public ImmutableSortedSet<BuildRule> get() {
      return builder.build();
    }

    public DepsBuilder add(Tool tool) {
      builder.addAll(tool.getDeps(getPathResolver()));
      return this;
    }

    public DepsBuilder add(CxxSource source) {
      builder.addAll(getPathResolver().filterBuildRuleInputs(source.getPath()));
      return this;
    }

    public DepsBuilder addPreprocessDeps() {
      builder.addAll(getPreprocessDeps());
      return this;
    }
  }


}
