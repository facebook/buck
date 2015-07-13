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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.rules.coercer.SourceWithFlagsList;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CxxDescriptionEnhancer {

  private static final Logger LOG = Logger.get(CxxDescriptionEnhancer.class);

  public static final Flavor HEADER_SYMLINK_TREE_FLAVOR = ImmutableFlavor.of("header-symlink-tree");
  public static final Flavor EXPORTED_HEADER_SYMLINK_TREE_FLAVOR =
      ImmutableFlavor.of("exported-header-symlink-tree");
  public static final Flavor STATIC_FLAVOR = ImmutableFlavor.of("static");
  public static final Flavor STATIC_PIC_FLAVOR = ImmutableFlavor.of("static-pic");
  public static final Flavor SHARED_FLAVOR = ImmutableFlavor.of("shared");
  public static final Flavor MACH_O_BUNDLE_FLAVOR = ImmutableFlavor.of("mach-o-bundle");
  public static final Flavor SHARED_LIBRARY_SYMLINK_TREE_FLAVOR =
      ImmutableFlavor.of("shared-library-symlink-tree");

  public static final Flavor CXX_LINK_BINARY_FLAVOR = ImmutableFlavor.of("binary");
  public static final Flavor LEX_YACC_SOURCE_FLAVOR = ImmutableFlavor.of("lex_yacc_sources");

  private CxxDescriptionEnhancer() {}

  private static BuildTarget createLexYaccSourcesBuildTarget(BuildTarget target) {
    return BuildTarget.builder(target).addFlavors(LEX_YACC_SOURCE_FLAVOR).build();
  }

  public static CxxHeaderSourceSpec requireLexYaccSources(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources) {
    BuildTarget lexYaccTarget = createLexYaccSourcesBuildTarget(params.getBuildTarget());

    // Check the cache...
    Optional<BuildRule> rule = ruleResolver.getRuleOptional(lexYaccTarget);
    if (rule.isPresent()) {
      @SuppressWarnings("unchecked")
      ContainerBuildRule<CxxHeaderSourceSpec> containerRule =
          (ContainerBuildRule<CxxHeaderSourceSpec>) rule.get();
      return containerRule.get();
    }

    // Setup the rules to run lex/yacc.
    CxxHeaderSourceSpec lexYaccSources =
        CxxDescriptionEnhancer.createLexYaccBuildRules(
            params,
            ruleResolver,
            cxxPlatform,
            ImmutableList.<String>of(),
            lexSources,
            ImmutableList.<String>of(),
            yaccSources);

    ruleResolver.addToIndex(
        ContainerBuildRule.of(
            params,
            pathResolver,
            lexYaccTarget,
            lexYaccSources));

    return lexYaccSources;
  }

  public static SymlinkTree createHeaderSymlinkTree(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      boolean includeLexYaccHeaders,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMap<Path, SourcePath> headers,
      HeaderVisibility headerVisibility) {

    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor(),
            headerVisibility);
    Path headerSymlinkTreeRoot =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            params.getBuildTarget(),
            cxxPlatform.getFlavor(),
            headerVisibility);

    CxxHeaderSourceSpec lexYaccSources;
    if (includeLexYaccHeaders) {
      lexYaccSources = requireLexYaccSources(
          params,
          ruleResolver,
          pathResolver,
          cxxPlatform,
          lexSources,
          yaccSources);
    } else {
      lexYaccSources = CxxHeaderSourceSpec.builder().build();
    }

    return CxxPreprocessables.createHeaderSymlinkTreeBuildRule(
        pathResolver,
        headerSymlinkTreeTarget,
        params,
        headerSymlinkTreeRoot,
        ImmutableMap.<Path, SourcePath>builder()
            .putAll(headers)
            .putAll(lexYaccSources.getCxxHeaders())
            .build());
  }

  public static SymlinkTree requireHeaderSymlinkTree(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      boolean includeLexYaccHeaders,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMap<Path, SourcePath> headers,
      HeaderVisibility headerVisibility) {
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor(),
            headerVisibility);

    // Check the cache...
    Optional<BuildRule> rule = ruleResolver.getRuleOptional(headerSymlinkTreeTarget);
    if (rule.isPresent()) {
      Preconditions.checkState(rule.get() instanceof SymlinkTree);
      return (SymlinkTree) rule.get();
    }

    SymlinkTree symlinkTree = createHeaderSymlinkTree(
        params,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        includeLexYaccHeaders,
        lexSources,
        yaccSources,
        headers,
        headerVisibility);

    ruleResolver.addToIndex(symlinkTree);

    return symlinkTree;
  }

  /**
   * @return the {@link BuildTarget} to use for the {@link BuildRule} generating the
   *    symlink tree of headers.
   */
  public static BuildTarget createHeaderSymlinkTreeTarget(
      BuildTarget target,
      Flavor platform,
      HeaderVisibility headerVisibility) {
    return BuildTarget
        .builder(target)
        .addFlavors(platform)
        .addFlavors(getHeaderSymlinkTreeFlavor(headerVisibility))
        .build();
  }

  /**
   * @return the {@link Path} to use for the symlink tree of headers.
   */
  public static Path getHeaderSymlinkTreePath(
      BuildTarget target,
      Flavor platform,
      HeaderVisibility headerVisibility) {
    return BuildTargets.getGenPath(
        createHeaderSymlinkTreeTarget(target, platform, headerVisibility),
        "%s");
  }

  public static Flavor getHeaderSymlinkTreeFlavor(HeaderVisibility headerVisibility) {
    switch (headerVisibility) {
      case PUBLIC:
        return EXPORTED_HEADER_SYMLINK_TREE_FLAVOR;
      case PRIVATE:
        return HEADER_SYMLINK_TREE_FLAVOR;
      default:
        throw new RuntimeException("Unexpected value of enum ExportMode");
    }
  }

  /**
   * @return a map of header locations to input {@link SourcePath} objects formed by parsing the
   *    input {@link SourcePath} objects for the "headers" parameter.
   */
  public static ImmutableMap<Path, SourcePath> parseHeaders(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args) {
    ImmutableMap.Builder<String, SourcePath> headers = ImmutableMap.builder();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    putAllHeaders(args.headers.get(), headers, pathResolver, "headers", params.getBuildTarget());
    for (SourceList sourceList :
        args.platformHeaders.get().getMatchingValues(cxxPlatform.getFlavor().toString())) {
      putAllHeaders(
          sourceList,
          headers,
          pathResolver,
          "platform_headers",
          params.getBuildTarget());
    }
    return CxxPreprocessables.resolveHeaderMap(
        args.headerNamespace.transform(MorePaths.TO_PATH)
            .or(params.getBuildTarget().getBasePath()),
        headers.build());
  }

  /**
   * @return a map of header locations to input {@link SourcePath} objects formed by parsing the
   *    input {@link SourcePath} objects for the "exportedHeaders" parameter.
   */
  public static ImmutableMap<Path, SourcePath> parseExportedHeaders(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxLibraryDescription.Arg args) {
    ImmutableMap.Builder<String, SourcePath> headers = ImmutableMap.builder();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    putAllHeaders(
        args.exportedHeaders.get(),
        headers,
        pathResolver,
        "exported_headers",
        params.getBuildTarget());
    for (SourceList sourceList :
        args.exportedPlatformHeaders.get().getMatchingValues(cxxPlatform.getFlavor().toString())) {
      putAllHeaders(
          sourceList,
          headers,
          pathResolver,
          "exported_platform_headers",
          params.getBuildTarget());
    }
    return CxxPreprocessables.resolveHeaderMap(
        args.headerNamespace.transform(MorePaths.TO_PATH)
            .or(params.getBuildTarget().getBasePath()),
        headers.build());
  }

  /**
   * Resolves the headers in `sourceList` and puts them into `sources` for the specificed
   * `buildTarget`.
   */
  public static void putAllHeaders(
      SourceList sourceList,
      ImmutableMap.Builder<String, SourcePath> sources,
      SourcePathResolver pathResolver,
      String parameterName,
      BuildTarget buildTarget) {
    switch (sourceList.getType()) {
      case NAMED:
        sources.putAll(sourceList.getNamedSources().get());
        break;
      case UNNAMED:
        sources.putAll(
            pathResolver.getSourcePathNames(
                buildTarget,
                parameterName,
                sourceList.getUnnamedSources().get()));
        break;
    }
  }

  /**
   * @return a list {@link CxxSource} objects formed by parsing the input {@link SourcePath}
   *    objects for the "srcs" parameter.
   */
  public static ImmutableMap<String, CxxSource> parseCxxSources(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args) {
    ImmutableMap.Builder<String, SourceWithFlags> sources = ImmutableMap.builder();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    putAllSources(args.srcs.get(), sources, pathResolver, params.getBuildTarget());
    for (SourceWithFlagsList sourceWithFlagsList :
        args.platformSrcs.get().getMatchingValues(cxxPlatform.getFlavor().toString())) {
      putAllSources(sourceWithFlagsList, sources, pathResolver, params.getBuildTarget());
    }
    return CxxCompilableEnhancer.resolveCxxSources(sources.build());
  }

  private static void putAllSources(
      SourceWithFlagsList sourceWithFlagsList,
      ImmutableMap.Builder<String, SourceWithFlags> sources,
      SourcePathResolver pathResolver,
      BuildTarget buildTarget) {
    switch (sourceWithFlagsList.getType()) {
      case NAMED:
        sources.putAll(sourceWithFlagsList.getNamedSources().get());
        break;
      case UNNAMED:
        sources.putAll(
            pathResolver.getSourcePathNames(
                buildTarget,
                "srcs",
                sourceWithFlagsList.getUnnamedSources().get(),
                SourceWithFlags.TO_SOURCE_PATH));
        break;
    }
  }

  public static ImmutableMap<String, SourcePath> parseLexSources(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxConstructorArg args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return pathResolver.getSourcePathNames(
        params.getBuildTarget(),
        "lexSrcs",
        args.lexSrcs.or(ImmutableList.<SourcePath>of()));
  }

  public static ImmutableMap<String, SourcePath> parseYaccSources(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxConstructorArg args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return pathResolver.getSourcePathNames(
        params.getBuildTarget(),
        "yaccSrcs",
        args.yaccSrcs.or(ImmutableList.<SourcePath>of()));
  }

  @VisibleForTesting
  protected static BuildTarget createLexBuildTarget(BuildTarget target, String name) {
    return BuildTarget
        .builder(target.getUnflavoredBuildTarget())
        .addFlavors(
            ImmutableFlavor.of(
                String.format(
                    "lex-%s",
                    name.replace('/', '-').replace('.', '-').replace('+', '-').replace(' ', '-'))))
        .build();
  }

  @VisibleForTesting
  protected static BuildTarget createYaccBuildTarget(BuildTarget target, String name) {
    return BuildTarget
        .builder(target.getUnflavoredBuildTarget())
        .addFlavors(
            ImmutableFlavor.of(
                String.format(
                    "yacc-%s",
                    name.replace('/', '-').replace('.', '-').replace('+', '-').replace(' ', '-'))))
        .build();
  }

  /**
   * @return the output path prefix to use for yacc generated files.
   */
  @VisibleForTesting
  protected static Path getYaccOutputPrefix(BuildTarget target, String name) {
    BuildTarget flavoredTarget = createYaccBuildTarget(target, name);
    return BuildTargets.getGenPath(flavoredTarget, "%s/" + name);
  }

  /**
   * @return the output path to use for the lex generated C/C++ source.
   */
  @VisibleForTesting
  protected static Path getLexSourceOutputPath(BuildTarget target, String name) {
    BuildTarget flavoredTarget = createLexBuildTarget(target, name);
    return BuildTargets.getGenPath(flavoredTarget, "%s/" + name + ".cc");
  }

  /**
   * @return the output path to use for the lex generated C/C++ header.
   */
  @VisibleForTesting
  protected static Path getLexHeaderOutputPath(BuildTarget target, String name) {
    BuildTarget flavoredTarget = createLexBuildTarget(target, name);
    return BuildTargets.getGenPath(flavoredTarget, "%s/" + name + ".h");
  }

  /**
   * Generate {@link Lex} and {@link Yacc} rules generating C/C++ sources from the
   * given lex/yacc sources.
   *
   * @return {@link CxxHeaderSourceSpec} containing the generated headers/sources
   */
  public static CxxHeaderSourceSpec createLexYaccBuildRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      ImmutableList<String> lexFlags,
      ImmutableMap<String, SourcePath> lexSrcs,
      ImmutableList<String> yaccFlags,
      ImmutableMap<String, SourcePath> yaccSrcs) {
    if (!lexSrcs.isEmpty() && !cxxPlatform.getLex().isPresent()) {
      throw new HumanReadableException(
          "Platform %s must support lex to compile srcs %s",
          cxxPlatform,
          lexSrcs);
    }

    if (!yaccSrcs.isEmpty() && !cxxPlatform.getYacc().isPresent()) {
      throw new HumanReadableException(
          "Platform %s must support yacc to compile srcs %s",
          cxxPlatform,
          yaccSrcs);
    }

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableMap.Builder<String, CxxSource> lexYaccCxxSourcesBuilder = ImmutableMap.builder();
    ImmutableMap.Builder<Path, SourcePath> lexYaccHeadersBuilder = ImmutableMap.builder();

    // Loop over all lex sources, generating build rule for each one and adding the sources
    // and headers it generates to our bookkeeping maps.
    for (ImmutableMap.Entry<String, SourcePath> ent : lexSrcs.entrySet()) {
      final String name = ent.getKey();
      final SourcePath source = ent.getValue();

      BuildTarget target = createLexBuildTarget(params.getBuildTarget(), name);
      Path outputSource = getLexSourceOutputPath(target, name);
      Path outputHeader = getLexHeaderOutputPath(target, name);

      // Create the build rule to run lex on this source and add it to the resolver.
      Lex lex = new Lex(
          params.copyWithChanges(
              target,
              Suppliers.ofInstance(
                  ImmutableSortedSet.copyOf(
                      pathResolver.filterBuildRuleInputs(ImmutableList.of(source)))),
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
          pathResolver,
          cxxPlatform.getLex().get(),
          ImmutableList.<String>builder()
              .addAll(cxxPlatform.getLexFlags())
              .addAll(lexFlags)
              .build(),
          outputSource,
          outputHeader,
          source);
      resolver.addToIndex(lex);

      // Record the output source and header as {@link BuildRuleSourcePath} objects.
      lexYaccCxxSourcesBuilder.put(
          name + ".cc",
          CxxSource.of(
              CxxSource.Type.CXX,
              new BuildTargetSourcePath(lex.getBuildTarget(), outputSource),
              ImmutableList.<String>of()));
      lexYaccHeadersBuilder.put(
          params.getBuildTarget().getBasePath().resolve(name + ".h"),
          new BuildTargetSourcePath(lex.getBuildTarget(), outputHeader));
    }

    // Loop over all yaccc sources, generating build rule for each one and adding the sources
    // and headers it generates to our bookkeeping maps.
    for (ImmutableMap.Entry<String, SourcePath> ent : yaccSrcs.entrySet()) {
      final String name = ent.getKey();
      final SourcePath source = ent.getValue();

      BuildTarget target = createYaccBuildTarget(params.getBuildTarget(), name);
      Path outputPrefix = getYaccOutputPrefix(target, Files.getNameWithoutExtension(name));

      // Create the build rule to run yacc on this source and add it to the resolver.
      Yacc yacc = new Yacc(
          params.copyWithChanges(
              target,
              Suppliers.ofInstance(
                  ImmutableSortedSet.copyOf(
                      pathResolver.filterBuildRuleInputs(ImmutableList.of(source)))),
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
          pathResolver,
          cxxPlatform.getYacc().get(),
          ImmutableList.<String>builder()
              .addAll(cxxPlatform.getYaccFlags())
              .addAll(yaccFlags)
              .build(),
          outputPrefix,
          source);
      resolver.addToIndex(yacc);

      // Record the output source and header as {@link BuildRuleSourcePath} objects.
      lexYaccCxxSourcesBuilder.put(
          name + ".cc",
          CxxSource.of(
              CxxSource.Type.CXX,
              new BuildTargetSourcePath(
                  yacc.getBuildTarget(),
                  Yacc.getSourceOutputPath(outputPrefix)),
              ImmutableList.<String>of()));

      lexYaccHeadersBuilder.put(
          params.getBuildTarget().getBasePath().resolve(name + ".h"),
          new BuildTargetSourcePath(
              yacc.getBuildTarget(),
              Yacc.getHeaderOutputPath(outputPrefix)));
    }

    return CxxHeaderSourceSpec.of(
        lexYaccHeadersBuilder.build(),
        lexYaccCxxSourcesBuilder.build());
  }

  public static ImmutableList<CxxPreprocessorInput> collectCxxPreprocessorInput(
      BuildRuleParams params,
      CxxPlatform cxxPlatform,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableList<SourcePath> prefixHeaders,
      ImmutableList<SymlinkTree> headerSymlinkTrees,
      ImmutableSet<Path> frameworkSearchPaths,
      Iterable<CxxPreprocessorInput> cxxPreprocessorInputFromDeps) {

    // Add the private includes of any rules which list this rule as a test.
    BuildTarget targetWithoutFlavor = BuildTarget.of(
        params.getBuildTarget().getUnflavoredBuildTarget());
    ImmutableList.Builder<CxxPreprocessorInput> cxxPreprocessorInputFromTestedRulesBuilder =
        ImmutableList.builder();
    for (BuildRule rule : params.getDeps()) {
      if (rule instanceof NativeTestable) {
        NativeTestable testable = (NativeTestable) rule;
        if (testable.isTestedBy(targetWithoutFlavor)) {
          LOG.debug(
              "Adding private includes of tested rule %s to testing rule %s",
              rule.getBuildTarget(),
              params.getBuildTarget());
          cxxPreprocessorInputFromTestedRulesBuilder.add(
              testable.getCxxPreprocessorInput(
                  cxxPlatform,
                  HeaderVisibility.PRIVATE));
        }
      }
    }

    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInputFromTestedRules =
        cxxPreprocessorInputFromTestedRulesBuilder.build();
    LOG.verbose(
        "Rules tested by target %s added private includes %s",
        params.getBuildTarget(),
        cxxPreprocessorInputFromTestedRules);

    ImmutableMap.Builder<Path, SourcePath> allLinks = ImmutableMap.builder();
    ImmutableMap.Builder<Path, SourcePath> allFullLinks = ImmutableMap.builder();
    ImmutableList.Builder<Path> allIncludeRoots = ImmutableList.builder();
    for (SymlinkTree headerSymlinkTree : headerSymlinkTrees) {
      allLinks.putAll(headerSymlinkTree.getLinks());
      allFullLinks.putAll(headerSymlinkTree.getFullLinks());
      allIncludeRoots.add(headerSymlinkTree.getRoot());
    }

    CxxPreprocessorInput localPreprocessorInput =
        CxxPreprocessorInput.builder()
            .addAllRules(Iterables.transform(headerSymlinkTrees, HasBuildTarget.TO_TARGET))
            .putAllPreprocessorFlags(preprocessorFlags)
            .setIncludes(
                CxxHeaders.builder()
                    .addAllPrefixHeaders(prefixHeaders)
                    .putAllNameToPathMap(allLinks.build())
                    .putAllFullNameToPathMap(allFullLinks.build())
                    .build())
            .addAllIncludeRoots(allIncludeRoots.build())
            .addAllFrameworkRoots(frameworkSearchPaths)
            .build();

    return ImmutableList.<CxxPreprocessorInput>builder()
        .add(localPreprocessorInput)
        .addAll(cxxPreprocessorInputFromDeps)
        .addAll(cxxPreprocessorInputFromTestedRules)
        .build();
  }

  public static BuildTarget createStaticLibraryBuildTarget(
      BuildTarget target,
      Flavor platform,
      CxxSourceRuleFactory.PicType pic) {
    return BuildTarget.builder(target)
        .addFlavors(platform)
        .addFlavors(pic == CxxSourceRuleFactory.PicType.PDC ? STATIC_FLAVOR : STATIC_PIC_FLAVOR)
        .build();
  }

  public static BuildTarget createSharedLibraryBuildTarget(
      BuildTarget target,
      Flavor platform) {
    return BuildTarget.builder(target).addFlavors(platform).addFlavors(SHARED_FLAVOR).build();
  }

  public static Path getStaticLibraryPath(
      BuildTarget target,
      Flavor platform,
      CxxSourceRuleFactory.PicType pic) {
    String name = String.format("lib%s.a", target.getShortName());
    return BuildTargets.getGenPath(createStaticLibraryBuildTarget(target, platform, pic), "%s")
        .resolve(name);
  }

  public static String getDefaultSharedLibrarySoname(BuildTarget target, CxxPlatform platform) {
    String libName =
        Joiner.on('_').join(
            ImmutableList.builder()
                .addAll(
                    FluentIterable.from(target.getBasePath())
                        .transform(Functions.toStringFunction())
                        .filter(Predicates.not(Predicates.equalTo(""))))
                .add(
                    target
                        .withoutFlavors(ImmutableSet.of(platform.getFlavor()))
                        .getShortNameAndFlavorPostfix())
                .build());
    String extension = platform.getSharedLibraryExtension();
    return String.format("lib%s.%s", libName, extension);
  }

  public static Path getSharedLibraryPath(
      BuildTarget target,
      String soname,
      CxxPlatform platform) {
    return BuildTargets.getGenPath(
        createSharedLibraryBuildTarget(target, platform.getFlavor()),
        "%s/" + soname);
  }

  @VisibleForTesting
  protected static Path getOutputPath(BuildTarget target) {
    return BuildTargets.getGenPath(target, "%s/" + target.getShortNameAndFlavorPostfix());
  }

  @VisibleForTesting
  protected static BuildTarget createCxxLinkTarget(BuildTarget target) {
    return BuildTarget.builder(target).addFlavors(CXX_LINK_BINARY_FLAVOR).build();
  }

  /**
   * @return the framework search paths with any embedded macros expanded.
   */
  static ImmutableSet<Path> getFrameworkSearchPaths(
      Optional<ImmutableSet<Path>> frameworkSearchPaths,
      CxxPlatform cxxPlatform) {
    return FluentIterable.from(frameworkSearchPaths.or(ImmutableSet.<Path>of()))
        .transform(Functions.toStringFunction())
        .transform(CxxFlags.getTranslateMacrosFn(cxxPlatform))
        .transform(MorePaths.TO_PATH)
        .toSet();
  }

  static class CxxLinkAndCompileRules {
    final CxxLink cxxLink;
    final ImmutableSortedSet<CxxPreprocessAndCompile> compileRules;
    CxxLinkAndCompileRules(
        CxxLink cxxLink,
        ImmutableSortedSet<CxxPreprocessAndCompile> compileRules) {
      this.cxxLink = cxxLink;
      this.compileRules = compileRules;
    }
  }

  public static CxxLinkAndCompileRules createBuildRulesForCxxBinaryDescriptionArg(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxBinaryDescription.Arg args,
      CxxPreprocessMode preprocessMode) {

    ImmutableMap<String, CxxSource> srcs = parseCxxSources(params, resolver, cxxPlatform, args);
    ImmutableMap<Path, SourcePath> headers = parseHeaders(params, resolver, cxxPlatform, args);
    ImmutableMap<String, SourcePath> lexSrcs = parseLexSources(params, resolver, args);
    ImmutableMap<String, SourcePath> yaccSrcs = parseYaccSources(params, resolver, args);

    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);

    // Setup the rules to run lex/yacc.
    CxxHeaderSourceSpec lexYaccSources =
        requireLexYaccSources(
            params,
            resolver,
            sourcePathResolver,
            cxxPlatform,
            lexSrcs,
            yaccSrcs);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    SymlinkTree headerSymlinkTree = requireHeaderSymlinkTree(
        params,
        resolver,
        sourcePathResolver,
        cxxPlatform,
        /* includeLexYaccHeaders */ true,
        lexSrcs,
        yaccSrcs,
        headers,
        HeaderVisibility.PRIVATE);
    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput =
        collectCxxPreprocessorInput(
            params,
            cxxPlatform,
            CxxFlags.getLanguageFlags(
                args.preprocessorFlags,
                args.platformPreprocessorFlags,
                args.langPreprocessorFlags,
                cxxPlatform),
            args.prefixHeaders.get(),
            ImmutableList.of(headerSymlinkTree),
            getFrameworkSearchPaths(
                args.frameworkSearchPaths,
                cxxPlatform),
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                cxxPlatform,
                FluentIterable.from(params.getDeps())
                    .filter(Predicates.instanceOf(CxxPreprocessorDep.class))));

    // The complete list of input sources.
    ImmutableMap<String, CxxSource> sources =
        ImmutableMap.<String, CxxSource>builder()
            .putAll(srcs)
            .putAll(lexYaccSources.getCxxSources())
            .build();

    // Generate and add all the build rules to preprocess and compile the source to the
    // resolver and get the `SourcePath`s representing the generated object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        CxxSourceRuleFactory.requirePreprocessAndCompileRules(
            params,
            resolver,
            sourcePathResolver,
            cxxPlatform,
            cxxPreprocessorInput,
            CxxFlags.getFlags(
                args.compilerFlags,
                args.platformCompilerFlags,
                cxxPlatform),
            preprocessMode,
            sources,
            CxxSourceRuleFactory.PicType.PDC);

    // Generate the final link rule.  We use the top-level target as the link rule's
    // target, so that it corresponds to the actual binary we build.
    Path output = getOutputPath(params.getBuildTarget());
    CxxLink cxxLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxPlatform,
        params,
        sourcePathResolver,
        /* extraCxxLdFlags */ ImmutableList.<String>of(),
        /* extraLdFlags */ CxxFlags.getFlags(
            args.linkerFlags,
            args.platformLinkerFlags,
            cxxPlatform),
        createCxxLinkTarget(params.getBuildTarget()),
        Linker.LinkType.EXECUTABLE,
        Optional.<String>absent(),
        output,
        objects.values(),
        (args.linkStyle.or(CxxBinaryDescription.LinkStyle.STATIC) ==
            CxxBinaryDescription.LinkStyle.STATIC)
        ? Linker.LinkableDepType.STATIC
        : Linker.LinkableDepType.SHARED,
        params.getDeps(),
        args.cxxRuntimeType,
        Optional.<SourcePath>absent());
    resolver.addToIndex(cxxLink);

    return new CxxLinkAndCompileRules(cxxLink, ImmutableSortedSet.copyOf(objects.keySet()));
  }

  private static <T> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      TargetNode<T> node,
      Flavor... flavors) {
    BuildTarget target = BuildTarget.builder(params.getBuildTarget()).addFlavors(flavors).build();
    Description<T> description = node.getDescription();
    T args = node.getConstructorArg();
    return description.createBuildRule(
        params.copyWithChanges(
            target,
            Suppliers.ofInstance(params.getDeclaredDeps()),
            Suppliers.ofInstance(params.getExtraDeps())),
        ruleResolver,
        args);
  }

  /**
   * Ensure that the build rule generated by the given {@link BuildRuleParams} had been generated
   * by it's corresponding {@link Description} and added to the {@link BuildRuleResolver}.  If not,
   * call into it's associated {@link Description} to generate it's {@link BuildRule}.
   *
   * @return the {@link BuildRule} generated by the description corresponding to the supplied
   *     {@link BuildRuleParams}.
   */
  public static BuildRule requireBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      Flavor... flavors) {
    BuildTarget target = BuildTarget.builder(params.getBuildTarget()).addFlavors(flavors).build();
    Optional<BuildRule> rule = ruleResolver.getRuleOptional(target);
    if (!rule.isPresent()) {
      TargetNode<?> node = params.getTargetGraph().get(params.getBuildTarget());
      Preconditions.checkNotNull(
          node,
          String.format("%s not in target graph", params.getBuildTarget()));
      rule = Optional.of(createBuildRule(params, ruleResolver, node, flavors));
      ruleResolver.addToIndex(rule.get());
    }
    return rule.get();
  }

  /**
   * @return a {@link Function} object which transforms path names from the output of a compiler
   *     or preprocessor using {@code pathProcessor}.
   */
  public static Function<String, Iterable<String>> createErrorMessagePathProcessor(
      final Function<String, String> pathProcessor) {
    return new Function<String, Iterable<String>>() {

      private final ImmutableList<Pattern> patterns =
          ImmutableList.of(
              Pattern.compile(
                  "(?<=^(?:In file included |\\s+)from )" +
                  "(?<path>[^:]+)" +
                  "(?=[:,](?:\\d+[:,](?:\\d+[:,])?)?$)"),
              Pattern.compile(
                  "^(?<path>[^:]+)(?=:(?:\\d+:(?:\\d+:)?)? )"));

      @Override
      public Iterable<String> apply(String line) {
        for (Pattern pattern : patterns) {
          Matcher m = pattern.matcher(line);
          if (m.find()) {
            return ImmutableList.of(m.replaceAll(pathProcessor.apply(m.group("path"))));
          }
        }
        return ImmutableList.of(line);
      }

    };
  }

  /**
   * @return the {@link BuildTarget} to use for the {@link BuildRule} generating the
   *    symlink tree of shared libraries.
   */
  public static BuildTarget createSharedLibrarySymlinkTreeTarget(
      BuildTarget target,
      Flavor platform) {
    return BuildTarget
        .builder(target)
        .addFlavors(SHARED_LIBRARY_SYMLINK_TREE_FLAVOR)
        .addFlavors(platform)
        .build();
  }

  /**
   * @return the {@link Path} to use for the symlink tree of headers.
   */
  public static Path getSharedLibrarySymlinkTreePath(
      BuildTarget target,
      Flavor platform) {
    return BuildTargets.getGenPath(
        createSharedLibrarySymlinkTreeTarget(target, platform),
        "%s");
  }

  /**
   * Build a {@link SymlinkTree} of all the shared libraries found via the top-level rule's
   * transitive dependencies.
   */
  public static SymlinkTree createSharedLibrarySymlinkTree(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Predicate<Object> traverse) {

    BuildTarget symlinkTreeTarget =
        createSharedLibrarySymlinkTreeTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor());
    Path symlinkTreeRoot =
        getSharedLibrarySymlinkTreePath(
            params.getBuildTarget(),
            cxxPlatform.getFlavor());

    ImmutableSortedMap<String, SourcePath> libraries =
        NativeLinkables.getTransitiveSharedLibraries(
            cxxPlatform,
            params.getDeps(),
            Linker.LinkableDepType.SHARED,
            traverse);

    ImmutableMap.Builder<Path, SourcePath> links = ImmutableMap.builder();
    for (Map.Entry<String, SourcePath> ent : libraries.entrySet()) {
      links.put(Paths.get(ent.getKey()), ent.getValue());
    }
    return new SymlinkTree(
        params.copyWithChanges(
            symlinkTreeTarget,
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        symlinkTreeRoot,
        links.build());
  }

}
