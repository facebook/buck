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

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.cxx.toolchain.linker.impl.Linkers;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.AddsToRuleKeyFunction;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.ProxyArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.args.ToolArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.macros.AbstractMacroExpanderWithoutPrecomputedWork;
import com.facebook.buck.rules.macros.ArgExpander;
import com.facebook.buck.rules.macros.CcFlagsMacro;
import com.facebook.buck.rules.macros.CcMacro;
import com.facebook.buck.rules.macros.CppFlagsMacro;
import com.facebook.buck.rules.macros.CxxFlagsMacro;
import com.facebook.buck.rules.macros.CxxGenruleFilterAndTargetsMacro;
import com.facebook.buck.rules.macros.CxxMacro;
import com.facebook.buck.rules.macros.CxxppFlagsMacro;
import com.facebook.buck.rules.macros.ExecutableMacro;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.ExecutableTargetMacro;
import com.facebook.buck.rules.macros.LdMacro;
import com.facebook.buck.rules.macros.LdflagsSharedFilterMacro;
import com.facebook.buck.rules.macros.LdflagsSharedMacro;
import com.facebook.buck.rules.macros.LdflagsStaticFilterMacro;
import com.facebook.buck.rules.macros.LdflagsStaticMacro;
import com.facebook.buck.rules.macros.LdflagsStaticPicFilterMacro;
import com.facebook.buck.rules.macros.LdflagsStaticPicMacro;
import com.facebook.buck.rules.macros.LocationPlatformMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.PlatformNameMacro;
import com.facebook.buck.rules.macros.SimpleMacroExpander;
import com.facebook.buck.rules.macros.StringExpander;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CxxGenruleDescription extends AbstractGenruleDescription<CxxGenruleDescriptionArg>
    implements Flavored,
        VersionPropagator<CxxGenruleDescriptionArg>,
        ImplicitDepsInferringDescription<CxxGenruleDescriptionArg> {

  private final ImmutableSet<Flavor> declaredPlatforms;

  public CxxGenruleDescription(
      ToolchainProvider toolchainProvider,
      BuckConfig buckConfig,
      CxxBuckConfig cxxBuckConfig,
      SandboxExecutionStrategy sandboxExecutionStrategy) {
    super(toolchainProvider, buckConfig, sandboxExecutionStrategy, false);
    this.declaredPlatforms = cxxBuckConfig.getDeclaredPlatforms();
  }

  public static boolean wrapsCxxGenrule(SourcePathRuleFinder ruleFinder, SourcePath path) {
    Optional<BuildRule> rule = ruleFinder.getRule(path);
    return rule.map(CxxGenrule.class::isInstance).orElse(false);
  }

  /**
   * @return a new {@link BuildTargetSourcePath} for an existing {@link BuildTargetSourcePath} which
   *     refers to a {@link CxxGenrule} with the given {@code platform} flavor applied.
   */
  public static SourcePath fixupSourcePath(
      ActionGraphBuilder graphBuilder, CxxPlatform platform, SourcePath path) {
    Optional<BuildRule> rule = graphBuilder.getRule(path);
    if (rule.isPresent() && rule.get() instanceof CxxGenrule) {
      Genrule platformRule =
          (Genrule)
              graphBuilder.requireRule(
                  rule.get().getBuildTarget().withAppendedFlavors(platform.getFlavor()));
      path = platformRule.getSourcePathToOutput();
    }
    return path;
  }

  public static ImmutableList<SourcePath> fixupSourcePaths(
      ActionGraphBuilder graphBuilder, CxxPlatform cxxPlatform, ImmutableList<SourcePath> paths) {
    ImmutableList.Builder<SourcePath> fixed = ImmutableList.builder();
    for (SourcePath path : paths) {
      fixed.add(fixupSourcePath(graphBuilder, cxxPlatform, path));
    }
    return fixed.build();
  }

  public static ImmutableSortedSet<SourcePath> fixupSourcePaths(
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<SourcePath> paths) {
    ImmutableSortedSet.Builder<SourcePath> fixed =
        new ImmutableSortedSet.Builder<>(Objects.requireNonNull(paths.comparator()));
    for (SourcePath path : paths) {
      fixed.add(fixupSourcePath(graphBuilder, cxxPlatform, path));
    }
    return fixed.build();
  }

  public static <T> ImmutableMap<T, SourcePath> fixupSourcePaths(
      ActionGraphBuilder graphBuilder, CxxPlatform cxxPlatform, ImmutableMap<T, SourcePath> paths) {
    ImmutableMap.Builder<T, SourcePath> fixed = ImmutableMap.builder();
    for (Map.Entry<T, SourcePath> ent : paths.entrySet()) {
      fixed.put(ent.getKey(), fixupSourcePath(graphBuilder, cxxPlatform, ent.getValue()));
    }
    return fixed.build();
  }

  private static String shquoteJoin(Iterable<String> args) {
    return Streams.stream(args).map(Escaper.SHELL_ESCAPER).collect(Collectors.joining(" "));
  }

  @Override
  public Class<CxxGenruleDescriptionArg> getConstructorArgType() {
    return CxxGenruleDescriptionArg.class;
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return getCxxPlatforms(toolchainTargetConfiguration).containsAnyOf(flavors)
        || !Sets.intersection(declaredPlatforms, flavors).isEmpty();
  }

  @Override
  protected Optional<ImmutableList<MacroExpander<? extends Macro, ?>>> getMacroHandler(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      TargetGraph targetGraph,
      CxxGenruleDescriptionArg args) {

    Optional<UnresolvedCxxPlatform> maybeCxxPlatform =
        getCxxPlatforms(buildTarget.getTargetConfiguration()).getValue(buildTarget);
    if (!maybeCxxPlatform.isPresent()) {
      return Optional.empty();
    }

    CxxPlatform cxxPlatform =
        maybeCxxPlatform.get().resolve(resolver, buildTarget.getTargetConfiguration());
    ImmutableList.Builder<MacroExpander<? extends Macro, ?>> expanders = ImmutableList.builder();

    expanders.add(new ExecutableMacroExpander<>(ExecutableMacro.class));
    expanders.add(new ExecutableMacroExpander<>(ExecutableTargetMacro.class));
    expanders.add(new CxxLocationMacroExpander(cxxPlatform));
    expanders.add(new LocationPlatformMacroExpander(cxxPlatform));
    expanders.add(
        new StringExpander<>(
            PlatformNameMacro.class, StringArg.of(cxxPlatform.getFlavor().toString())));
    expanders.add(
        new ToolExpander<>(
            CcMacro.class,
            cxxPlatform.getCc().resolve(resolver, buildTarget.getTargetConfiguration())));
    expanders.add(
        new ToolExpander<>(
            CxxMacro.class,
            cxxPlatform.getCxx().resolve(resolver, buildTarget.getTargetConfiguration())));

    ImmutableList<Arg> asflags = cxxPlatform.getAsflags();
    ImmutableList<Arg> cflags = cxxPlatform.getCflags();
    ImmutableList<Arg> cxxflags = cxxPlatform.getCxxflags();
    expanders.add(
        new ArgExpander<>(
            CcFlagsMacro.class,
            new ShQuoteJoinArg(
                Stream.concat(cflags.stream(), asflags.stream())
                    .collect(ImmutableList.toImmutableList()))));
    expanders.add(
        new ArgExpander<>(
            CxxFlagsMacro.class,
            new ShQuoteJoinArg(
                Stream.concat(cxxflags.stream(), asflags.stream())
                    .collect(ImmutableList.toImmutableList()))));

    expanders.add(
        new CxxPreprocessorFlagsExpander<>(CppFlagsMacro.class, cxxPlatform, CxxSource.Type.C));
    expanders.add(
        new CxxPreprocessorFlagsExpander<>(CxxppFlagsMacro.class, cxxPlatform, CxxSource.Type.CXX));
    expanders.add(
        new ToolExpander<>(
            LdMacro.class,
            cxxPlatform.getLd().resolve(resolver, buildTarget.getTargetConfiguration())));

    for (Map.Entry<Class<? extends CxxGenruleFilterAndTargetsMacro>, LinkableDepType> ent :
        ImmutableMap.<Class<? extends CxxGenruleFilterAndTargetsMacro>, LinkableDepType>builder()
            .put(LdflagsSharedMacro.class, LinkableDepType.SHARED)
            .put(LdflagsSharedFilterMacro.class, LinkableDepType.SHARED)
            .put(LdflagsStaticMacro.class, LinkableDepType.STATIC)
            .put(LdflagsStaticFilterMacro.class, LinkableDepType.STATIC)
            .put(LdflagsStaticPicMacro.class, LinkableDepType.STATIC_PIC)
            .put(LdflagsStaticPicFilterMacro.class, LinkableDepType.STATIC_PIC)
            .build()
            .entrySet()) {
      expanders.add(
          new CxxLinkerFlagsExpander<>(
              ent.getKey(), buildTarget, filesystem, cxxPlatform, ent.getValue(), args.getOut()));
    }

    return Optional.of(expanders.build());
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      CxxGenruleDescriptionArg args) {
    Optional<UnresolvedCxxPlatform> cxxPlatform =
        getCxxPlatforms(buildTarget.getTargetConfiguration()).getValue(buildTarget);
    if (cxxPlatform.isPresent()) {
      return super.createBuildRule(
          context, buildTarget.withAppendedFlavors(cxxPlatform.get().getFlavor()), params, args);
    }
    return new CxxGenrule(buildTarget, context.getProjectFilesystem(), params, args.getOut());
  }

  @Override
  protected BuildRule createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      // TODO(swgillespie) T55035474 tracks removal of this parameter
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CxxGenruleDescriptionArg args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe) {
    return createBuildRule(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        args,
        cmd,
        bash,
        cmdExe,
        Optional.of(args.getOut()),
        Optional.empty()); // multiple outputs not supported yet for CxxGenRule
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      CxxGenruleDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Add in all parse time deps from the C/C++ platforms.
    for (UnresolvedCxxPlatform cxxPlatform :
        getCxxPlatforms(buildTarget.getTargetConfiguration()).getValues()) {
      targetGraphOnlyDepsBuilder.addAll(
          cxxPlatform.getParseTimeDeps(buildTarget.getTargetConfiguration()));
    }
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  private FlavorDomain<UnresolvedCxxPlatform> getCxxPlatforms(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider
        .getByName(
            CxxPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            CxxPlatformsProvider.class)
        .getUnresolvedCxxPlatforms();
  }

  @RuleArg
  interface AbstractCxxGenruleDescriptionArg extends AbstractGenruleDescription.CommonArg {
    String getOut();
  }

  /** A macro expander that expands to a specific {@link Tool}. */
  private static class ToolExpander<M extends Macro> extends SimpleMacroExpander<M> {

    private final Class<M> clazz;
    private final Tool tool;

    ToolExpander(Class<M> clazz, Tool tool) {
      this.clazz = clazz;
      this.tool = tool;
    }

    @Override
    public Class<M> getInputClass() {
      return clazz;
    }

    @Override
    public Arg expandFrom(BuildTarget target, BuildRuleResolver resolver) {
      return ToolArg.of(tool);
    }
  }

  private abstract static class FilterAndTargetsExpander<M extends CxxGenruleFilterAndTargetsMacro>
      extends AbstractMacroExpanderWithoutPrecomputedWork<M> {

    protected ImmutableList<BuildRule> resolve(
        BuildRuleResolver resolver, ImmutableList<BuildTarget> input) throws MacroException {
      ImmutableList.Builder<BuildRule> rules = ImmutableList.builder();
      for (BuildTarget ruleTarget : input) {
        Optional<BuildRule> rule = resolver.getRuleOptional(ruleTarget);
        if (!rule.isPresent()) {
          throw new MacroException(String.format("no rule %s", ruleTarget));
        }
        rules.add(rule.get());
      }
      return rules.build();
    }

    protected abstract Arg expand(
        ActionGraphBuilder graphBuilder,
        TargetConfiguration targetConfiguration,
        ImmutableList<BuildRule> rules,
        Optional<Pattern> filter);

    @Override
    public Arg expandFrom(BuildTarget target, ActionGraphBuilder graphBuilder, M input)
        throws MacroException {
      return expand(
          graphBuilder,
          target.getTargetConfiguration(),
          resolve(graphBuilder, input.getTargets()),
          input.getFilter());
    }
  }

  /**
   * A build target expander that replaces lists of build target with their transitive preprocessor
   * input.
   */
  private static class CxxPreprocessorFlagsExpander<M extends CxxGenruleFilterAndTargetsMacro>
      extends FilterAndTargetsExpander<M> {
    private final Class<M> clazz;
    private final CxxPlatform cxxPlatform;
    private final CxxSource.Type sourceType;

    CxxPreprocessorFlagsExpander(
        Class<M> clazz, CxxPlatform cxxPlatform, CxxSource.Type sourceType) {
      this.clazz = clazz;
      this.cxxPlatform = cxxPlatform;
      this.sourceType = sourceType;
    }

    @Override
    public Class<M> getInputClass() {
      return clazz;
    }

    /** Make sure all resolved targets are instances of {@link CxxPreprocessorDep}. */
    @Override
    protected ImmutableList<BuildRule> resolve(
        BuildRuleResolver resolver, ImmutableList<BuildTarget> input) throws MacroException {
      return FluentIterable.from(super.resolve(resolver, input))
          .filter(CxxPreprocessorDep.class::isInstance)
          .toList();
    }

    /** Get the transitive C/C++ preprocessor input rooted at the given rules. */
    private Collection<CxxPreprocessorInput> getCxxPreprocessorInput(
        ActionGraphBuilder graphBuilder, ImmutableList<BuildRule> rules) {
      return CxxPreprocessables.getTransitiveCxxPreprocessorInputFromDeps(
          cxxPlatform, graphBuilder, rules);
    }

    /**
     * Return the {@link PreprocessorFlags} object formed by the transitive C/C++ preprocessor input
     * for the given rules.
     */
    private PreprocessorFlags getPreprocessorFlags(
        Iterable<CxxPreprocessorInput> transitivePreprocessorInput) {
      PreprocessorFlags.Builder ppFlagsBuilder = PreprocessorFlags.builder();
      ExplicitCxxToolFlags.Builder toolFlagsBuilder = CxxToolFlags.explicitBuilder();
      toolFlagsBuilder.setPlatformFlags(
          CxxSourceTypes.getPlatformPreprocessFlags(cxxPlatform, sourceType));
      for (CxxPreprocessorInput input : transitivePreprocessorInput) {
        ppFlagsBuilder.addAllIncludes(input.getIncludes());
        ppFlagsBuilder.addAllFrameworkPaths(input.getFrameworks());
        toolFlagsBuilder.addAllRuleFlags(input.getPreprocessorFlags().get(sourceType));
      }
      ppFlagsBuilder.setOtherFlags(toolFlagsBuilder.build());
      return ppFlagsBuilder.build();
    }

    /**
     * Expand the preprocessor input for the given rules into a shell-escaped string containing all
     * flags and header trees.
     */
    @Override
    protected Arg expand(
        ActionGraphBuilder graphBuilder,
        TargetConfiguration targetConfiguration,
        ImmutableList<BuildRule> rules,
        Optional<Pattern> filter) {
      return new CxxPreprocessorFlagsArg(
          getPreprocessorFlags(getCxxPreprocessorInput(graphBuilder, rules)),
          CxxSourceTypes.getPreprocessor(cxxPlatform, sourceType)
              .resolve(graphBuilder, targetConfiguration),
          CxxDescriptionEnhancer.frameworkPathToSearchPath(
              cxxPlatform, graphBuilder.getSourcePathResolver()));
    }
  }

  /**
   * Argument type for C++ compiler preprocessor args. In addition to holding the flags themselves,
   * this type also holds a rule-keyable function mapping framework paths to search paths.
   */
  private static class CxxPreprocessorFlagsArg implements Arg {
    @AddToRuleKey private final PreprocessorFlags ppFlags;
    @AddToRuleKey private final Preprocessor preprocessor;

    @AddToRuleKey
    private final AddsToRuleKeyFunction<FrameworkPath, Path> frameworkPathToSearchPath;

    CxxPreprocessorFlagsArg(
        PreprocessorFlags ppFlags,
        Preprocessor preprocessor,
        AddsToRuleKeyFunction<FrameworkPath, Path> frameworkPathToSearchPath) {
      this.ppFlags = ppFlags;
      this.preprocessor = preprocessor;
      this.frameworkPathToSearchPath = frameworkPathToSearchPath;
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolverAdapter resolver) {
      consumer.accept(
          Arg.stringify(
                  ppFlags
                      .toToolFlags(
                          resolver,
                          PathShortener.identity(),
                          frameworkPathToSearchPath,
                          preprocessor,
                          /* pch */ Optional.empty())
                      .getAllFlags(),
                  resolver)
              .stream()
              .map(Escaper.SHELL_ESCAPER)
              .collect(Collectors.joining(" ")));
    }
  }

  /**
   * A build target expander that replaces lists of build target with their transitive preprocessor
   * input.
   */
  private static class CxxLinkerFlagsExpander<M extends CxxGenruleFilterAndTargetsMacro>
      extends FilterAndTargetsExpander<M> {

    private final Class<M> clazz;
    private final BuildTarget buildTarget;
    private final ProjectFilesystem filesystem;
    private final CxxPlatform cxxPlatform;
    private final Linker.LinkableDepType depType;
    private final String out;

    CxxLinkerFlagsExpander(
        Class<M> clazz,
        BuildTarget buildTarget,
        ProjectFilesystem filesystem,
        CxxPlatform cxxPlatform,
        Linker.LinkableDepType depType,
        String out) {
      this.clazz = clazz;
      this.buildTarget = buildTarget;
      this.filesystem = filesystem;
      this.cxxPlatform = cxxPlatform;
      this.depType = depType;
      this.out = out;
    }

    @Override
    public Class<M> getInputClass() {
      return clazz;
    }

    /**
     * @return a {@link SymlinkTree} containing all the transitive shared libraries from the given
     *     roots linked in by their library name.
     */
    private SymlinkTree requireSymlinkTree(
        ActionGraphBuilder graphBuilder, ImmutableList<BuildRule> rules) {
      return CxxDescriptionEnhancer.requireSharedLibrarySymlinkTree(
          buildTarget, filesystem, graphBuilder, cxxPlatform, rules);
    }

    /**
     * @return the list of {@link Arg} required for dynamic linking so that linked binaries can find
     *     their shared library dependencies at runtime.
     */
    private ImmutableList<Arg> getSharedLinkArgs(
        ActionGraphBuilder graphBuilder, ImmutableList<BuildRule> rules) {

      // Embed a origin-relative library path into the binary so it can find the shared libraries.
      // The shared libraries root is absolute. Also need an absolute path to the linkOutput
      Path linkOutput = BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s").resolve(out);
      Path absLinkOut = filesystem.resolve(linkOutput);
      SymlinkTree symlinkTree = requireSymlinkTree(graphBuilder, rules);
      return RichStream.from(
              StringArg.from(
                  Linkers.iXlinker(
                      "-rpath",
                      String.format(
                          "%s/%s",
                          cxxPlatform
                              .getLd()
                              .resolve(graphBuilder, buildTarget.getTargetConfiguration())
                              .origin(),
                          absLinkOut.getParent().relativize(symlinkTree.getRoot()).toString()))))
          .map(arg -> new SymlinkProxyArg(arg, symlinkTree.getSourcePathToOutput()))
          .collect(ImmutableList.toImmutableList());
    }

    private NativeLinkableInput getNativeLinkableInput(
        ActionGraphBuilder graphBuilder, Iterable<BuildRule> rules, Optional<Pattern> filter) {
      ImmutableList<? extends NativeLinkable> nativeLinkables =
          NativeLinkables.getNativeLinkables(
              graphBuilder,
              FluentIterable.from(rules)
                  .filter(NativeLinkableGroup.class)
                  .transform(g -> g.getNativeLinkable(cxxPlatform, graphBuilder)),
              depType,
              !filter.isPresent()
                  ? x -> true
                  : input ->
                      filter
                          .get()
                          .matcher(
                              String.format("%s(%s)", input.getRuleType(), input.getBuildTarget()))
                          .find());
      ImmutableList.Builder<NativeLinkableInput> nativeLinkableInputs = ImmutableList.builder();
      for (NativeLinkable nativeLinkable : nativeLinkables) {
        nativeLinkableInputs.add(
            NativeLinkables.getNativeLinkableInput(
                depType, nativeLinkable, graphBuilder, buildTarget.getTargetConfiguration()));
      }
      return NativeLinkableInput.concat(nativeLinkableInputs.build());
    }

    /** Make sure all resolved targets are instances of {@link NativeLinkableGroup}. */
    @Override
    protected ImmutableList<BuildRule> resolve(
        BuildRuleResolver resolver, ImmutableList<BuildTarget> input) throws MacroException {
      return FluentIterable.from(super.resolve(resolver, input))
          .filter(NativeLinkableGroup.class::isInstance)
          .toList();
    }

    /** Return the args formed by the transitive native linkable input for the given rules. */
    private ImmutableList<Arg> getLinkerArgs(
        ActionGraphBuilder graphBuilder, ImmutableList<BuildRule> rules, Optional<Pattern> filter) {
      ImmutableList.Builder<Arg> args = ImmutableList.builder();
      args.addAll(cxxPlatform.getLdflags());
      if (depType == Linker.LinkableDepType.SHARED) {
        args.addAll(getSharedLinkArgs(graphBuilder, rules));
      }
      args.addAll(getNativeLinkableInput(graphBuilder, rules, filter).getArgs());
      return args.build();
    }

    /**
     * Expand the native linkable input for the given rules into a shell-escaped string containing
     * all linker flags.
     */
    @Override
    public Arg expand(
        ActionGraphBuilder graphBuilder,
        TargetConfiguration targetConfiguration,
        ImmutableList<BuildRule> rules,
        Optional<Pattern> filter) {
      return new ShQuoteJoinArg(getLinkerArgs(graphBuilder, rules, filter));
    }
  }

  private static class ShQuoteJoinArg implements Arg {
    @AddToRuleKey private final ImmutableList<Arg> args;

    ShQuoteJoinArg(ImmutableList<Arg> args) {
      this.args = args;
    }

    @Override
    public void appendToCommandLine(
        Consumer<String> consumer, SourcePathResolverAdapter pathResolver) {
      consumer.accept(shquoteJoin(Arg.stringify(args, pathResolver)));
    }
  }

  private static class SymlinkProxyArg extends ProxyArg {
    // This is added so that the arg's rulekey properly reflects its deps.
    @AddToRuleKey private final NonHashableSourcePathContainer symlinkTreeRef;

    public SymlinkProxyArg(Arg arg, SourcePath symlinkTreePath) {
      super(arg);
      this.symlinkTreeRef = new NonHashableSourcePathContainer(symlinkTreePath);
    }
  }
}
