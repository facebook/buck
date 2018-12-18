/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetParser;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetPatternParser;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.cxx.toolchain.linker.Linkers;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.ProxyArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.args.ToolArg;
import com.facebook.buck.rules.macros.AbstractMacroExpander;
import com.facebook.buck.rules.macros.AbstractMacroExpanderWithoutPrecomputedWork;
import com.facebook.buck.rules.macros.CcFlagsMacro;
import com.facebook.buck.rules.macros.CcMacro;
import com.facebook.buck.rules.macros.CppFlagsMacro;
import com.facebook.buck.rules.macros.CxxFlagsMacro;
import com.facebook.buck.rules.macros.CxxGenruleFilterAndTargetsMacro;
import com.facebook.buck.rules.macros.CxxMacro;
import com.facebook.buck.rules.macros.CxxppFlagsMacro;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LdMacro;
import com.facebook.buck.rules.macros.LdflagsSharedFilterMacro;
import com.facebook.buck.rules.macros.LdflagsSharedMacro;
import com.facebook.buck.rules.macros.LdflagsStaticFilterMacro;
import com.facebook.buck.rules.macros.LdflagsStaticMacro;
import com.facebook.buck.rules.macros.LdflagsStaticPicFilterMacro;
import com.facebook.buck.rules.macros.LdflagsStaticPicMacro;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.PlatformNameMacro;
import com.facebook.buck.rules.macros.SimpleMacroExpander;
import com.facebook.buck.rules.macros.StringExpander;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.immutables.value.Value;

public class CxxGenruleDescription extends AbstractGenruleDescription<CxxGenruleDescriptionArg>
    implements Flavored,
        VersionPropagator<CxxGenruleDescriptionArg>,
        ImplicitDepsInferringDescription<CxxGenruleDescriptionArg> {

  private final ImmutableSet<Flavor> declaredPlatforms;

  public CxxGenruleDescription(
      CxxBuckConfig cxxBuckConfig,
      ToolchainProvider toolchainProvider,
      SandboxExecutionStrategy sandboxExecutionStrategy) {
    super(toolchainProvider, sandboxExecutionStrategy, false);
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
      ActionGraphBuilder graphBuilder,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform platform,
      SourcePath path) {
    Optional<BuildRule> rule = ruleFinder.getRule(path);
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
      ActionGraphBuilder graphBuilder,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      ImmutableList<SourcePath> paths) {
    ImmutableList.Builder<SourcePath> fixed = ImmutableList.builder();
    for (SourcePath path : paths) {
      fixed.add(fixupSourcePath(graphBuilder, ruleFinder, cxxPlatform, path));
    }
    return fixed.build();
  }

  public static ImmutableSortedSet<SourcePath> fixupSourcePaths(
      ActionGraphBuilder graphBuilder,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<SourcePath> paths) {
    ImmutableSortedSet.Builder<SourcePath> fixed =
        new ImmutableSortedSet.Builder<>(Objects.requireNonNull(paths.comparator()));
    for (SourcePath path : paths) {
      fixed.add(fixupSourcePath(graphBuilder, ruleFinder, cxxPlatform, path));
    }
    return fixed.build();
  }

  public static <T> ImmutableMap<T, SourcePath> fixupSourcePaths(
      ActionGraphBuilder graphBuilder,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      ImmutableMap<T, SourcePath> paths) {
    ImmutableMap.Builder<T, SourcePath> fixed = ImmutableMap.builder();
    for (Map.Entry<T, SourcePath> ent : paths.entrySet()) {
      fixed.put(
          ent.getKey(), fixupSourcePath(graphBuilder, ruleFinder, cxxPlatform, ent.getValue()));
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
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return getCxxPlatforms().containsAnyOf(flavors)
        || !Sets.intersection(declaredPlatforms, flavors).isEmpty();
  }

  @Override
  protected Optional<ImmutableList<AbstractMacroExpander<? extends Macro, ?>>> getMacroHandler(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      TargetGraph targetGraph,
      CxxGenruleDescriptionArg args) {

    Optional<CxxPlatform> maybeCxxPlatform = getCxxPlatforms().getValue(buildTarget);
    if (!maybeCxxPlatform.isPresent()) {
      return Optional.empty();
    }

    CxxPlatform cxxPlatform = maybeCxxPlatform.get();
    ImmutableList.Builder<AbstractMacroExpander<? extends Macro, ?>> expanders =
        ImmutableList.builder();

    expanders.add(new ExecutableMacroExpander());
    expanders.add(new CxxLocationMacroExpander(cxxPlatform));
    expanders.add(
        new StringExpander<>(
            PlatformNameMacro.class, StringArg.of(cxxPlatform.getFlavor().toString())));
    expanders.add(new ToolExpander<>(CcMacro.class, cxxPlatform.getCc().resolve(resolver)));
    expanders.add(new ToolExpander<>(CxxMacro.class, cxxPlatform.getCxx().resolve(resolver)));

    ImmutableList<String> asflags = cxxPlatform.getAsflags();
    ImmutableList<String> cflags = cxxPlatform.getCflags();
    ImmutableList<String> cxxflags = cxxPlatform.getCxxflags();
    expanders.add(
        new StringExpander<>(
            CcFlagsMacro.class, StringArg.of(shquoteJoin(Iterables.concat(cflags, asflags)))));
    expanders.add(
        new StringExpander<>(
            CxxFlagsMacro.class, StringArg.of(shquoteJoin(Iterables.concat(cxxflags, asflags)))));

    expanders.add(
        new CxxPreprocessorFlagsExpander<>(CppFlagsMacro.class, cxxPlatform, CxxSource.Type.C));
    expanders.add(
        new CxxPreprocessorFlagsExpander<>(CxxppFlagsMacro.class, cxxPlatform, CxxSource.Type.CXX));
    expanders.add(new ToolExpander<>(LdMacro.class, cxxPlatform.getLd().resolve(resolver)));

    for (Map.Entry<Class<? extends CxxGenruleFilterAndTargetsMacro>, Pair<LinkableDepType, Filter>>
        ent :
            ImmutableMap
                .<Class<? extends CxxGenruleFilterAndTargetsMacro>, Pair<LinkableDepType, Filter>>
                    builder()
                .put(LdflagsSharedMacro.class, new Pair<>(LinkableDepType.SHARED, Filter.NONE))
                .put(
                    LdflagsSharedFilterMacro.class,
                    new Pair<>(LinkableDepType.SHARED, Filter.PARAM))
                .put(LdflagsStaticMacro.class, new Pair<>(LinkableDepType.STATIC, Filter.NONE))
                .put(
                    LdflagsStaticFilterMacro.class,
                    new Pair<>(LinkableDepType.STATIC, Filter.PARAM))
                .put(
                    LdflagsStaticPicMacro.class,
                    new Pair<>(LinkableDepType.STATIC_PIC, Filter.NONE))
                .put(
                    LdflagsStaticPicFilterMacro.class,
                    new Pair<>(LinkableDepType.STATIC_PIC, Filter.PARAM))
                .build()
                .entrySet()) {
      expanders.add(
          new CxxLinkerFlagsExpander<>(
              ent.getKey(),
              buildTarget,
              filesystem,
              cxxPlatform,
              ent.getValue().getFirst(),
              args.getOut(),
              ent.getValue().getSecond()));
    }

    return Optional.of(expanders.build());
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      CxxGenruleDescriptionArg args) {
    Optional<CxxPlatform> cxxPlatform = getCxxPlatforms().getValue(buildTarget);
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
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CxxGenruleDescriptionArg args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe) {
    return createBuildRule(
        buildTarget,
        projectFilesystem,
        params,
        graphBuilder,
        args,
        cmd,
        bash,
        cmdExe,
        args.getOut());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      CxxGenruleDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Add in all parse time deps from the C/C++ platforms.
    for (CxxPlatform cxxPlatform : getCxxPlatforms().getValues()) {
      targetGraphOnlyDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatform));
    }
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  private FlavorDomain<CxxPlatform> getCxxPlatforms() {
    return toolchainProvider
        .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
        .getCxxPlatforms();
  }

  @BuckStyleImmutable
  @Value.Immutable
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
    public Arg expandFrom(
        BuildTarget target, CellPathResolver cellNames, BuildRuleResolver resolver) {
      return ToolArg.of(tool);
    }
  }

  private abstract static class FilterAndTargetsExpander<M extends CxxGenruleFilterAndTargetsMacro>
      extends AbstractMacroExpanderWithoutPrecomputedWork<M> {

    private final Filter filter;

    FilterAndTargetsExpander(Filter filter) {
      this.filter = filter;
    }

    /** @return an instance of the subclass represented by T. */
    @SuppressWarnings("unchecked")
    public <T extends CxxGenruleFilterAndTargetsMacro> T create(
        Class<T> clazz, Optional<Pattern> filter, ImmutableList<BuildTarget> targets) {
      try {
        return (T)
            clazz
                .getMethod("of", Optional.class, ImmutableList.class)
                .invoke(null, filter, targets);
      } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    protected final M parse(
        BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input)
        throws MacroException {

      if (this.filter == Filter.PARAM && input.size() < 1) {
        throw new MacroException("expected at least 1 argument");
      }

      Iterator<String> itr = input.iterator();

      Optional<Pattern> filter =
          this.filter == Filter.PARAM ? Optional.of(Pattern.compile(itr.next())) : Optional.empty();

      ImmutableList.Builder<BuildTarget> targets = ImmutableList.builder();
      while (itr.hasNext()) {
        targets.add(
            BuildTargetParser.INSTANCE.parse(
                itr.next(), BuildTargetPatternParser.forBaseName(target.getBaseName()), cellNames));
      }

      return create(getInputClass(), filter, targets.build());
    }

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
        ActionGraphBuilder graphBuilder, ImmutableList<BuildRule> rules, Optional<Pattern> filter);

    @Override
    public Arg expandFrom(
        BuildTarget target, CellPathResolver cellNames, ActionGraphBuilder graphBuilder, M input)
        throws MacroException {
      return expand(graphBuilder, resolve(graphBuilder, input.getTargets()), input.getFilter());
    }

    @Override
    public void extractParseTimeDepsFrom(
        BuildTarget target,
        CellPathResolver cellNames,
        M input,
        ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
        ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
      buildDepsBuilder.addAll(input.getTargets());
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
      super(Filter.NONE);
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
      return CxxPreprocessables.getTransitiveCxxPreprocessorInput(cxxPlatform, graphBuilder, rules);
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
          StringArg.from(CxxSourceTypes.getPlatformPreprocessFlags(cxxPlatform, sourceType)));
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
        ActionGraphBuilder graphBuilder, ImmutableList<BuildRule> rules, Optional<Pattern> filter) {
      return new CxxPreprocessorFlagsArg(
          getPreprocessorFlags(getCxxPreprocessorInput(graphBuilder, rules)),
          CxxSourceTypes.getPreprocessor(cxxPlatform, sourceType).resolve(graphBuilder));
    }

    private class CxxPreprocessorFlagsArg implements Arg {
      @AddToRuleKey private final PreprocessorFlags ppFlags;
      @AddToRuleKey private final Preprocessor preprocessor;

      CxxPreprocessorFlagsArg(PreprocessorFlags ppFlags, Preprocessor preprocessor) {
        this.ppFlags = ppFlags;
        this.preprocessor = preprocessor;
      }

      @Override
      public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver resolver) {
        consumer.accept(
            Arg.stringify(
                    ppFlags
                        .toToolFlags(
                            resolver,
                            PathShortener.identity(),
                            CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, resolver),
                            preprocessor,
                            /* pch */ Optional.empty())
                        .getAllFlags(),
                    resolver)
                .stream()
                .map(Escaper.SHELL_ESCAPER)
                .collect(Collectors.joining(" ")));
      }
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
        String out,
        Filter filter) {
      super(filter);
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
      Path absLinkOut = buildTarget.getCellPath().resolve(linkOutput);
      SymlinkTree symlinkTree = requireSymlinkTree(graphBuilder, rules);
      return RichStream.from(
              StringArg.from(
                  Linkers.iXlinker(
                      "-rpath",
                      String.format(
                          "%s/%s",
                          cxxPlatform.getLd().resolve(graphBuilder).origin(),
                          absLinkOut.getParent().relativize(symlinkTree.getRoot()).toString()))))
          .map(
              arg ->
                  new ProxyArg(arg) {
                    // This is added so that the arg's rulekey properly reflects its deps.
                    @AddToRuleKey
                    private final NonHashableSourcePathContainer symlinkTreeRef =
                        new NonHashableSourcePathContainer(symlinkTree.getSourcePathToOutput());
                  })
          .collect(ImmutableList.toImmutableList());
    }

    private NativeLinkableInput getNativeLinkableInput(
        ActionGraphBuilder graphBuilder, Iterable<BuildRule> rules, Optional<Pattern> filter) {
      ImmutableList<NativeLinkable> nativeLinkables =
          NativeLinkables.getNativeLinkables(
              cxxPlatform,
              graphBuilder,
              FluentIterable.from(rules).filter(NativeLinkable.class),
              depType,
              !filter.isPresent()
                  ? x -> true
                  : input -> {
                    Preconditions.checkArgument(input instanceof BuildRule);
                    BuildRule rule = (BuildRule) input;
                    return filter
                        .get()
                        .matcher(String.format("%s(%s)", rule.getType(), rule.getBuildTarget()))
                        .find();
                  });
      ImmutableList.Builder<NativeLinkableInput> nativeLinkableInputs = ImmutableList.builder();
      for (NativeLinkable nativeLinkable : nativeLinkables) {
        nativeLinkableInputs.add(
            NativeLinkables.getNativeLinkableInput(
                cxxPlatform, depType, nativeLinkable, graphBuilder));
      }
      return NativeLinkableInput.concat(nativeLinkableInputs.build());
    }

    /** Make sure all resolved targets are instances of {@link NativeLinkable}. */
    @Override
    protected ImmutableList<BuildRule> resolve(
        BuildRuleResolver resolver, ImmutableList<BuildTarget> input) throws MacroException {
      return FluentIterable.from(super.resolve(resolver, input))
          .filter(NativeLinkable.class::isInstance)
          .toList();
    }

    /** Return the args formed by the transitive native linkable input for the given rules. */
    private ImmutableList<Arg> getLinkerArgs(
        ActionGraphBuilder graphBuilder, ImmutableList<BuildRule> rules, Optional<Pattern> filter) {
      ImmutableList.Builder<Arg> args = ImmutableList.builder();
      args.addAll(StringArg.from(cxxPlatform.getLdflags()));
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
        ActionGraphBuilder graphBuilder, ImmutableList<BuildRule> rules, Optional<Pattern> filter) {
      return new ShQuoteJoinArg(getLinkerArgs(graphBuilder, rules, filter));
    }
  }

  private static class ShQuoteJoinArg implements Arg {
    @AddToRuleKey private final ImmutableList<Arg> args;

    ShQuoteJoinArg(ImmutableList<Arg> args) {
      this.args = args;
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
      consumer.accept(shquoteJoin(Arg.stringify(args, pathResolver)));
    }
  }

  private enum Filter {
    NONE,
    PARAM,
  }
}
