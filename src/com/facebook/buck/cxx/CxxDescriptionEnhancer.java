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
import com.facebook.buck.json.JsonConcatenate;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
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

  private static final Pattern SONAME_EXT_MACRO_PATTERN =
      Pattern.compile("\\$\\(ext(?: ([.0-9]+))?\\)");

  private CxxDescriptionEnhancer() {}

  public static HeaderSymlinkTree createHeaderSymlinkTree(
      BuildRuleParams params,
      BuildRuleResolver resolver,
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
    if (cxxPlatform.getCpp().resolve(resolver).supportsHeaderMaps() &&
        cxxPlatform.getCxxpp().resolve(resolver).supportsHeaderMaps()) {
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
        ruleResolver,
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
   * @return the absolute {@link Path} to use for the symlink tree of headers.
   */
  public static Path getHeaderSymlinkTreePath(
      BuildTarget target,
      Flavor platform,
      HeaderVisibility headerVisibility) {
    return target.getCellPath().resolve(
        BuildTargets.getGenPath(
        createHeaderSymlinkTreeTarget(target, platform, headerVisibility),
        "%s"));
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
      BuildTarget buildTarget,
      SourcePathResolver resolver,
      Optional<CxxPlatform> cxxPlatform,
      CxxConstructorArg args) {
    ImmutableMap.Builder<String, SourcePath> headers = ImmutableMap.builder();
    putAllHeaders(args.headers.get(), headers, resolver, "headers", buildTarget);
    if (cxxPlatform.isPresent()) {
      for (SourceList sourceList : args.platformHeaders.get().getMatchingValues(
          cxxPlatform.get().getFlavor().toString())) {
        putAllHeaders(
            sourceList,
            headers,
            resolver,
            "platform_headers",
            buildTarget);
      }
    }
    return CxxPreprocessables.resolveHeaderMap(
        args.headerNamespace.transform(MorePaths.TO_PATH)
            .or(buildTarget.getBasePath()),
        headers.build());
  }

  /**
   * @return a map of header locations to input {@link SourcePath} objects formed by parsing the
   *    input {@link SourcePath} objects for the "exportedHeaders" parameter.
   */
  public static ImmutableMap<Path, SourcePath> parseExportedHeaders(
      BuildTarget buildTarget,
      SourcePathResolver resolver,
      Optional<CxxPlatform> cxxPlatform,
      CxxLibraryDescription.Arg args) {
    ImmutableMap.Builder<String, SourcePath> headers = ImmutableMap.builder();
    putAllHeaders(
        args.exportedHeaders.get(),
        headers,
        resolver,
        "exported_headers",
        buildTarget);
    if (cxxPlatform.isPresent()) {
      for (SourceList sourceList : args.exportedPlatformHeaders.get().getMatchingValues(
          cxxPlatform.get().getFlavor().toString())) {
        putAllHeaders(
            sourceList,
            headers,
            resolver,
            "exported_platform_headers",
            buildTarget);
      }
    }
    return CxxPreprocessables.resolveHeaderMap(
        args.headerNamespace.transform(MorePaths.TO_PATH)
            .or(buildTarget.getBasePath()),
        headers.build());
  }

  /**
   * Resolves the headers in `sourceList` and puts them into `sources` for the specificed
   * `buildTarget`.
   */
  public static void putAllHeaders(
      SourceList sourceList,
      ImmutableMap.Builder<String, SourcePath> sources,
      SourcePathResolver sourcePathResolver,
      String parameterName,
      BuildTarget buildTarget) {
    switch (sourceList.getType()) {
      case NAMED:
        sources.putAll(sourceList.getNamedSources().get());
        break;
      case UNNAMED:
        sources.putAll(
            sourcePathResolver.getSourcePathNames(
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
      BuildTarget buildTarget,
      SourcePathResolver resolver,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args) {
    return parseCxxSources(
        buildTarget,
        resolver,
        cxxPlatform,
        args.srcs.get(),
        args.platformSrcs.get());
  }

  public static ImmutableMap<String, CxxSource> parseCxxSources(
      BuildTarget buildTarget,
      SourcePathResolver resolver,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<SourceWithFlags> srcs,
      PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> platformSrcs) {
    ImmutableMap.Builder<String, SourceWithFlags> sources = ImmutableMap.builder();
    putAllSources(srcs, sources, resolver, buildTarget);
    for (ImmutableSortedSet<SourceWithFlags> sourcesWithFlags :
        platformSrcs.getMatchingValues(cxxPlatform.getFlavor().toString())) {
      putAllSources(sourcesWithFlags, sources, resolver, buildTarget);
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
      BuildRuleParams params,
      CxxPlatform cxxPlatform,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableList<HeaderSymlinkTree> headerSymlinkTrees,
      ImmutableSet<FrameworkPath> frameworks,
      Iterable<CxxPreprocessorInput> cxxPreprocessorInputFromDeps)
      throws NoSuchBuildTargetException {

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

    ImmutableList.Builder<CxxHeaders> allIncludes = ImmutableList.builder();
    for (HeaderSymlinkTree headerSymlinkTree : headerSymlinkTrees) {
      allIncludes.add(
          CxxHeaders.fromSymlinkTree(headerSymlinkTree, CxxPreprocessables.IncludeType.LOCAL));
    }

    CxxPreprocessorInput localPreprocessorInput =
        CxxPreprocessorInput.builder()
            .putAllPreprocessorFlags(preprocessorFlags)
            .addAllIncludes(allIncludes.build())
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

  static String getSharedLibrarySoname(
      Optional<String> declaredSoname,
      BuildTarget target,
      CxxPlatform platform) {
    if (!declaredSoname.isPresent()) {
      return getDefaultSharedLibrarySoname(target, platform);
    }
    return getNonDefaultSharedLibrarySoname(
        declaredSoname.get(),
        platform.getSharedLibraryExtension(),
        platform.getSharedLibraryVersionedExtensionFormat());
  }

  @VisibleForTesting
  static String getNonDefaultSharedLibrarySoname(
      String declared,
      String sharedLibraryExtension,
      String sharedLibraryVersionedExtensionFormat) {
    Matcher match = SONAME_EXT_MACRO_PATTERN.matcher(declared);
    if (!match.find()) {
      return declared;
    }
    String version = match.group(1);
    if (version == null) {
      return match.replaceFirst(sharedLibraryExtension);
    }
    return match.replaceFirst(
        String.format(
            sharedLibraryVersionedExtensionFormat,
            version));
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
   * @return a function that transforms the {@link FrameworkPath} to search paths with any embedded
   * macros expanded.
   */
  static RuleKeyAppendableFunction<FrameworkPath, Path> frameworkPathToSearchPath(
      final CxxPlatform cxxPlatform,
      final SourcePathResolver resolver) {
    return new RuleKeyAppendableFunction<FrameworkPath, Path>() {
      private RuleKeyAppendableFunction<String, String> translateMacrosFn =
          CxxFlags.getTranslateMacrosFn(cxxPlatform);

      @Override
      public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
        return builder.setReflectively("translateMacrosFn", translateMacrosFn);
      }

      @Override
      public Path apply(FrameworkPath input) {
        Function<FrameworkPath, Path> convertToPath =
            FrameworkPath.getUnexpandedSearchPathFunction(
                resolver.getAbsolutePathFunction(),
                Functions.<Path>identity());
        String pathAsString = convertToPath.apply(input).toString();
        return Paths.get(translateMacrosFn.apply(pathAsString));
      }
    };
  }

  public static CxxLinkAndCompileRules createBuildRulesForCxxBinaryDescriptionArg(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxBinaryDescription.Arg args,
      CxxPreprocessMode preprocessMode,
      Optional<CxxStrip.StripStyle> stripStyle) throws NoSuchBuildTargetException {

    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    ImmutableMap<String, CxxSource> srcs = parseCxxSources(
        params.getBuildTarget(),
        sourcePathResolver,
        cxxPlatform,
        args);
    ImmutableMap<Path, SourcePath> headers = parseHeaders(
        params.getBuildTarget(),
        new SourcePathResolver(resolver),
        Optional.of(cxxPlatform),
        args);
    return createBuildRulesForCxxBinary(
        params,
        resolver,
        cxxPlatform,
        srcs,
        headers,
        preprocessMode,
        stripStyle,
        args.linkStyle.or(Linker.LinkableDepType.STATIC),
        args.preprocessorFlags,
        args.platformPreprocessorFlags,
        args.langPreprocessorFlags,
        args.frameworks,
        args.libraries,
        args.compilerFlags,
        args.langCompilerFlags,
        args.platformCompilerFlags,
        args.prefixHeader,
        args.linkerFlags,
        args.platformLinkerFlags,
        args.cxxRuntimeType);
  }

  public static CxxLinkAndCompileRules createBuildRulesForCxxBinary(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, CxxSource> srcs,
      ImmutableMap<Path, SourcePath> headers,
      CxxPreprocessMode preprocessMode,
      Optional<CxxStrip.StripStyle> stripStyle,
      Linker.LinkableDepType linkStyle,
      Optional<ImmutableList<String>> preprocessorFlags,
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformPreprocessorFlags,
      Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>> langPreprocessorFlags,
      Optional<ImmutableSortedSet<FrameworkPath>> frameworks,
      Optional<ImmutableSortedSet<FrameworkPath>> libraries,
      Optional<ImmutableList<String>> compilerFlags,
      Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>> langCompilerFlags,
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformCompilerFlags,
      Optional<SourcePath> prefixHeader,
      Optional<ImmutableList<String>> linkerFlags,
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformLinkerFlags,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType)
      throws NoSuchBuildTargetException {
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
            CxxFlags.getLanguageFlags(
                compilerFlags,
                platformCompilerFlags,
                langCompilerFlags,
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
                    resolver)));

    // Special handling for dynamically linked binaries.
    if (linkStyle == Linker.LinkableDepType.SHARED) {

      // Create a symlink tree with for all shared libraries needed by this binary.
      SymlinkTree sharedLibraries =
          resolver.addToIndex(
              createSharedLibrarySymlinkTree(
                  params,
                  sourcePathResolver,
                  cxxPlatform,
                  Predicates.instanceOf(NativeLinkable.class)));

      // Embed a origin-relative library path into the binary so it can find the shared libraries.
      // The shared libraries root is absolute. Also need an absolute path to the linkOutput

      Path absLinkOut = params.getBuildTarget().getCellPath().resolve(linkOutput);

      argsBuilder.addAll(
          StringArg.from(
              Linkers.iXlinker(
                  "-rpath",
                  String.format(
                      "%s/%s",
                      cxxPlatform.getLd().resolve(resolver).origin(),
                      absLinkOut.getParent().relativize(sharedLibraries.getRoot()).toString()))));

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
            cxxPlatform,
            params,
            resolver,
            sourcePathResolver,
            createCxxLinkTarget(params.getBuildTarget()),
            Linker.LinkType.EXECUTABLE,
            Optional.<String>absent(),
            linkOutput,
            linkStyle,
            FluentIterable.from(params.getDeps())
                .filter(NativeLinkable.class),
            cxxRuntimeType,
            Optional.<SourcePath>absent(),
            ImmutableSet.<BuildTarget>of(),
            NativeLinkableInput.builder()
                .setArgs(argsBuilder.build())
                .setFrameworks(frameworks.or(ImmutableSortedSet.<FrameworkPath>of()))
                .setLibraries(libraries.or(ImmutableSortedSet.<FrameworkPath>of()))
                .build());
    resolver.addToIndex(cxxLink);

    BuildRule binaryRuleForExecutable;
    Optional<CxxStrip> cxxStrip = Optional.absent();
    if (stripStyle.isPresent()) {
      CxxStrip stripRule =
          createCxxStripRule(params, cxxPlatform, stripStyle, sourcePathResolver, cxxLink);
      cxxStrip = Optional.of(stripRule);
      resolver.addToIndex(stripRule);
      binaryRuleForExecutable = stripRule;
    } else {
      binaryRuleForExecutable = cxxLink;
    }

    // Add the output of the link as the lone argument needed to invoke this binary as a tool.
    executableBuilder.addArg(
        new SourcePathArg(
            sourcePathResolver,
            new BuildTargetSourcePath(binaryRuleForExecutable.getBuildTarget())));

    return new CxxLinkAndCompileRules(
        cxxLink,
        cxxStrip,
        ImmutableSortedSet.copyOf(objects.keySet()),
        executableBuilder.build());
  }

  private static CxxStrip createCxxStripRule(
      BuildRuleParams params,
      CxxPlatform cxxPlatform,
      Optional<CxxStrip.StripStyle> stripStyle,
      SourcePathResolver sourcePathResolver,
      CxxLink cxxLink) {
    BuildRuleParams stripRuleParams = params
        .copyWithChanges(
            BuildTarget.builder(params.getBuildTarget())
                .addFlavors(CxxStrip.RULE_FLAVOR)
                .addFlavors(stripStyle.get().getFlavor())
                .build(),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(cxxLink)),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
    return new CxxStrip(
        stripRuleParams,
        sourcePathResolver,
        stripStyle.get(),
        new BuildTargetSourcePath(cxxLink.getBuildTarget()),
        cxxPlatform.getStrip(),
        CxxDescriptionEnhancer.getLinkOutputPath(stripRuleParams.getBuildTarget()));
  }

  /**
   * Create all build rules needed to generate the compilation database.
   *
   * @return the {@link CxxCompilationDatabase} rule representing the actual compilation database.
   */
  public static CxxCompilationDatabase createCompilationDatabase(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      CxxPreprocessMode preprocessMode,
      CxxConstructorArg arg) throws NoSuchBuildTargetException {
    // Invoking requireObjects has the side-effect of invoking
    // CxxSourceRuleFactory.requirePreprocessAndCompileRules(), which has the side-effect of
    // creating CxxPreprocessAndCompile rules and adding them to the ruleResolver.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects = requireObjects(
        params.withoutFlavor(CxxCompilationDatabase.COMPILATION_DATABASE),
        ruleResolver,
        pathResolver,
        cxxPlatform,
        preprocessMode,
        CxxSourceRuleFactory.PicType.PIC,
        arg);

    return CxxCompilationDatabase.createCompilationDatabase(
        params,
        pathResolver,
        preprocessMode,
        objects.keySet());
  }

  public static BuildRule createUberCompilationDatabase(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver) throws NoSuchBuildTargetException {
    Optional<CxxCompilationDatabaseDependencies> compilationDatabases =
        ruleResolver.requireMetadata(
            params
                .withoutFlavor(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)
                .withFlavor(CxxCompilationDatabase.COMPILATION_DATABASE)
                .getBuildTarget(),
            CxxCompilationDatabaseDependencies.class);
    Preconditions.checkState(compilationDatabases.isPresent());
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    return new JsonConcatenate(
        params.copyWithDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.copyOf(
                    pathResolver.filterBuildRuleInputs(
                        compilationDatabases.get().getSourcePaths()))),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        ImmutableSortedSet.copyOf(
            pathResolver.getAllAbsolutePaths(compilationDatabases.get().getSourcePaths())),
        "compilation-database-concatenate",
        "Concatenate compilation databases",
        "uber-compilation-database",
        "compile_commands.json");
  }

  public static Optional<CxxCompilationDatabaseDependencies> createCompilationDatabaseDependencies(
      BuildTarget buildTarget,
      FlavorDomain<CxxPlatform> platforms,
      BuildRuleResolver resolver,
      CxxConstructorArg args) throws NoSuchBuildTargetException {
    Preconditions.checkState(
        buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE));
    Optional<Flavor> cxxPlatformFlavor = platforms.getFlavor(buildTarget);
    Preconditions.checkState(
        cxxPlatformFlavor.isPresent(),
        "Could not find cxx platform in:\n%s",
        Joiner.on(", ").join(buildTarget.getFlavors()));
    ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
    for (BuildTarget dep : args.deps.get()) {
      Optional<CxxCompilationDatabaseDependencies> compilationDatabases =
          resolver.requireMetadata(
              BuildTarget.builder(dep)
                  .addFlavors(CxxCompilationDatabase.COMPILATION_DATABASE)
                  .addFlavors(cxxPlatformFlavor.get())
                  .build(),
              CxxCompilationDatabaseDependencies.class);
      if (compilationDatabases.isPresent()) {
        sourcePaths.addAll(compilationDatabases.get().getSourcePaths());
      }
    }
    // Not all parts of Buck use require yet, so require the rule here so it's available in the
    // resolver for the parts that don't.
    resolver.requireRule(buildTarget);
    sourcePaths.add(new BuildTargetSourcePath(buildTarget));
    return Optional.of(CxxCompilationDatabaseDependencies.of(sourcePaths.build()));
  }

  public static ImmutableMap<CxxPreprocessAndCompile, SourcePath> requireObjects(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver sourcePathResolver,
      CxxPlatform cxxPlatform,
      CxxPreprocessMode preprocessMode,
      CxxSourceRuleFactory.PicType pic,
      CxxConstructorArg args) throws NoSuchBuildTargetException {
    ImmutableMultimap<CxxSource.Type, String> exportedPreprocessorFlags;
    ImmutableMap<Path, SourcePath> exportedHeaders;
    if (args instanceof CxxLibraryDescription.Arg) {
      CxxLibraryDescription.Arg hasExportedArgs = (CxxLibraryDescription.Arg) args;
      exportedPreprocessorFlags = CxxFlags.getLanguageFlags(
          hasExportedArgs.exportedPreprocessorFlags,
          hasExportedArgs.exportedPlatformPreprocessorFlags,
          hasExportedArgs.exportedLangPreprocessorFlags,
          cxxPlatform);
      exportedHeaders = CxxDescriptionEnhancer.parseExportedHeaders(
          params.getBuildTarget(),
          sourcePathResolver,
          Optional.of(cxxPlatform),
          hasExportedArgs);
    } else {
      exportedPreprocessorFlags = ImmutableMultimap.of();
      exportedHeaders = ImmutableMap.of();
    }

    HeaderSymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params,
            ruleResolver,
            sourcePathResolver,
            cxxPlatform,
            CxxDescriptionEnhancer.parseHeaders(
                params.getBuildTarget(),
                sourcePathResolver,
                Optional.of(cxxPlatform),
                args),
            HeaderVisibility.PRIVATE);

    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInputFromDependencies =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            params,
            cxxPlatform,
            CxxFlags.getLanguageFlags(
                args.preprocessorFlags,
                args.platformPreprocessorFlags,
                args.langPreprocessorFlags,
                cxxPlatform),
            ImmutableList.of(headerSymlinkTree),
            ImmutableSet.<FrameworkPath>of(),
            CxxLibraryDescription.getTransitiveCxxPreprocessorInput(
                params,
                ruleResolver,
                sourcePathResolver,
                cxxPlatform,
                exportedPreprocessorFlags,
                exportedHeaders,
                args.frameworks.or(ImmutableSortedSet.<FrameworkPath>of())));

    // Create rule to build the object files.
    return CxxSourceRuleFactory.requirePreprocessAndCompileRules(
        params,
        ruleResolver,
        sourcePathResolver,
        cxxPlatform,
        cxxPreprocessorInputFromDependencies,
        CxxFlags.getLanguageFlags(
            args.compilerFlags,
            args.platformCompilerFlags,
            args.langCompilerFlags,
            cxxPlatform),
        args.prefixHeader,
        preprocessMode,
        CxxDescriptionEnhancer.parseCxxSources(
            params.getBuildTarget(),
            sourcePathResolver,
            cxxPlatform,
            args),
        pic);
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
    return target.getCellPath().resolve(BuildTargets.getGenPath(
        createSharedLibrarySymlinkTreeTarget(target, platform),
        "%s"));
  }

  /**
   * Build a {@link HeaderSymlinkTree} of all the shared libraries found via the top-level rule's
   * transitive dependencies.
   */
  public static SymlinkTree createSharedLibrarySymlinkTree(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Predicate<Object> traverse) throws NoSuchBuildTargetException {

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

  public static Flavor flavorForLinkableDepType(Linker.LinkableDepType linkableDepType) {
    switch (linkableDepType) {
      case STATIC:
        return STATIC_FLAVOR;
      case STATIC_PIC:
        return STATIC_PIC_FLAVOR;
      case SHARED:
        return SHARED_FLAVOR;
    }
    throw new RuntimeException(
        String.format("Unsupported LinkableDepType: '%s'", linkableDepType));
  }

}
