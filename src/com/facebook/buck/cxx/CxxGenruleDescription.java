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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.model.MacroFinder;
import com.facebook.buck.model.MacroReplacer;
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.macros.AbstractMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.rules.macros.StringExpander;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.facebook.buck.versions.TargetTranslatorOverridingDescription;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class CxxGenruleDescription extends AbstractGenruleDescription<CxxGenruleDescriptionArg>
    implements Flavored,
        VersionPropagator<CxxGenruleDescriptionArg>,
        TargetTranslatorOverridingDescription<CxxGenruleDescriptionArg> {

  private static final MacroFinder MACRO_FINDER = new MacroFinder();

  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public CxxGenruleDescription(FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.cxxPlatforms = cxxPlatforms;
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
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform platform,
      SourcePath path)
      throws NoSuchBuildTargetException {
    Optional<BuildRule> rule = ruleFinder.getRule(path);
    if (rule.isPresent() && rule.get() instanceof CxxGenrule) {
      Genrule platformRule =
          (Genrule)
              ruleResolver.requireRule(
                  rule.get().getBuildTarget().withAppendedFlavors(platform.getFlavor()));
      path = platformRule.getSourcePathToOutput();
    }
    return path;
  }

  public static ImmutableList<SourcePath> fixupSourcePaths(
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      ImmutableList<SourcePath> paths)
      throws NoSuchBuildTargetException {
    ImmutableList.Builder<SourcePath> fixed = ImmutableList.builder();
    for (SourcePath path : paths) {
      fixed.add(fixupSourcePath(ruleResolver, ruleFinder, cxxPlatform, path));
    }
    return fixed.build();
  }

  public static ImmutableSortedSet<SourcePath> fixupSourcePaths(
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<SourcePath> paths)
      throws NoSuchBuildTargetException {
    ImmutableSortedSet.Builder<SourcePath> fixed =
        new ImmutableSortedSet.Builder<>(Preconditions.checkNotNull(paths.comparator()));
    for (SourcePath path : paths) {
      fixed.add(fixupSourcePath(ruleResolver, ruleFinder, cxxPlatform, path));
    }
    return fixed.build();
  }

  public static <T> ImmutableMap<T, SourcePath> fixupSourcePaths(
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      ImmutableMap<T, SourcePath> paths)
      throws NoSuchBuildTargetException {
    ImmutableMap.Builder<T, SourcePath> fixed = ImmutableMap.builder();
    for (Map.Entry<T, SourcePath> ent : paths.entrySet()) {
      fixed.put(
          ent.getKey(), fixupSourcePath(ruleResolver, ruleFinder, cxxPlatform, ent.getValue()));
    }
    return fixed.build();
  }

  private static String shquoteJoin(Iterable<String> args) {
    return Joiner.on(' ').join(Iterables.transform(args, Escaper.SHELL_ESCAPER));
  }

  @Override
  public Class<CxxGenruleDescriptionArg> getConstructorArgType() {
    return CxxGenruleDescriptionArg.class;
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
    macros.put("cppflags", new ParseTimeDepsExpander(Filter.NONE));
    macros.put("cxxppflags", new ParseTimeDepsExpander(Filter.NONE));
    macros.put("solibs", new ParseTimeDepsExpander(Filter.NONE));
    macros.put("ld", new CxxPlatformParseTimeDepsExpander(cxxPlatforms));
    for (Linker.LinkableDepType style : Linker.LinkableDepType.values()) {
      for (Filter filter : Filter.values()) {
        macros.put(
            String.format(
                "ldflags-%s%s",
                CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, style.toString()),
                filter == Filter.PARAM ? "-filter" : ""),
            new ParseTimeDepsExpander(filter));
      }
    }
    return new MacroHandler(macros.build());
  }

  @Override
  protected Optional<MacroHandler> getMacroHandler(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      TargetGraph targetGraph,
      CxxGenruleDescriptionArg args) {
    Optional<CxxPlatform> maybeCxxPlatform = cxxPlatforms.getValue(buildTarget);
    if (!maybeCxxPlatform.isPresent()) {
      return Optional.empty();
    }
    CxxPlatform cxxPlatform = maybeCxxPlatform.get();
    ImmutableMap.Builder<String, MacroExpander> macros = ImmutableMap.builder();
    macros.put("exe", new ExecutableMacroExpander());
    macros.put("location", new CxxLocationMacroExpander(cxxPlatform));
    macros.put("platform-name", new StringExpander(cxxPlatform.getFlavor().toString()));
    macros.put(
        "location-platform",
        new LocationMacroExpander() {
          @Override
          protected BuildRule resolve(BuildRuleResolver resolver, LocationMacro input)
              throws MacroException {
            try {
              return resolver.requireRule(
                  input.getTarget().withAppendedFlavors(cxxPlatform.getFlavor()));
            } catch (NoSuchBuildTargetException e) {
              throw new MacroException(e.getHumanReadableErrorMessage());
            }
          }
        });
    macros.put("cc", new ToolExpander(cxxPlatform.getCc().resolve(resolver)));
    macros.put("cxx", new ToolExpander(cxxPlatform.getCxx().resolve(resolver)));

    ImmutableList<String> asflags = cxxPlatform.getAsflags();
    ImmutableList<String> cflags = cxxPlatform.getCflags();
    ImmutableList<String> cxxflags = cxxPlatform.getCxxflags();
    macros.put("cflags", new StringExpander(shquoteJoin(Iterables.concat(cflags, asflags))));
    macros.put("cxxflags", new StringExpander(shquoteJoin(Iterables.concat(cxxflags, asflags))));

    macros.put("cppflags", new CxxPreprocessorFlagsExpander(cxxPlatform, CxxSource.Type.C));
    macros.put("cxxppflags", new CxxPreprocessorFlagsExpander(cxxPlatform, CxxSource.Type.CXX));
    macros.put("ld", new ToolExpander(cxxPlatform.getLd().resolve(resolver)));
    for (Linker.LinkableDepType depType : Linker.LinkableDepType.values()) {
      for (Filter filter : Filter.values()) {
        macros.put(
            String.format(
                "ldflags-%s%s",
                depType.toString().toLowerCase().replace('_', '-'),
                filter == Filter.PARAM ? "-filter" : ""),
            new CxxLinkerFlagsExpander(
                buildTarget, filesystem, cxxPlatform, depType, args.getOut(), filter));
      }
    }
    return Optional.of(new MacroHandler(macros.build()));
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxGenruleDescriptionArg args)
      throws NoSuchBuildTargetException {
    Optional<CxxPlatform> cxxPlatform = cxxPlatforms.getValue(params.getBuildTarget());
    if (cxxPlatform.isPresent()) {
      return super.createBuildRule(
          targetGraph,
          params.withAppendedFlavor(cxxPlatform.get().getFlavor()),
          resolver,
          cellRoots,
          args);
    }
    return new CxxGenrule(params, resolver, args.getOut());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      CxxGenruleDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Add in all parse time deps from the C/C++ platforms.
    for (CxxPlatform cxxPlatform : cxxPlatforms.getValues()) {
      extraDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatform));
    }

    // Add in parse time deps from parent.
    super.findDepsForTargetFromConstructorArgs(
        buildTarget, cellRoots, constructorArg, extraDepsBuilder, targetGraphOnlyDepsBuilder);
  }

  private ImmutableMap<String, MacroReplacer> getMacroReplacersForTargetTranslation(
      BuildTarget target, CellPathResolver cellNames, TargetNodeTranslator translator) {
    BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
        BuildTargetPatternParser.forBaseName(target.getBaseName());

    ImmutableMap.Builder<String, MacroReplacer> macros = ImmutableMap.builder();

    ImmutableList.of("exe", "location", "location-platform", "cppflags", "cxxppflags", "solibs")
        .forEach(
            name ->
                macros.put(
                    name,
                    new TargetTranslatorMacroReplacer(
                        new AsIsMacroReplacer(name),
                        Filter.NONE,
                        buildTargetPatternParser,
                        cellNames,
                        translator)));

    ImmutableList.of("platform-name", "cc", "cflags", "cxx", "cxxflags", "ld")
        .forEach(name -> macros.put(name, new AsIsMacroReplacer(name)));

    for (Linker.LinkableDepType style : Linker.LinkableDepType.values()) {
      for (Filter filter : Filter.values()) {
        String name =
            String.format(
                "ldflags-%s%s",
                CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, style.toString()),
                filter == Filter.PARAM ? "-filter" : "");
        macros.put(
            name,
            new TargetTranslatorMacroReplacer(
                new AsIsMacroReplacer(name),
                filter,
                buildTargetPatternParser,
                cellNames,
                translator));
      }
    }
    return macros.build();
  }

  private String translateCmd(
      BuildTarget root,
      CellPathResolver cellNames,
      TargetNodeTranslator translator,
      String field,
      String cmd) {
    try {
      return MACRO_FINDER.replace(
          getMacroReplacersForTargetTranslation(root, cellNames, translator), cmd, false);
    } catch (MacroException e) {
      throw new HumanReadableException(
          e, "%s: \"%s\": error expanding macros: %s", root, field, e.getMessage());
    }
  }

  @Override
  public Optional<CxxGenruleDescriptionArg> translateConstructorArg(
      BuildTarget target,
      CellPathResolver cellNames,
      TargetNodeTranslator translator,
      CxxGenruleDescriptionArg constructorArg) {
    CxxGenruleDescriptionArg.Builder newConstructorArgBuilder = CxxGenruleDescriptionArg.builder();
    translator.translateConstructorArg(
        cellNames,
        BuildTargetPatternParser.forBaseName(target.getBaseName()),
        constructorArg,
        newConstructorArgBuilder);
    CxxGenruleDescriptionArg newIntermediate = newConstructorArgBuilder.build();
    newConstructorArgBuilder.setCmd(
        newIntermediate.getCmd().map(c -> translateCmd(target, cellNames, translator, "cmd", c)));
    newConstructorArgBuilder.setBash(
        newIntermediate.getBash().map(c -> translateCmd(target, cellNames, translator, "bash", c)));
    newConstructorArgBuilder.setCmdExe(
        newIntermediate
            .getCmdExe()
            .map(c -> translateCmd(target, cellNames, translator, "cmd_exe", c)));
    return Optional.of(newConstructorArgBuilder.build());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractCxxGenruleDescriptionArg extends AbstractGenruleDescription.CommonArg {}

  /**
   * A build target macro expander just used at parse time to extract deps from the preprocessor
   * flag macros.
   */
  private static class ParseTimeDepsExpander extends FilterAndTargetsExpander {

    public ParseTimeDepsExpander(Filter filter) {
      super(filter);
    }

    @Override
    public Class<FilterAndTargets> getInputClass() {
      return FilterAndTargets.class;
    }

    @Override
    protected String expand(
        BuildRuleResolver resolver, ImmutableList<BuildRule> rule, Optional<Pattern> filter)
        throws MacroException {
      throw new IllegalStateException();
    }
  }

  /** A macro expander that expands to a specific {@link Tool}. */
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
        ImmutableList<String> input)
        throws MacroException {
      SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
      return shquoteJoin(tool.getCommandPrefix(pathResolver));
    }

    @Override
    public ImmutableList<BuildRule> extractBuildTimeDeps(
        BuildTarget target,
        CellPathResolver cellNames,
        BuildRuleResolver resolver,
        ImmutableList<String> input)
        throws MacroException {
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
      return ImmutableList.copyOf(tool.getDeps(ruleFinder));
    }

    @Override
    public void extractParseTimeDeps(
        BuildTarget target,
        CellPathResolver cellNames,
        ImmutableList<String> input,
        ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
        ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder)
        throws MacroException {
      // We already return all platform-specific parse-time deps from
      // `findDepsForTargetFromConstructorArgs`.
    }

    @Override
    public Object extractRuleKeyAppendables(
        BuildTarget target,
        CellPathResolver cellNames,
        BuildRuleResolver resolver,
        ImmutableList<String> input)
        throws MacroException {
      return tool;
    }
  }

  private abstract static class FilterAndTargetsExpander
      extends AbstractMacroExpander<FilterAndTargets> {

    private final Filter filter;

    public FilterAndTargetsExpander(Filter filter) {
      this.filter = filter;
    }

    @Override
    protected final FilterAndTargets parse(
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

      return new FilterAndTargets(filter, targets.build());
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

    protected abstract String expand(
        BuildRuleResolver resolver, ImmutableList<BuildRule> rules, Optional<Pattern> filter)
        throws MacroException;

    @Override
    public String expandFrom(
        BuildTarget target,
        CellPathResolver cellNames,
        BuildRuleResolver resolver,
        FilterAndTargets input)
        throws MacroException {
      return expand(resolver, resolve(resolver, input.targets), input.filter);
    }

    protected ImmutableList<BuildRule> extractBuildTimeDeps(
        @SuppressWarnings("unused") BuildRuleResolver resolver,
        ImmutableList<BuildRule> rules,
        @SuppressWarnings("unused") Optional<Pattern> filter)
        throws MacroException {
      return rules;
    }

    @Override
    public ImmutableList<BuildRule> extractBuildTimeDepsFrom(
        BuildTarget target,
        CellPathResolver cellNames,
        BuildRuleResolver resolver,
        FilterAndTargets input)
        throws MacroException {
      return extractBuildTimeDeps(resolver, resolve(resolver, input.targets), input.filter);
    }

    @Override
    public void extractParseTimeDepsFrom(
        BuildTarget target,
        CellPathResolver cellNames,
        FilterAndTargets input,
        ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
        ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
      buildDepsBuilder.addAll(input.targets);
    }

    @Override
    public Object extractRuleKeyAppendablesFrom(
        BuildTarget target,
        CellPathResolver cellNames,
        BuildRuleResolver resolver,
        FilterAndTargets input)
        throws MacroException {
      return input
          .targets
          .stream()
          .map(DefaultBuildTargetSourcePath::new)
          .collect(MoreCollectors.toImmutableSortedSet(Ordering.natural()));
    }
  }

  /**
   * A build target expander that replaces lists of build target with their transitive preprocessor
   * input.
   */
  private static class CxxPreprocessorFlagsExpander extends FilterAndTargetsExpander {

    private final CxxPlatform cxxPlatform;
    private final CxxSource.Type sourceType;

    public CxxPreprocessorFlagsExpander(CxxPlatform cxxPlatform, CxxSource.Type sourceType) {
      super(Filter.NONE);
      this.cxxPlatform = cxxPlatform;
      this.sourceType = sourceType;
    }

    @Override
    public Class<FilterAndTargets> getInputClass() {
      return FilterAndTargets.class;
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
    private Collection<CxxPreprocessorInput> getCxxPreprocessorInput(ImmutableList<BuildRule> rules)
        throws MacroException {
      try {
        return CxxPreprocessables.getTransitiveCxxPreprocessorInput(cxxPlatform, rules);
      } catch (NoSuchBuildTargetException e) {
        throw new MacroException(
            String.format("failed getting preprocessor input: %s", e.getMessage()), e);
      }
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
    protected String expand(
        BuildRuleResolver resolver, ImmutableList<BuildRule> rules, Optional<Pattern> filter)
        throws MacroException {
      SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
      PreprocessorFlags ppFlags = getPreprocessorFlags(getCxxPreprocessorInput(rules));
      Preprocessor preprocessor =
          CxxSourceTypes.getPreprocessor(cxxPlatform, sourceType).resolve(resolver);
      CxxToolFlags flags =
          ppFlags.toToolFlags(
              pathResolver,
              PathShortener.identity(),
              CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, pathResolver),
              preprocessor,
              /* pch */ Optional.empty());
      return Joiner.on(' ').join(Iterables.transform(flags.getAllFlags(), Escaper.SHELL_ESCAPER));
    }

    @Override
    protected ImmutableList<BuildRule> extractBuildTimeDeps(
        BuildRuleResolver resolver, ImmutableList<BuildRule> rules, Optional<Pattern> filter)
        throws MacroException {
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
      ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
      for (CxxPreprocessorInput input : getCxxPreprocessorInput(rules)) {
        deps.addAll(input.getDeps(resolver, ruleFinder));
      }
      return deps.build();
    }

    @Override
    public Object extractRuleKeyAppendablesFrom(
        final BuildTarget target,
        final CellPathResolver cellNames,
        final BuildRuleResolver resolver,
        FilterAndTargets input)
        throws MacroException {
      final Iterable<CxxPreprocessorInput> transitivePreprocessorInput =
          getCxxPreprocessorInput(resolve(resolver, input.targets));
      final PreprocessorFlags ppFlags = getPreprocessorFlags(transitivePreprocessorInput);
      return (RuleKeyAppendable)
          sink -> {
            ppFlags.appendToRuleKey(sink, cxxPlatform.getCompilerDebugPathSanitizer());
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
  private static class CxxLinkerFlagsExpander extends FilterAndTargetsExpander {

    private final BuildTarget buildTarget;
    private final ProjectFilesystem filesystem;
    private final CxxPlatform cxxPlatform;
    private final Linker.LinkableDepType depType;
    private final String out;

    public CxxLinkerFlagsExpander(
        BuildTarget buildTarget,
        ProjectFilesystem filesystem,
        CxxPlatform cxxPlatform,
        Linker.LinkableDepType depType,
        String out,
        Filter filter) {
      super(filter);
      this.buildTarget = buildTarget;
      this.filesystem = filesystem;
      this.cxxPlatform = cxxPlatform;
      this.depType = depType;
      this.out = out;
    }

    @Override
    public Class<FilterAndTargets> getInputClass() {
      return FilterAndTargets.class;
    }

    /**
     * @return a {@link SymlinkTree} containing all the transitive shared libraries from the given
     *     roots linked in by their library name.
     */
    private SymlinkTree requireSymlinkTree(
        BuildRuleResolver resolver, ImmutableList<BuildRule> rules) throws MacroException {
      BuildTarget symlinkTreeTarget =
          CxxDescriptionEnhancer.createSharedLibrarySymlinkTreeTarget(
              buildTarget, cxxPlatform.getFlavor());
      SymlinkTree symlinkTree =
          resolver.getRuleOptionalWithType(symlinkTreeTarget, SymlinkTree.class).orElse(null);
      if (symlinkTree == null) {
        try {
          symlinkTree =
              resolver.addToIndex(
                  CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
                      new SourcePathRuleFinder(resolver),
                      buildTarget,
                      filesystem,
                      cxxPlatform,
                      rules,
                      NativeLinkable.class::isInstance));
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
     *     that linked binaries can find their shared library dependencies at runtime.
     */
    private ImmutableList<com.facebook.buck.rules.args.Arg> getSharedLinkArgs(
        BuildRuleResolver resolver, ImmutableList<BuildRule> rules) throws MacroException {

      // Embed a origin-relative library path into the binary so it can find the shared libraries.
      // The shared libraries root is absolute. Also need an absolute path to the linkOutput
      Path linkOutput = BuildTargets.getGenPath(filesystem, buildTarget, "%s").resolve(out);
      Path absLinkOut = buildTarget.getCellPath().resolve(linkOutput);
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

    private NativeLinkableInput getNativeLinkableInput(
        Iterable<BuildRule> rules, final Optional<Pattern> filter) throws MacroException {
      try {
        ImmutableMap<BuildTarget, NativeLinkable> nativeLinkables =
            NativeLinkables.getNativeLinkables(
                cxxPlatform,
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
        for (NativeLinkable nativeLinkable : nativeLinkables.values()) {
          nativeLinkableInputs.add(
              NativeLinkables.getNativeLinkableInput(cxxPlatform, depType, nativeLinkable));
        }
        return NativeLinkableInput.concat(nativeLinkableInputs.build());
      } catch (NoSuchBuildTargetException e) {
        throw new MacroException(
            String.format("failed getting native linker args: %s", e.getMessage()), e);
      }
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
    private ImmutableList<com.facebook.buck.rules.args.Arg> getLinkerArgs(
        BuildRuleResolver resolver, ImmutableList<BuildRule> rules, Optional<Pattern> filter)
        throws MacroException {
      ImmutableList.Builder<com.facebook.buck.rules.args.Arg> args = ImmutableList.builder();
      args.addAll(StringArg.from(cxxPlatform.getLdflags()));
      if (depType == Linker.LinkableDepType.SHARED) {
        args.addAll(getSharedLinkArgs(resolver, rules));
      }
      args.addAll(getNativeLinkableInput(rules, filter).getArgs());
      return args.build();
    }

    /**
     * Expand the native linkable input for the given rules into a shell-escaped string containing
     * all linker flags.
     */
    @Override
    public String expand(
        BuildRuleResolver resolver, ImmutableList<BuildRule> rules, Optional<Pattern> filter)
        throws MacroException {
      return shquoteJoin(
          com.facebook.buck.rules.args.Arg.stringify(
              getLinkerArgs(resolver, rules, filter),
              new SourcePathResolver(new SourcePathRuleFinder(resolver))));
    }

    @Override
    protected ImmutableList<BuildRule> extractBuildTimeDeps(
        BuildRuleResolver resolver, ImmutableList<BuildRule> rules, Optional<Pattern> filter)
        throws MacroException {
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
      ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
      deps.addAll(
          getLinkerArgs(resolver, rules, filter)
              .stream()
              .flatMap(arg -> arg.getDeps(ruleFinder).stream())
              .iterator());
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
        FilterAndTargets inputs)
        throws MacroException {
      return getLinkerArgs(resolver, resolve(resolver, inputs.targets), inputs.filter);
    }
  }

  private static class CxxPlatformParseTimeDepsExpander extends StringExpander {

    private final FlavorDomain<CxxPlatform> cxxPlatforms;

    public CxxPlatformParseTimeDepsExpander(FlavorDomain<CxxPlatform> cxxPlatforms) {
      super("");
      this.cxxPlatforms = cxxPlatforms;
    }

    @Override
    public void extractParseTimeDeps(
        BuildTarget target,
        CellPathResolver cellNames,
        ImmutableList<String> input,
        ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
        ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder)
        throws MacroException {
      Optional<CxxPlatform> platform = cxxPlatforms.getValue(target.getFlavors());
      if (platform.isPresent()) {
        buildDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(platform.get()));
      }
    }
  }

  private static class FilterAndTargets {

    public final Optional<Pattern> filter;
    public final ImmutableList<BuildTarget> targets;

    public FilterAndTargets(Optional<Pattern> filter, ImmutableList<BuildTarget> targets) {
      this.filter = filter;
      this.targets = targets;
    }
  }

  private static class AsIsMacroReplacer implements MacroReplacer {

    private final String name;

    private AsIsMacroReplacer(String name) {
      this.name = name;
    }

    @Override
    public String replace(ImmutableList<String> args) throws MacroException {
      return String.format("$(%s %s)", name, Joiner.on(' ').join(args));
    }
  }

  private static class TargetTranslatorMacroReplacer implements MacroReplacer {

    private final AsIsMacroReplacer asIsMacroReplacer;
    private final Filter filter;
    private final BuildTargetPatternParser<BuildTargetPattern> buildTargetBuildTargetParser;
    private final CellPathResolver cellNames;
    private final TargetNodeTranslator translator;

    private TargetTranslatorMacroReplacer(
        AsIsMacroReplacer asIsMacroReplacer,
        Filter filter,
        BuildTargetPatternParser<BuildTargetPattern> buildTargetBuildTargetParser,
        CellPathResolver cellNames,
        TargetNodeTranslator translator) {
      this.asIsMacroReplacer = asIsMacroReplacer;
      this.filter = filter;
      this.buildTargetBuildTargetParser = buildTargetBuildTargetParser;
      this.cellNames = cellNames;
      this.translator = translator;
    }

    private BuildTarget parse(String input) throws MacroException {
      try {
        return BuildTargetParser.INSTANCE.parse(input, buildTargetBuildTargetParser, cellNames);
      } catch (BuildTargetParseException e) {
        throw new MacroException(e.getMessage(), e);
      }
    }

    @Override
    public String replace(ImmutableList<String> args) throws MacroException {
      ImmutableList.Builder<String> strings = ImmutableList.builder();

      if (filter == Filter.PARAM) {
        strings.add(args.get(0));
      }

      for (String arg : args.subList(filter == Filter.PARAM ? 1 : 0, args.size())) {
        BuildTarget target = parse(arg);
        strings.add(
            translator
                .translate(cellNames, buildTargetBuildTargetParser, target)
                .orElse(target)
                .getFullyQualifiedName());
      }

      return asIsMacroReplacer.replace(strings.build());
    }
  }

  private enum Filter {
    NONE,
    PARAM,
  }
}
