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
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CxxDescriptionEnhancer {

  private static final Logger LOG = Logger.get(CxxDescriptionEnhancer.class);

  public static final Flavor HEADER_SYMLINK_TREE_FLAVOR = ImmutableFlavor.of("private-headers");
  public static final Flavor EXPORTED_HEADER_SYMLINK_TREE_FLAVOR = ImmutableFlavor.of("headers");
  public static final Flavor STATIC_FLAVOR = ImmutableFlavor.of("static");
  public static final Flavor STATIC_PIC_FLAVOR = ImmutableFlavor.of("static-pic");
  public static final Flavor SHARED_FLAVOR = ImmutableFlavor.of("shared");
  public static final Flavor MACH_O_BUNDLE_FLAVOR = ImmutableFlavor.of("mach-o-bundle");
  public static final Flavor SHARED_LIBRARY_SYMLINK_TREE_FLAVOR =
      ImmutableFlavor.of("shared-library-symlink-tree");

  public static final Flavor CXX_LINK_BINARY_FLAVOR = ImmutableFlavor.of("binary");

  protected static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.<String, MacroExpander>of(
              "location", new LocationMacroExpander()));

  private CxxDescriptionEnhancer() {}

  public static HeaderSymlinkTree createHeaderSymlinkTree(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
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
    Optional<Path> headerMapLocation = Optional.absent();
    if (cxxPlatform.getCpp().supportsHeaderMaps() && cxxPlatform.getCxxpp().supportsHeaderMaps()) {
      headerMapLocation =
          Optional.of(
              getHeaderMapPath(
                  params.getBuildTarget(),
                  cxxPlatform.getFlavor(),
                  headerVisibility));
    }

    return CxxPreprocessables.createHeaderSymlinkTreeBuildRule(
        pathResolver,
        headerSymlinkTreeTarget,
        params,
        headerSymlinkTreeRoot,
        headerMapLocation,
        headers);
  }

  public static HeaderSymlinkTree requireHeaderSymlinkTree(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
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
      Preconditions.checkState(rule.get() instanceof HeaderSymlinkTree);
      return (HeaderSymlinkTree) rule.get();
    }

    HeaderSymlinkTree symlinkTree = createHeaderSymlinkTree(
        params,
        pathResolver,
        cxxPlatform,
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
   * @return the {@link Path} to use for the header map for the given symlink tree.
   */
  public static Path getHeaderMapPath(
      BuildTarget target,
      Flavor platform,
      HeaderVisibility headerVisibility) {
    return BuildTargets.getGenPath(
        createHeaderSymlinkTreeTarget(target, platform, headerVisibility),
        "%s.hmap");
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
    return parseCxxSources(
        params,
        resolver,
        cxxPlatform,
        args.srcs.get(),
        args.platformSrcs.get());
  }

  public static ImmutableMap<String, CxxSource> parseCxxSources(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<SourceWithFlags> srcs,
      PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> platformSrcs) {
    ImmutableMap.Builder<String, SourceWithFlags> sources = ImmutableMap.builder();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    putAllSources(srcs, sources, pathResolver, params.getBuildTarget());
    for (ImmutableSortedSet<SourceWithFlags> sourcesWithFlags :
        platformSrcs.getMatchingValues(cxxPlatform.getFlavor().toString())) {
      putAllSources(sourcesWithFlags, sources, pathResolver, params.getBuildTarget());
    }
    return CxxCompilableEnhancer.resolveCxxSources(sources.build());
  }

  private static void putAllSources(
      ImmutableSortedSet<SourceWithFlags> sourcesWithFlags,
      ImmutableMap.Builder<String, SourceWithFlags> sources,
      SourcePathResolver pathResolver,
      BuildTarget buildTarget) {

    sources.putAll(
        pathResolver.getSourcePathNames(
            buildTarget,
            "srcs",
            sourcesWithFlags,
            SourceWithFlags.TO_SOURCE_PATH));
  }

  public static ImmutableList<CxxPreprocessorInput> collectCxxPreprocessorInput(
      TargetGraph targetGraph,
      BuildRuleParams params,
      CxxPlatform cxxPlatform,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableList<HeaderSymlinkTree> headerSymlinkTrees,
      ImmutableSet<FrameworkPath> frameworks,
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
                  targetGraph,
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
    ImmutableSet.Builder<Path> allHeaderMaps = ImmutableSet.builder();
    for (HeaderSymlinkTree headerSymlinkTree : headerSymlinkTrees) {
      allLinks.putAll(headerSymlinkTree.getLinks());
      allFullLinks.putAll(headerSymlinkTree.getFullLinks());
      allIncludeRoots.add(headerSymlinkTree.getIncludePath());
      allHeaderMaps.addAll(headerSymlinkTree.getHeaderMap().asSet());
    }

    CxxPreprocessorInput localPreprocessorInput =
        CxxPreprocessorInput.builder()
            .addAllRules(Iterables.transform(headerSymlinkTrees, HasBuildTarget.TO_TARGET))
            .putAllPreprocessorFlags(preprocessorFlags)
            .setIncludes(
                CxxHeaders.builder()
                    .putAllNameToPathMap(allLinks.build())
                    .putAllFullNameToPathMap(allFullLinks.build())
                    .build())
            .addAllIncludeRoots(allIncludeRoots.build())
            .addAllHeaderMaps(allHeaderMaps.build())
            .addAllFrameworks(frameworks)
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
  protected static Path getLinkOutputPath(BuildTarget target) {
    return BuildTargets.getGenPath(target, "%s");
  }

  @VisibleForTesting
  protected static BuildTarget createCxxLinkTarget(BuildTarget target) {
    return BuildTarget.builder(target).addFlavors(CXX_LINK_BINARY_FLAVOR).build();
  }

  /**
   * @return the framework search paths with any embedded macros expanded.
   */
  static ImmutableSet<Path> getFrameworkSearchPaths(
      Optional<ImmutableSortedSet<FrameworkPath>> frameworks,
      CxxPlatform cxxPlatform,
      SourcePathResolver resolver) {

    ImmutableSet<Path> searchPaths = FluentIterable.from(frameworks.get())
        .transform(
            FrameworkPath.getUnexpandedSearchPathFunction(
                resolver.deprecatedPathFunction(),
                Functions.<Path>identity()))
        .toSet();

   return FluentIterable.from(Optional.of(searchPaths).or(ImmutableSet.<Path>of()))
        .transform(Functions.toStringFunction())
        .transform(CxxFlags.getTranslateMacrosFn(cxxPlatform))
       .transform(MorePaths.TO_PATH)
       .toSet();
  }

  public static CxxLinkAndCompileRules createBuildRulesForCxxBinaryDescriptionArg(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxBinaryDescription.Arg args,
      CxxPreprocessMode preprocessMode) {

    ImmutableMap<String, CxxSource> srcs = parseCxxSources(params, resolver, cxxPlatform, args);
    ImmutableMap<Path, SourcePath> headers = parseHeaders(params, resolver, cxxPlatform, args);
    return createBuildRulesForCxxBinary(
        targetGraph,
        params,
        resolver,
        cxxPlatform,
        srcs,
        headers,
        preprocessMode,
        args.linkStyle.or(Linker.LinkableDepType.STATIC),
        args.preprocessorFlags,
        args.platformPreprocessorFlags,
        args.langPreprocessorFlags,
        args.frameworks,
        args.compilerFlags,
        args.platformCompilerFlags,
        args.prefixHeader,
        args.linkerFlags,
        args.platformLinkerFlags,
        args.cxxRuntimeType);
  }

  public static CxxLinkAndCompileRules createBuildRulesForCxxBinary(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, CxxSource> srcs,
      ImmutableMap<Path, SourcePath> headers,
      CxxPreprocessMode preprocessMode,
      Linker.LinkableDepType linkStyle,
      Optional<ImmutableList<String>> preprocessorFlags,
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformPreprocessorFlags,
      Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>> langPreprocessorFlags,
      Optional<ImmutableSortedSet<FrameworkPath>> frameworks,
      Optional<ImmutableList<String>> compilerFlags,
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformCompilerFlags,
      Optional<SourcePath> prefixHeader,
      Optional<ImmutableList<String>> linkerFlags,
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformLinkerFlags,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType) {
    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    Path linkOutput = getLinkOutputPath(params.getBuildTarget());
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();
    CommandTool.Builder executableBuilder = new CommandTool.Builder();

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    HeaderSymlinkTree headerSymlinkTree = requireHeaderSymlinkTree(
        params,
        resolver,
        sourcePathResolver,
        cxxPlatform,
        headers,
        HeaderVisibility.PRIVATE);
    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput =
        collectCxxPreprocessorInput(
            targetGraph,
            params,
            cxxPlatform,
            CxxFlags.getLanguageFlags(
                preprocessorFlags,
                platformPreprocessorFlags,
                langPreprocessorFlags,
                cxxPlatform),
            ImmutableList.of(headerSymlinkTree),
            frameworks.get(),
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                targetGraph,
                cxxPlatform,
                FluentIterable.from(params.getDeps())
                    .filter(Predicates.instanceOf(CxxPreprocessorDep.class))));

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
                compilerFlags,
                platformCompilerFlags,
                cxxPlatform),
            prefixHeader,
            preprocessMode,
            srcs,
            linkStyle == Linker.LinkableDepType.STATIC ?
                CxxSourceRuleFactory.PicType.PDC :
                CxxSourceRuleFactory.PicType.PIC);

    // Build up the linker flags, which support macro expansion.
    ImmutableList<String> resolvedLinkerFlags =
        CxxFlags.getFlags(
            linkerFlags,
            platformLinkerFlags,
            cxxPlatform);
    argsBuilder.addAll(
        FluentIterable.from(resolvedLinkerFlags)
            .transform(
                MacroArg.toMacroArgFunction(
                    MACRO_HANDLER,
                    params.getBuildTarget(),
                    params.getCellRoots(),
                    resolver,
                    params.getProjectFilesystem())));

    // Special handling for dynamically linked binaries.
    if (linkStyle == Linker.LinkableDepType.SHARED) {

      // Create a symlink tree with for all shared libraries needed by this binary.
      SymlinkTree sharedLibraries =
          resolver.addToIndex(
              createSharedLibrarySymlinkTree(
                  targetGraph,
                  params,
                  sourcePathResolver,
                  cxxPlatform,
                  Predicates.instanceOf(NativeLinkable.class)));

      // Embed a origin-relative library path into the binary so it can find the shared libraries.
      argsBuilder.addAll(
          StringArg.from(
              Linkers.iXlinker(
                  "-rpath",
                  String.format(
                      "%s/%s",
                      cxxPlatform.getLd().origin(),
                      linkOutput.getParent().relativize(sharedLibraries.getRoot()).toString()))));

      // Add all the shared libraries and the symlink tree as inputs to the tool that represents
      // this binary, so that users can attach the proper deps.
      executableBuilder.addDep(sharedLibraries);
      executableBuilder.addInputs(sharedLibraries.getLinks().values());
    }

    // Add object files into the args.
    argsBuilder.addAll(SourcePathArg.from(sourcePathResolver, objects.values()));

    // Generate the final link rule.  We use the top-level target as the link rule's
    // target, so that it corresponds to the actual binary we build.
    CxxLink cxxLink =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            targetGraph,
            cxxPlatform,
            params,
            sourcePathResolver,
            createCxxLinkTarget(params.getBuildTarget()),
            Linker.LinkType.EXECUTABLE,
            Optional.<String>absent(),
            linkOutput,
            argsBuilder.build(),
            linkStyle,
            params.getDeps(),
            cxxRuntimeType,
            Optional.<SourcePath>absent(),
            ImmutableSet.<BuildTarget>of(),
            frameworks.or(ImmutableSortedSet.<FrameworkPath>of()));
    resolver.addToIndex(cxxLink);

    // Add the output of the link as the lone argument needed to invoke this binary as a tool.
    executableBuilder.addArg(new BuildTargetSourcePath(cxxLink.getBuildTarget()));

    return new CxxLinkAndCompileRules(
        cxxLink,
        ImmutableSortedSet.copyOf(objects.keySet()),
        executableBuilder.build());
  }

  private static <T> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      TargetNode<T> node,
      Flavor... flavors) {
    BuildTarget target = BuildTarget.builder(params.getBuildTarget()).addFlavors(flavors).build();
    Description<T> description = node.getDescription();
    T args = node.getConstructorArg();
    return description.createBuildRule(
        targetGraph,
        params.copyWithChanges(
            target,
            params.getDeclaredDeps(),
            params.getExtraDeps()),
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
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      Flavor... flavors) {
    BuildTarget target = BuildTarget.builder(params.getBuildTarget()).addFlavors(flavors).build();
    Optional<BuildRule> rule = ruleResolver.getRuleOptional(target);
    if (!rule.isPresent()) {
      TargetNode<?> node = targetGraph.get(params.getBuildTarget());
      Preconditions.checkNotNull(
          node,
          String.format("%s not in target graph", params.getBuildTarget()));
      rule = Optional.of(createBuildRule(targetGraph, params, ruleResolver, node, flavors));
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
   * Build a {@link HeaderSymlinkTree} of all the shared libraries found via the top-level rule's
   * transitive dependencies.
   */
  public static SymlinkTree createSharedLibrarySymlinkTree(
      TargetGraph targetGraph,
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
            targetGraph,
            cxxPlatform,
            params.getDeps(),
            traverse);

    ImmutableMap.Builder<Path, SourcePath> links = ImmutableMap.builder();
    for (Map.Entry<String, SourcePath> ent : libraries.entrySet()) {
      links.put(Paths.get(ent.getKey()), ent.getValue());
    }
    try {
      return new SymlinkTree(
          params.copyWithChanges(
              symlinkTreeTarget,
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
          pathResolver,
          symlinkTreeRoot,
          links.build());
    } catch (SymlinkTree.InvalidSymlinkTreeException e) {
      throw new RuntimeException(e.getMessage());
    }
  }

}
