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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.macros.BuildTargetsMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.rules.macros.StringExpander;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.util.Escaper;
import com.google.common.base.CaseFormat;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Collection;

public class CxxGenruleDescription
    extends AbstractGenruleDescription<AbstractGenruleDescription.Arg>
    implements Flavored {

  public static final BuildRuleType TYPE = BuildRuleType.of("cxx_genrule");

  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public CxxGenruleDescription(FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.cxxPlatforms = cxxPlatforms;
  }

  /**
   * @return a new {@link BuildTargetSourcePath} for an existing {@link BuildTargetSourcePath} which
   *         refers to a {@link CxxGenrule} with the given {@code platform} flavor applied.
   */
  public static SourcePath fixupSourcePath(
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform platform,
      SourcePath path)
      throws NoSuchBuildTargetException {
    Optional<BuildRule> rule = pathResolver.getRule(path);
    if (rule.isPresent() && rule.get() instanceof CxxGenrule) {
      BuildRule platformRule =
          ruleResolver.requireRule(
              rule.get().getBuildTarget().withAppendedFlavors(platform.getFlavor()));
      path = new BuildTargetSourcePath(platformRule.getBuildTarget());
    }
    return path;
  }


  private static String shquoteJoin(Iterable<String> args) {
    return Joiner.on(' ').join(Iterables.transform(args, Escaper.SHELL_ESCAPER));
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return cxxPlatforms.containsAnyOf(flavors);
  }

  @Override
  protected MacroHandler getMacroHandlerForParseTimeDeps() {
    ImmutableMap.Builder<String, MacroExpander> macros = ImmutableMap.builder();
    macros.put("exe", new ExecutableMacroExpander());
    macros.put("location", new LocationMacroExpander());
    macros.put("location-platform", new LocationMacroExpander());
    macros.put("platform-name", new StringExpander(""));
    macros.put("cc", new CxxPlatformParseTimeDepsExpander(cxxPlatforms));
    macros.put("cxx", new CxxPlatformParseTimeDepsExpander(cxxPlatforms));
    macros.put("cflags", new StringExpander(""));
    macros.put("cxxflags", new StringExpander(""));
    macros.put("cppflags", new ParseTimeDepsExpander());
    macros.put("cxxppflags", new ParseTimeDepsExpander());
    macros.put("solibs", new ParseTimeDepsExpander());
    macros.put("ld", new CxxPlatformParseTimeDepsExpander(cxxPlatforms));
    for (Linker.LinkableDepType style : Linker.LinkableDepType.values()) {
      macros.put(
          String.format(
              "ldflags-%s",
              CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, style.toString())),
          new ParseTimeDepsExpander());
    }
    return new MacroHandler(macros.build());
  }

  @Override
  protected <A extends AbstractGenruleDescription.Arg> MacroHandler getMacroHandler(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    final Optional<CxxPlatform> cxxPlatform = cxxPlatforms.getValue(params.getBuildTarget());
    Preconditions.checkState(cxxPlatform.isPresent());
    return new MacroHandler(
        ImmutableMap.<String, MacroExpander>builder()
            .put("exe", new ExecutableMacroExpander())
            .put("location", new LocationMacroExpander())
            .put("location-platform", new LocationMacroExpander() {
              @Override
              protected BuildRule resolve(BuildRuleResolver resolver, BuildTarget input)
                  throws MacroException {
                try {
                  return resolver.requireRule(input.withFlavors(cxxPlatform.get().getFlavor()));
                } catch (NoSuchBuildTargetException e) {
                  throw new MacroException(e.getHumanReadableErrorMessage());
                }
              }
            })
            .put("cc", new ToolExpander(cxxPlatform.get().getCc().resolve(resolver)))
            .put("cxx", new ToolExpander(cxxPlatform.get().getCxx().resolve(resolver)))
            .put("cflags", new StringExpander(shquoteJoin(cxxPlatform.get().getCflags())))
            .put("cxxflags", new StringExpander(shquoteJoin(cxxPlatform.get().getCxxflags())))
            .put(
                "cppflags",
                new CxxPreprocessorFlagsExpander(cxxPlatform.get(), CxxSource.Type.C))
            .put(
                "cxxppflags",
                new CxxPreprocessorFlagsExpander(cxxPlatform.get(), CxxSource.Type.CXX))
            .put("ld", new ToolExpander(cxxPlatform.get().getLd().resolve(resolver)))
            .put(
                "ldflags-shared",
                new CxxLinkerFlagsExpander(
                    params,
                    cxxPlatform.get(),
                    Linker.LinkableDepType.SHARED, args.out))
            .put(
                "ldflags-static",
                new CxxLinkerFlagsExpander(
                    params,
                    cxxPlatform.get(),
                    Linker.LinkableDepType.STATIC, args.out))
            .put(
                "ldflags-static-pic",
                new CxxLinkerFlagsExpander(
                    params,
                    cxxPlatform.get(),
                    Linker.LinkableDepType.STATIC_PIC, args.out))
            .put("platform-name", new StringExpander(cxxPlatform.get().getFlavor().toString()))
            .build());
  }

  @Override
  public <A extends AbstractGenruleDescription.Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args)
      throws NoSuchBuildTargetException {
    Optional<CxxPlatform> cxxPlatform = cxxPlatforms.getValue(params.getBuildTarget());
    if (cxxPlatform.isPresent()) {
      return super.createBuildRule(
          targetGraph,
          params.withFlavor(cxxPlatform.get().getFlavor()),
          resolver,
          args);
    }
    return new CxxGenrule(params, new SourcePathResolver(resolver), resolver, args.out);
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();

    // Add in all parse time deps from the C/C++ platforms.
    for (CxxPlatform cxxPlatform : cxxPlatforms.getValues()) {
      targets.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatform));
    }

    // Add in parse time deps from parent.
    targets.addAll(
        super.findDepsForTargetFromConstructorArgs(buildTarget, cellRoots, constructorArg));

    return targets.build();
  }

  /**
   * A build target macro expander just used at parse time to extract deps from the preprocessor
   * flag macros.
   */
  private static class ParseTimeDepsExpander extends BuildTargetsMacroExpander {

    @Override
    protected String expand(
        BuildRuleResolver resolver,
        ImmutableList<BuildRule> rule)
        throws MacroException {
      throw new IllegalStateException();
    }

  }

  /**
   * A macro expander that expands to a specific {@link Tool}.
   */
  private static class ToolExpander implements MacroExpander {

    private final Tool tool;

    public ToolExpander(Tool tool) {
      this.tool = tool;
    }

    @Override
    public String expand(
        BuildTarget target,
        CellPathResolver cellNames,
        BuildRuleResolver resolver,
        ImmutableList<String> input) throws MacroException {
      SourcePathResolver pathResolver = new SourcePathResolver(resolver);
      return shquoteJoin(tool.getCommandPrefix(pathResolver));
    }

    @Override
    public ImmutableList<BuildRule> extractBuildTimeDeps(
        BuildTarget target,
        CellPathResolver cellNames,
        BuildRuleResolver resolver,
        ImmutableList<String> input) throws MacroException {
      SourcePathResolver pathResolver = new SourcePathResolver(resolver);
      return ImmutableList.copyOf(tool.getDeps(pathResolver));
    }

    @Override
    public ImmutableList<BuildTarget> extractParseTimeDeps(
        BuildTarget target,
        CellPathResolver cellNames,
        ImmutableList<String> input)
        throws MacroException {
      // We already return all platform-specific parse-time deps from
      // `findDepsForTargetFromConstructorArgs`.
      return ImmutableList.of();
    }

    @Override
    public Object extractRuleKeyAppendables(
        BuildTarget target,
        CellPathResolver cellNames,
        BuildRuleResolver resolver,
        ImmutableList<String> input) throws MacroException {
      return tool;
    }

  }

  /**
   * A build target expander that replaces lists of build target with their transitive preprocessor
   * input.
   */
  private static class CxxPreprocessorFlagsExpander extends BuildTargetsMacroExpander {

    private final CxxPlatform cxxPlatform;
    private final CxxSource.Type sourceType;

    public CxxPreprocessorFlagsExpander(
        CxxPlatform cxxPlatform,
        CxxSource.Type sourceType) {
      this.cxxPlatform = cxxPlatform;
      this.sourceType = sourceType;
    }

    /**
     * Make sure all resolved targets are instances of {@link CxxPreprocessorDep}.
     */
    @Override
    protected ImmutableList<BuildRule> resolve(
        BuildRuleResolver resolver,
        ImmutableList<BuildTarget> input)
        throws MacroException {
      return FluentIterable.from(super.resolve(resolver, input))
          .filter(Predicates.instanceOf(CxxPreprocessorDep.class))
          .toList();
    }

    /**
     * Get the transitive C/C++ preprocessor input rooted at the given rules.
     */
    private Collection<CxxPreprocessorInput> getCxxPreprocessorInput(
        ImmutableList<BuildRule> rules)
        throws MacroException {
      try {
        return CxxPreprocessables.getTransitiveCxxPreprocessorInput(cxxPlatform, rules);
      } catch (NoSuchBuildTargetException e) {
        throw new MacroException(
            String.format("failed getting preprocessor input: %s", e.getMessage()), e);
      }
    }

    /**
     * Return the {@link PreprocessorFlags} object formed by the transitive C/C++ preprocessor
     * input for the given rules.
     */
    private PreprocessorFlags getPreprocessorFlags(
        Iterable<CxxPreprocessorInput> transitivePreprocessorInput) {
      PreprocessorFlags.Builder ppFlagsBuilder = PreprocessorFlags.builder();
      ExplicitCxxToolFlags.Builder toolFlagsBuilder = CxxToolFlags.explicitBuilder();
      toolFlagsBuilder.setPlatformFlags(
          CxxSourceTypes.getPlatformPreprocessFlags(cxxPlatform, sourceType));
      for (CxxPreprocessorInput input : transitivePreprocessorInput) {
        ppFlagsBuilder.addAllIncludes(input.getIncludes());
        ppFlagsBuilder.addAllSystemIncludePaths(input.getSystemIncludeRoots());
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
    protected String expand(
        BuildRuleResolver resolver,
        ImmutableList<BuildRule> rules)
        throws MacroException {
      SourcePathResolver pathResolver = new SourcePathResolver(resolver);
      PreprocessorFlags ppFlags = getPreprocessorFlags(getCxxPreprocessorInput(rules));
      Preprocessor preprocessor =
          CxxSourceTypes.getPreprocessor(cxxPlatform, sourceType).resolve(resolver);
      CxxToolFlags flags =
          ppFlags.toToolFlags(
              pathResolver,
              Functions.identity(),
              CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, pathResolver),
              preprocessor);
      return Joiner.on(' ').join(Iterables.transform(flags.getAllFlags(), Escaper.SHELL_ESCAPER));
    }

    @Override
    protected ImmutableList<BuildRule> extractBuildTimeDeps(
        BuildRuleResolver resolver,
        ImmutableList<BuildRule> rules)
        throws MacroException {
      SourcePathResolver pathResolver = new SourcePathResolver(resolver);
      ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
      for (CxxPreprocessorInput input : getCxxPreprocessorInput(rules)) {
        deps.addAll(input.getDeps(resolver, pathResolver));
      }
      return deps.build();
    }

    @Override
    public Object extractRuleKeyAppendablesFrom(
        final BuildTarget target,
        final CellPathResolver cellNames,
        final BuildRuleResolver resolver,
        final ImmutableList<BuildTarget> input) throws MacroException {
      final Iterable<CxxPreprocessorInput> transitivePreprocessorInput =
          getCxxPreprocessorInput(resolve(resolver, input));
      final PreprocessorFlags ppFlags = getPreprocessorFlags(transitivePreprocessorInput);
      return (RuleKeyAppendable) sink -> {
        ppFlags.appendToRuleKey(sink, cxxPlatform.getDebugPathSanitizer());
        sink.setReflectively(
            "headers",
            FluentIterable.from(transitivePreprocessorInput)
                .transformAndConcat(CxxPreprocessorInput::getIncludes)
                .toList());
      };
    }

  }

  /**
   * A build target expander that replaces lists of build target with their transitive preprocessor
   * input.
   */
  private static class CxxLinkerFlagsExpander extends BuildTargetsMacroExpander {

    private final BuildRuleParams params;
    private final CxxPlatform cxxPlatform;
    private final Linker.LinkableDepType depType;
    private final String out;

    public CxxLinkerFlagsExpander(
        BuildRuleParams params,
        CxxPlatform cxxPlatform,
        Linker.LinkableDepType depType,
        String out) {
      this.params = params;
      this.cxxPlatform = cxxPlatform;
      this.depType = depType;
      this.out = out;
    }

    /**
     * @return a {@link SymlinkTree} containing all the transitive shared libraries from the given
     *         roots linked in by their library name.
     */
    private SymlinkTree requireSymlinkTree(
        BuildRuleResolver resolver,
        ImmutableList<BuildRule> rules)
        throws MacroException {
      SourcePathResolver pathResolver = new SourcePathResolver(resolver);
      BuildTarget symlinkTreeTarget =
          CxxDescriptionEnhancer.createSharedLibrarySymlinkTreeTarget(
              params.getBuildTarget(),
              cxxPlatform.getFlavor());
      SymlinkTree symlinkTree =
          resolver.getRuleOptionalWithType(symlinkTreeTarget, SymlinkTree.class).orNull();
      if (symlinkTree == null) {
        try {
          symlinkTree =
              resolver.addToIndex(
                  CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
                      params,
                      pathResolver,
                      cxxPlatform,
                      rules,
                      Predicates.instanceOf(NativeLinkable.class)));
        } catch (NoSuchBuildTargetException e) {
          throw new MacroException(
              String.format("cannot create shared library symlink tree: %s: %s", e, e.getMessage()),
              e);
        }
      }
      return symlinkTree;
    }

    /**
     * @return the list of {@link com.facebook.buck.rules.args.Arg} required for dynamic linking so
     *         that linked binaries can find their shared library dependencies at runtime.
     */
    private ImmutableList<com.facebook.buck.rules.args.Arg> getSharedLinkArgs(
        BuildRuleResolver resolver,
        ImmutableList<BuildRule> rules)
        throws MacroException {

      // Embed a origin-relative library path into the binary so it can find the shared libraries.
      // The shared libraries root is absolute. Also need an absolute path to the linkOutput
      Path linkOutput =
          BuildTargets.getGenPath(params.getProjectFilesystem(), params.getBuildTarget(), "%s")
              .resolve(out);
      Path absLinkOut = params.getBuildTarget().getCellPath().resolve(linkOutput);
      SymlinkTree symlinkTree = requireSymlinkTree(resolver, rules);
      return ImmutableList.copyOf(
          StringArg.from(
              Linkers.iXlinker(
                  "-rpath",
                  String.format(
                      "%s/%s",
                      cxxPlatform.getLd().resolve(resolver).origin(),
                      absLinkOut.getParent().relativize(symlinkTree.getRoot()).toString()))));
    }

    private NativeLinkableInput getNativeLinkableInput(Iterable<BuildRule> rules)
        throws MacroException {
      try {
        return NativeLinkables.getTransitiveNativeLinkableInput(
            cxxPlatform,
            rules,
            depType,
            Predicates.alwaysTrue());
      } catch (NoSuchBuildTargetException e) {
        throw new MacroException(
            String.format("failed getting native linker args: %s", e.getMessage()), e);
      }
    }

    /**
     * Make sure all resolved targets are instances of {@link NativeLinkable}.
     */
    @Override
    protected ImmutableList<BuildRule> resolve(
        BuildRuleResolver resolver,
        ImmutableList<BuildTarget> input)
        throws MacroException {
      return FluentIterable.from(super.resolve(resolver, input))
          .filter(Predicates.instanceOf(NativeLinkable.class))
          .toList();
    }

    /**
     * Return the args formed by the transitive native linkable input for the given rules.
     */
    private ImmutableList<com.facebook.buck.rules.args.Arg> getLinkerArgs(
        BuildRuleResolver resolver,
        ImmutableList<BuildRule> rules)
        throws MacroException {
      ImmutableList.Builder<com.facebook.buck.rules.args.Arg> args = ImmutableList.builder();
      args.addAll(StringArg.from(cxxPlatform.getLdflags()));
      if (depType == Linker.LinkableDepType.SHARED) {
        args.addAll(getSharedLinkArgs(resolver, rules));
      }
      args.addAll(getNativeLinkableInput(rules).getArgs());
      return args.build();
    }

    /**
     * Expand the native linkable input for the given rules into a shell-escaped string containing
     * all linker flags.
     */
    @Override
    protected String expand(
        BuildRuleResolver resolver,
        ImmutableList<BuildRule> rules)
        throws MacroException {
      return shquoteJoin(
          com.facebook.buck.rules.args.Arg.stringify(getLinkerArgs(resolver, rules)));
    }

    @Override
    protected ImmutableList<BuildRule> extractBuildTimeDeps(
        BuildRuleResolver resolver,
        ImmutableList<BuildRule> rules)
        throws MacroException {
      SourcePathResolver pathResolver = new SourcePathResolver(resolver);
      ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
      deps.addAll(
          FluentIterable.from(getLinkerArgs(resolver, rules))
              .transformAndConcat(com.facebook.buck.rules.args.Arg.getDepsFunction(pathResolver)));
      if (depType == Linker.LinkableDepType.SHARED) {
        deps.add(requireSymlinkTree(resolver, rules));
      }
      return deps.build();
    }

    @Override
    public Object extractRuleKeyAppendablesFrom(
        final BuildTarget target,
        final CellPathResolver cellNames,
        final BuildRuleResolver resolver,
        final ImmutableList<BuildTarget> input) throws MacroException {
      return getLinkerArgs(resolver, resolve(resolver, input));
    }

  }

  private static class CxxPlatformParseTimeDepsExpander extends StringExpander {

    private final FlavorDomain<CxxPlatform> cxxPlatforms;

    public CxxPlatformParseTimeDepsExpander(FlavorDomain<CxxPlatform> cxxPlatforms) {
      super("");
      this.cxxPlatforms = cxxPlatforms;
    }

    @Override
    public ImmutableList<BuildTarget> extractParseTimeDeps(
        BuildTarget target,
        CellPathResolver cellNames,
        ImmutableList<String> input) throws MacroException {
      Optional<CxxPlatform> platform = cxxPlatforms.getValue(target.getFlavors());
      return platform.isPresent() ?
          ImmutableList.copyOf(CxxPlatforms.getParseTimeDeps(platform.get())) :
          ImmutableList.<BuildTarget>of();
    }

  }

}
