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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.JsonConcatenate;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.args.StringWithMacrosArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

public class CxxDescriptionEnhancer {

  private static final Logger LOG = Logger.get(CxxDescriptionEnhancer.class);

  public static final Flavor SANDBOX_TREE_FLAVOR = InternalFlavor.of("sandbox");
  public static final Flavor HEADER_SYMLINK_TREE_FLAVOR = InternalFlavor.of("private-headers");
  public static final Flavor EXPORTED_HEADER_SYMLINK_TREE_FLAVOR = InternalFlavor.of("headers");
  public static final Flavor STATIC_FLAVOR = InternalFlavor.of("static");
  public static final Flavor STATIC_PIC_FLAVOR = InternalFlavor.of("static-pic");
  public static final Flavor SHARED_FLAVOR = InternalFlavor.of("shared");
  public static final Flavor MACH_O_BUNDLE_FLAVOR = InternalFlavor.of("mach-o-bundle");
  public static final Flavor SHARED_LIBRARY_SYMLINK_TREE_FLAVOR =
      InternalFlavor.of("shared-library-symlink-tree");

  public static final Flavor CXX_LINK_BINARY_FLAVOR = InternalFlavor.of("binary");

  protected static final MacroHandler MACRO_HANDLER =
      new MacroHandler(ImmutableMap.of("location", new LocationMacroExpander()));

  private static final Pattern SONAME_EXT_MACRO_PATTERN =
      Pattern.compile("\\$\\(ext(?: ([.0-9]+))?\\)");

  private CxxDescriptionEnhancer() {}

  public static CxxPreprocessables.HeaderMode getHeaderModeForPlatform(
      BuildRuleResolver resolver, CxxPlatform cxxPlatform, boolean shouldCreateHeadersSymlinks) {
    boolean useHeaderMap =
        (cxxPlatform.getCpp().resolve(resolver).supportsHeaderMaps()
            && cxxPlatform.getCxxpp().resolve(resolver).supportsHeaderMaps());
    return !useHeaderMap
        ? CxxPreprocessables.HeaderMode.SYMLINK_TREE_ONLY
        : (shouldCreateHeadersSymlinks
            ? CxxPreprocessables.HeaderMode.SYMLINK_TREE_WITH_HEADER_MAP
            : CxxPreprocessables.HeaderMode.HEADER_MAP_ONLY);
  }

  public static HeaderSymlinkTree createHeaderSymlinkTree(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPreprocessables.HeaderMode mode,
      ImmutableMap<Path, SourcePath> headers,
      HeaderVisibility headerVisibility,
      Flavor... flavors) {
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            params.getBuildTarget(), headerVisibility, flavors);
    Path headerSymlinkTreeRoot =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            params.getProjectFilesystem(), params.getBuildTarget(), headerVisibility, flavors);
    return CxxPreprocessables.createHeaderSymlinkTreeBuildRule(
        headerSymlinkTreeTarget,
        params.getProjectFilesystem(),
        headerSymlinkTreeRoot,
        headers,
        mode,
        new SourcePathRuleFinder(resolver));
  }

  public static HeaderSymlinkTree createHeaderSymlinkTree(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<Path, SourcePath> headers,
      HeaderVisibility headerVisibility,
      boolean shouldCreateHeadersSymlinks) {
    return createHeaderSymlinkTree(
        params,
        resolver,
        getHeaderModeForPlatform(resolver, cxxPlatform, shouldCreateHeadersSymlinks),
        headers,
        headerVisibility,
        cxxPlatform.getFlavor());
  }

  public static SymlinkTree createSandboxSymlinkTree(
      BuildRuleParams params,
      CxxPlatform cxxPlatform,
      ImmutableMap<Path, SourcePath> map,
      SourcePathRuleFinder ruleFinder) {
    BuildTarget sandboxSymlinkTreeTarget =
        CxxDescriptionEnhancer.createSandboxSymlinkTreeTarget(
            params.getBuildTarget(), cxxPlatform.getFlavor());
    Path sandboxSymlinkTreeRoot =
        CxxDescriptionEnhancer.getSandboxSymlinkTreePath(
            params.getProjectFilesystem(), sandboxSymlinkTreeTarget);

    return new SymlinkTree(
        sandboxSymlinkTreeTarget,
        params.getProjectFilesystem(),
        sandboxSymlinkTreeRoot,
        map,
        ruleFinder);
  }

  public static HeaderSymlinkTree requireHeaderSymlinkTree(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<Path, SourcePath> headers,
      HeaderVisibility headerVisibility,
      boolean shouldCreateHeadersSymlinks) {
    BuildRuleParams untypedParams = CxxLibraryDescription.getUntypedParams(params);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            untypedParams.getBuildTarget(), headerVisibility, cxxPlatform.getFlavor());

    // Check the cache...
    Optional<BuildRule> rule = ruleResolver.getRuleOptional(headerSymlinkTreeTarget);
    if (rule.isPresent()) {
      Preconditions.checkState(rule.get() instanceof HeaderSymlinkTree);
      return (HeaderSymlinkTree) rule.get();
    }

    HeaderSymlinkTree symlinkTree =
        createHeaderSymlinkTree(
            untypedParams,
            ruleResolver,
            cxxPlatform,
            headers,
            headerVisibility,
            shouldCreateHeadersSymlinks);

    ruleResolver.addToIndex(symlinkTree);

    return symlinkTree;
  }

  private static SymlinkTree requireSandboxSymlinkTree(
      BuildRuleParams params, BuildRuleResolver ruleResolver, CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    BuildRuleParams untypedParams = CxxLibraryDescription.getUntypedParams(params);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createSandboxSymlinkTreeTarget(
            untypedParams.getBuildTarget(), cxxPlatform.getFlavor());
    BuildRule rule = ruleResolver.requireRule(headerSymlinkTreeTarget);
    Preconditions.checkState(
        rule instanceof SymlinkTree, rule.getBuildTarget() + " " + rule.getClass().toString());
    return (SymlinkTree) rule;
  }

  /**
   * @return the {@link BuildTarget} to use for the {@link BuildRule} generating the symlink tree of
   *     headers.
   */
  @VisibleForTesting
  public static BuildTarget createHeaderSymlinkTreeTarget(
      BuildTarget target, HeaderVisibility headerVisibility, Flavor... flavors) {
    return BuildTarget.builder(target)
        .addFlavors(getHeaderSymlinkTreeFlavor(headerVisibility))
        .addFlavors(flavors)
        .build();
  }

  @VisibleForTesting
  public static BuildTarget createSandboxSymlinkTreeTarget(BuildTarget target, Flavor platform) {
    return BuildTarget.builder(target).addFlavors(platform).addFlavors(SANDBOX_TREE_FLAVOR).build();
  }

  /** @return the absolute {@link Path} to use for the symlink tree of headers. */
  public static Path getHeaderSymlinkTreePath(
      ProjectFilesystem filesystem,
      BuildTarget target,
      HeaderVisibility headerVisibility,
      Flavor... flavors) {
    return BuildTargets.getGenPath(
        filesystem, createHeaderSymlinkTreeTarget(target, headerVisibility, flavors), "%s");
  }

  public static Path getSandboxSymlinkTreePath(ProjectFilesystem filesystem, BuildTarget target) {
    return BuildTargets.getGenPath(filesystem, target, "%s");
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

  static ImmutableMap<String, SourcePath> parseOnlyHeaders(
      BuildTarget buildTarget,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver sourcePathResolver,
      String parameterName,
      SourceList exportedHeaders) {
    return exportedHeaders.toNameMap(
        buildTarget,
        sourcePathResolver,
        parameterName,
        path -> !CxxGenruleDescription.wrapsCxxGenrule(ruleFinder, path),
        path -> path);
  }

  static ImmutableMap<String, SourcePath> parseOnlyPlatformHeaders(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver sourcePathResolver,
      CxxPlatform cxxPlatform,
      String headersParameterName,
      SourceList headers,
      String platformHeadersParameterName,
      PatternMatchedCollection<SourceList> platformHeaders)
      throws NoSuchBuildTargetException {
    ImmutableMap.Builder<String, SourcePath> parsed = ImmutableMap.builder();

    java.util.function.Function<SourcePath, SourcePath> fixup =
        path -> {
          try {
            return CxxGenruleDescription.fixupSourcePath(resolver, ruleFinder, cxxPlatform, path);
          } catch (NoSuchBuildTargetException e) {
            throw new RuntimeException(e);
          }
        };

    // Include all normal exported headers that are generated by `cxx_genrule`.
    parsed.putAll(
        headers.toNameMap(
            buildTarget,
            sourcePathResolver,
            headersParameterName,
            path -> CxxGenruleDescription.wrapsCxxGenrule(ruleFinder, path),
            fixup));

    // Include all platform specific headers.
    for (SourceList sourceList :
        platformHeaders.getMatchingValues(cxxPlatform.getFlavor().toString())) {
      parsed.putAll(
          sourceList.toNameMap(
              buildTarget, sourcePathResolver, platformHeadersParameterName, path -> true, fixup));
    }

    return parsed.build();
  }

  /**
   * @return a map of header locations to input {@link SourcePath} objects formed by parsing the
   *     input {@link SourcePath} objects for the "headers" parameter.
   */
  public static ImmutableMap<Path, SourcePath> parseHeaders(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver sourcePathResolver,
      Optional<CxxPlatform> cxxPlatform,
      CxxConstructorArg args)
      throws NoSuchBuildTargetException {
    ImmutableMap.Builder<String, SourcePath> headers = ImmutableMap.builder();

    // Add platform-agnostic headers.
    headers.putAll(
        parseOnlyHeaders(
            buildTarget, ruleFinder, sourcePathResolver, "headers", args.getHeaders()));

    // Add platform-specific headers.
    if (cxxPlatform.isPresent()) {
      headers.putAll(
          parseOnlyPlatformHeaders(
              buildTarget,
              resolver,
              ruleFinder,
              sourcePathResolver,
              cxxPlatform.get(),
              "headers",
              args.getHeaders(),
              "platform_headers",
              args.getPlatformHeaders()));
    }

    return CxxPreprocessables.resolveHeaderMap(
        args.getHeaderNamespace().map(Paths::get).orElse(buildTarget.getBasePath()),
        headers.build());
  }

  /**
   * @return a map of header locations to input {@link SourcePath} objects formed by parsing the
   *     input {@link SourcePath} objects for the "exportedHeaders" parameter.
   */
  public static ImmutableMap<Path, SourcePath> parseExportedHeaders(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver sourcePathResolver,
      Optional<CxxPlatform> cxxPlatform,
      CxxLibraryDescription.CommonArg args)
      throws NoSuchBuildTargetException {
    ImmutableMap.Builder<String, SourcePath> headers = ImmutableMap.builder();

    // Include platform-agnostic headers.
    headers.putAll(
        parseOnlyHeaders(
            buildTarget,
            ruleFinder,
            sourcePathResolver,
            "exported_headers",
            args.getExportedHeaders()));

    // If a platform is specific, include platform-specific headers.
    if (cxxPlatform.isPresent()) {
      headers.putAll(
          parseOnlyPlatformHeaders(
              buildTarget,
              resolver,
              ruleFinder,
              sourcePathResolver,
              cxxPlatform.get(),
              "exported_headers",
              args.getExportedHeaders(),
              "exported_platform_headers",
              args.getExportedPlatformHeaders()));
    }

    return CxxPreprocessables.resolveHeaderMap(
        args.getHeaderNamespace().map(Paths::get).orElse(buildTarget.getBasePath()),
        headers.build());
  }

  /**
   * @return a map of header locations to input {@link SourcePath} objects formed by parsing the
   *     input {@link SourcePath} objects for the "exportedHeaders" parameter.
   */
  public static ImmutableMap<Path, SourcePath> parseExportedPlatformHeaders(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver sourcePathResolver,
      CxxPlatform cxxPlatform,
      CxxLibraryDescription.CommonArg args)
      throws NoSuchBuildTargetException {
    return CxxPreprocessables.resolveHeaderMap(
        args.getHeaderNamespace().map(Paths::get).orElse(buildTarget.getBasePath()),
        parseOnlyPlatformHeaders(
            buildTarget,
            resolver,
            ruleFinder,
            sourcePathResolver,
            cxxPlatform,
            "exported_headers",
            args.getExportedHeaders(),
            "exported_platform_headers",
            args.getExportedPlatformHeaders()));
  }

  /**
   * @return a list {@link CxxSource} objects formed by parsing the input {@link SourcePath} objects
   *     for the "srcs" parameter.
   */
  public static ImmutableMap<String, CxxSource> parseCxxSources(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      CxxConstructorArg args) {
    return parseCxxSources(
        buildTarget,
        resolver,
        ruleFinder,
        pathResolver,
        cxxPlatform,
        args.getSrcs(),
        args.getPlatformSrcs());
  }

  public static ImmutableMap<String, CxxSource> parseCxxSources(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<SourceWithFlags> srcs,
      PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> platformSrcs) {
    ImmutableMap.Builder<String, SourceWithFlags> sources = ImmutableMap.builder();
    putAllSources(buildTarget, resolver, ruleFinder, pathResolver, cxxPlatform, srcs, sources);
    for (ImmutableSortedSet<SourceWithFlags> sourcesWithFlags :
        platformSrcs.getMatchingValues(cxxPlatform.getFlavor().toString())) {
      putAllSources(
          buildTarget, resolver, ruleFinder, pathResolver, cxxPlatform, sourcesWithFlags, sources);
    }
    return resolveCxxSources(sources.build());
  }

  private static void putAllSources(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<SourceWithFlags> sourcesWithFlags,
      ImmutableMap.Builder<String, SourceWithFlags> sources) {
    sources.putAll(
        pathResolver.getSourcePathNames(
            buildTarget,
            "srcs",
            sourcesWithFlags
                .stream()
                .map(
                    s -> {
                      try {
                        return s.withSourcePath(
                            CxxGenruleDescription.fixupSourcePath(
                                resolver,
                                ruleFinder,
                                cxxPlatform,
                                Preconditions.checkNotNull(s.getSourcePath())));
                      } catch (NoSuchBuildTargetException e) {
                        throw new RuntimeException(e);
                      }
                    })
                .collect(MoreCollectors.toImmutableList()),
            x -> true,
            SourceWithFlags::getSourcePath));
  }

  public static ImmutableList<CxxPreprocessorInput> collectCxxPreprocessorInput(
      BuildRuleParams params,
      CxxPlatform cxxPlatform,
      Iterable<BuildRule> deps,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableList<HeaderSymlinkTree> headerSymlinkTrees,
      ImmutableSet<FrameworkPath> frameworks,
      Iterable<CxxPreprocessorInput> cxxPreprocessorInputFromDeps,
      ImmutableList<String> includeDirs,
      Optional<SymlinkTree> symlinkTree)
      throws NoSuchBuildTargetException {

    // Add the private includes of any rules which this rule depends on, and which list this rule as
    // a test.
    BuildTarget targetWithoutFlavor =
        BuildTarget.of(params.getBuildTarget().getUnflavoredBuildTarget());
    ImmutableList.Builder<CxxPreprocessorInput> cxxPreprocessorInputFromTestedRulesBuilder =
        ImmutableList.builder();
    for (BuildRule rule : deps) {
      if (rule instanceof NativeTestable) {
        NativeTestable testable = (NativeTestable) rule;
        if (testable.isTestedBy(targetWithoutFlavor)) {
          LOG.debug(
              "Adding private includes of tested rule %s to testing rule %s",
              rule.getBuildTarget(), params.getBuildTarget());
          cxxPreprocessorInputFromTestedRulesBuilder.add(
              testable.getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PRIVATE));

          // Add any dependent headers
          cxxPreprocessorInputFromTestedRulesBuilder.addAll(
              CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                  cxxPlatform, ImmutableList.of(rule)));
        }
      }
    }

    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInputFromTestedRules =
        cxxPreprocessorInputFromTestedRulesBuilder.build();
    LOG.verbose(
        "Rules tested by target %s added private includes %s",
        params.getBuildTarget(), cxxPreprocessorInputFromTestedRules);

    ImmutableList.Builder<CxxHeaders> allIncludes = ImmutableList.builder();
    for (HeaderSymlinkTree headerSymlinkTree : headerSymlinkTrees) {
      allIncludes.add(
          CxxSymlinkTreeHeaders.from(headerSymlinkTree, CxxPreprocessables.IncludeType.LOCAL));
    }

    CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();
    builder.putAllPreprocessorFlags(preprocessorFlags);

    // headers from #sandbox are put before #private-headers and #headers on purpose
    // this is the only way to control windows behavior
    if (symlinkTree.isPresent()) {
      for (String includeDir : includeDirs) {
        builder.addIncludes(
            CxxSandboxInclude.from(
                symlinkTree.get(), includeDir, CxxPreprocessables.IncludeType.LOCAL));
      }
    }

    builder.addAllIncludes(allIncludes.build()).addAllFrameworks(frameworks);

    CxxPreprocessorInput localPreprocessorInput = builder.build();

    return ImmutableList.<CxxPreprocessorInput>builder()
        .add(localPreprocessorInput)
        .addAll(cxxPreprocessorInputFromDeps)
        .addAll(cxxPreprocessorInputFromTestedRules)
        .build();
  }

  public static BuildTarget createStaticLibraryBuildTarget(
      BuildTarget target, Flavor platform, CxxSourceRuleFactory.PicType pic) {
    return BuildTarget.builder(target)
        .addFlavors(platform)
        .addFlavors(pic == CxxSourceRuleFactory.PicType.PDC ? STATIC_FLAVOR : STATIC_PIC_FLAVOR)
        .build();
  }

  public static BuildTarget createSharedLibraryBuildTarget(
      BuildTarget target, Flavor platform, Linker.LinkType linkType) {
    Flavor linkFlavor;
    switch (linkType) {
      case SHARED:
        linkFlavor = SHARED_FLAVOR;
        break;
      case MACH_O_BUNDLE:
        linkFlavor = MACH_O_BUNDLE_FLAVOR;
        break;
      case EXECUTABLE:
      default:
        throw new IllegalStateException(
            "Only SHARED and MACH_O_BUNDLE types expected, got: " + linkType);
    }
    return BuildTarget.builder(target).addFlavors(platform).addFlavors(linkFlavor).build();
  }

  public static Path getStaticLibraryPath(
      ProjectFilesystem filesystem,
      BuildTarget target,
      Flavor platform,
      CxxSourceRuleFactory.PicType pic,
      String extension) {
    String name = String.format("lib%s.%s", target.getShortName(), extension);
    return BuildTargets.getGenPath(
            filesystem, createStaticLibraryBuildTarget(target, platform, pic), "%s")
        .resolve(name);
  }

  public static String getSharedLibrarySoname(
      Optional<String> declaredSoname, BuildTarget target, CxxPlatform platform) {
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
    return match.replaceFirst(String.format(sharedLibraryVersionedExtensionFormat, version));
  }

  public static String getDefaultSharedLibrarySoname(BuildTarget target, CxxPlatform platform) {
    String libName =
        Joiner.on('_')
            .join(
                ImmutableList.builder()
                    .addAll(
                        StreamSupport.stream(target.getBasePath().spliterator(), false)
                            .map(Object::toString)
                            .filter(x -> !x.isEmpty())
                            .iterator())
                    .add(target.getShortName())
                    .build());
    String extension = platform.getSharedLibraryExtension();
    return String.format("lib%s.%s", libName, extension);
  }

  public static Path getSharedLibraryPath(
      ProjectFilesystem filesystem, BuildTarget sharedLibraryTarget, String soname) {
    return BuildTargets.getGenPath(filesystem, sharedLibraryTarget, "%s/" + soname);
  }

  private static Path getBinaryOutputPath(
      BuildTarget target, ProjectFilesystem filesystem, Optional<String> extension) {
    String format = extension.map(ext -> "%s." + ext).orElse("%s");
    return BuildTargets.getGenPath(filesystem, target, format);
  }

  @VisibleForTesting
  public static BuildTarget createCxxLinkTarget(
      BuildTarget target, Optional<LinkerMapMode> flavoredLinkerMapMode) {
    if (flavoredLinkerMapMode.isPresent()) {
      target = target.withAppendedFlavors(flavoredLinkerMapMode.get().getFlavor());
    }
    return target.withAppendedFlavors(CXX_LINK_BINARY_FLAVOR);
  }

  /**
   * @return a function that transforms the {@link FrameworkPath} to search paths with any embedded
   *     macros expanded.
   */
  public static RuleKeyAppendableFunction<FrameworkPath, Path> frameworkPathToSearchPath(
      final CxxPlatform cxxPlatform, final SourcePathResolver resolver) {
    return new RuleKeyAppendableFunction<FrameworkPath, Path>() {
      private RuleKeyAppendableFunction<String, String> translateMacrosFn =
          CxxFlags.getTranslateMacrosFn(cxxPlatform);

      @Override
      public void appendToRuleKey(RuleKeyObjectSink sink) {
        sink.setReflectively("translateMacrosFn", translateMacrosFn);
      }

      @Override
      public Path apply(FrameworkPath input) {
        String pathAsString =
            FrameworkPath.getUnexpandedSearchPath(
                    resolver::getAbsolutePath, Functions.identity(), input)
                .toString();
        return Paths.get(translateMacrosFn.apply(pathAsString));
      }
    };
  }

  public static CxxLinkAndCompileRules createBuildRulesForCxxBinaryDescriptionArg(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxBinaryDescription.CommonArg args,
      ImmutableSet<BuildTarget> extraDeps,
      Optional<StripStyle> stripStyle,
      Optional<LinkerMapMode> flavoredLinkerMapMode)
      throws NoSuchBuildTargetException {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ImmutableMap<String, CxxSource> srcs =
        parseCxxSources(
            params.getBuildTarget(), resolver, ruleFinder, pathResolver, cxxPlatform, args);
    ImmutableMap<Path, SourcePath> headers =
        parseHeaders(
            params.getBuildTarget(),
            resolver,
            ruleFinder,
            pathResolver,
            Optional.of(cxxPlatform),
            args);

    // Build the binary deps.
    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
    // Add original declared and extra deps.
    args.getCxxDeps().get(resolver, cxxPlatform).forEach(depsBuilder::add);
    // Add in deps found via deps query.
    args.getDepsQuery()
        .ifPresent(
            query ->
                QueryUtils.resolveDepQuery(
                        params.getBuildTarget(),
                        query,
                        resolver,
                        cellRoots,
                        targetGraph,
                        args.getDeps())
                    .forEach(depsBuilder::add));
    // Add any extra deps passed in.
    extraDeps.stream().map(resolver::getRule).forEach(depsBuilder::add);
    ImmutableSortedSet<BuildRule> deps = depsBuilder.build();

    return createBuildRulesForCxxBinary(
        params,
        resolver,
        cellRoots,
        cxxBuckConfig,
        cxxPlatform,
        srcs,
        headers,
        deps,
        stripStyle,
        flavoredLinkerMapMode,
        args.getLinkStyle().orElse(Linker.LinkableDepType.STATIC),
        args.getThinLto(),
        args.getPreprocessorFlags(),
        args.getPlatformPreprocessorFlags(),
        args.getLangPreprocessorFlags(),
        args.getFrameworks(),
        args.getLibraries(),
        args.getCompilerFlags(),
        args.getLangCompilerFlags(),
        args.getPlatformCompilerFlags(),
        args.getPrefixHeader(),
        args.getPrecompiledHeader(),
        args.getLinkerFlags(),
        args.getPlatformLinkerFlags(),
        args.getCxxRuntimeType(),
        args.getIncludeDirs(),
        Optional.empty());
  }

  public static CxxLinkAndCompileRules createBuildRulesForCxxBinary(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, CxxSource> srcs,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableSortedSet<BuildRule> deps,
      Optional<StripStyle> stripStyle,
      Optional<LinkerMapMode> flavoredLinkerMapMode,
      Linker.LinkableDepType linkStyle,
      boolean thinLto,
      ImmutableList<String> preprocessorFlags,
      PatternMatchedCollection<ImmutableList<String>> platformPreprocessorFlags,
      ImmutableMap<CxxSource.Type, ImmutableList<String>> langPreprocessorFlags,
      ImmutableSortedSet<FrameworkPath> frameworks,
      ImmutableSortedSet<FrameworkPath> libraries,
      ImmutableList<String> compilerFlags,
      ImmutableMap<CxxSource.Type, ImmutableList<String>> langCompilerFlags,
      PatternMatchedCollection<ImmutableList<String>> platformCompilerFlags,
      Optional<SourcePath> prefixHeader,
      Optional<SourcePath> precompiledHeader,
      ImmutableList<StringWithMacros> linkerFlags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformLinkerFlags,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType,
      ImmutableList<String> includeDirs,
      Optional<Boolean> xcodePrivateHeadersSymlinks)
      throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);
    //    TODO(beefon): should be:
    //    Path linkOutput = getLinkOutputPath(
    //        createCxxLinkTarget(params.getBuildTarget(), flavoredLinkerMapMode),
    //        params.getProjectFilesystem());

    BuildTarget target = params.getBuildTarget();
    if (flavoredLinkerMapMode.isPresent()) {
      target = target.withAppendedFlavors(flavoredLinkerMapMode.get().getFlavor());
    }
    Path linkOutput =
        getBinaryOutputPath(
            target, params.getProjectFilesystem(), cxxPlatform.getBinaryExtension());
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();
    CommandTool.Builder executableBuilder = new CommandTool.Builder();

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    boolean shouldCreatePrivateHeadersSymlinks =
        xcodePrivateHeadersSymlinks.orElse(cxxBuckConfig.getPrivateHeadersSymlinksEnabled());
    HeaderSymlinkTree headerSymlinkTree =
        requireHeaderSymlinkTree(
            params,
            resolver,
            cxxPlatform,
            headers,
            HeaderVisibility.PRIVATE,
            shouldCreatePrivateHeadersSymlinks);
    Optional<SymlinkTree> sandboxTree = Optional.empty();
    if (cxxBuckConfig.sandboxSources()) {
      sandboxTree = createSandboxTree(params, resolver, cxxPlatform);
    }
    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput =
        collectCxxPreprocessorInput(
            params,
            cxxPlatform,
            deps,
            CxxFlags.getLanguageFlags(
                preprocessorFlags, platformPreprocessorFlags, langPreprocessorFlags, cxxPlatform),
            ImmutableList.of(headerSymlinkTree),
            frameworks,
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                cxxPlatform,
                RichStream.from(deps)
                    .filter(CxxPreprocessorDep.class::isInstance)
                    .toImmutableList()),
            includeDirs,
            sandboxTree);

    ImmutableList.Builder<String> compilerFlagsWithLto = ImmutableList.builder();
    compilerFlagsWithLto.addAll(compilerFlags);
    if (thinLto) {
      compilerFlagsWithLto.add("-flto=thin");
    }
    // Generate and add all the build rules to preprocess and compile the source to the
    // resolver and get the `SourcePath`s representing the generated object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        CxxSourceRuleFactory.requirePreprocessAndCompileRules(
            params,
            resolver,
            sourcePathResolver,
            ruleFinder,
            cxxBuckConfig,
            cxxPlatform,
            cxxPreprocessorInput,
            CxxFlags.getLanguageFlags(
                compilerFlagsWithLto.build(),
                platformCompilerFlags,
                langCompilerFlags,
                cxxPlatform),
            prefixHeader,
            precompiledHeader,
            srcs,
            linkStyle == Linker.LinkableDepType.STATIC
                ? CxxSourceRuleFactory.PicType.PDC
                : CxxSourceRuleFactory.PicType.PIC,
            sandboxTree);

    // Build up the linker flags, which support macro expansion.
    argsBuilder.addAll(
        toStringWithMacrosArgs(
            target,
            cellRoots,
            resolver,
            cxxPlatform,
            CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                linkerFlags, platformLinkerFlags, cxxPlatform)));

    // Special handling for dynamically linked binaries.
    if (linkStyle == Linker.LinkableDepType.SHARED) {

      // Create a symlink tree with for all shared libraries needed by this binary.
      SymlinkTree sharedLibraries =
          requireSharedLibrarySymlinkTree(
              params.getBuildTarget(),
              params.getProjectFilesystem(),
              resolver,
              ruleFinder,
              cxxPlatform,
              deps,
              NativeLinkable.class::isInstance);

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
    ImmutableList<SourcePathArg> objectArgs =
        SourcePathArg.from(objects.values())
            .stream()
            .map(
                input -> {
                  Preconditions.checkArgument(input instanceof SourcePathArg);
                  return (SourcePathArg) input;
                })
            .collect(MoreCollectors.toImmutableList());
    argsBuilder.addAll(FileListableLinkerInputArg.from(objectArgs));

    BuildTarget linkRuleTarget =
        createCxxLinkTarget(params.getBuildTarget(), flavoredLinkerMapMode);

    CxxLink cxxLink =
        createCxxLinkRule(
            params,
            resolver,
            cxxBuckConfig,
            cxxPlatform,
            RichStream.from(deps).filter(NativeLinkable.class).toImmutableList(),
            linkStyle,
            thinLto,
            frameworks,
            libraries,
            cxxRuntimeType,
            sourcePathResolver,
            ruleFinder,
            linkOutput,
            argsBuilder,
            linkRuleTarget);

    BuildRule binaryRuleForExecutable;
    Optional<CxxStrip> cxxStrip = Optional.empty();
    if (stripStyle.isPresent()) {
      BuildRuleParams cxxParams = params;
      if (flavoredLinkerMapMode.isPresent()) {
        cxxParams = params.withAppendedFlavor(flavoredLinkerMapMode.get().getFlavor());
      }
      CxxStrip stripRule =
          createCxxStripRule(cxxParams, resolver, stripStyle.get(), cxxLink, cxxPlatform);
      cxxStrip = Optional.of(stripRule);
      binaryRuleForExecutable = stripRule;
    } else {
      binaryRuleForExecutable = cxxLink;
    }

    // Add the output of the link as the lone argument needed to invoke this binary as a tool.
    executableBuilder.addArg(SourcePathArg.of(binaryRuleForExecutable.getSourcePathToOutput()));

    return new CxxLinkAndCompileRules(
        cxxLink,
        cxxStrip,
        ImmutableSortedSet.copyOf(objects.keySet()),
        executableBuilder.build(),
        deps);
  }

  private static CxxLink createCxxLinkRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      Iterable<? extends NativeLinkable> deps,
      Linker.LinkableDepType linkStyle,
      boolean thinLto,
      ImmutableSortedSet<FrameworkPath> frameworks,
      ImmutableSortedSet<FrameworkPath> libraries,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      Path linkOutput,
      ImmutableList.Builder<Arg> argsBuilder,
      BuildTarget linkRuleTarget)
      throws NoSuchBuildTargetException {
    CxxLink cxxLink;
    Optional<BuildRule> existingCxxLinkRule = resolver.getRuleOptional(linkRuleTarget);
    if (existingCxxLinkRule.isPresent()) {
      Preconditions.checkArgument(existingCxxLinkRule.get() instanceof CxxLink);
      cxxLink = (CxxLink) existingCxxLinkRule.get();
    } else {
      // Generate the final link rule.  We use the top-level target as the link rule's
      // target, so that it corresponds to the actual binary we build.
      cxxLink =
          CxxLinkableEnhancer.createCxxLinkableBuildRule(
              cxxBuckConfig,
              cxxPlatform,
              params,
              resolver,
              sourcePathResolver,
              ruleFinder,
              linkRuleTarget,
              Linker.LinkType.EXECUTABLE,
              Optional.empty(),
              linkOutput,
              linkStyle,
              thinLto,
              deps,
              cxxRuntimeType,
              Optional.empty(),
              ImmutableSet.of(),
              NativeLinkableInput.builder()
                  .setArgs(argsBuilder.build())
                  .setFrameworks(frameworks)
                  .setLibraries(libraries)
                  .build(),
              Optional.empty());
      resolver.addToIndex(cxxLink);
    }
    return cxxLink;
  }

  public static CxxStrip createCxxStripRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      StripStyle stripStyle,
      BuildRule unstrippedBinaryRule,
      CxxPlatform cxxPlatform) {
    BuildRuleParams stripRuleParams =
        params
            .withBuildTarget(
                params
                    .getBuildTarget()
                    .withAppendedFlavors(CxxStrip.RULE_FLAVOR, stripStyle.getFlavor()))
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.of(unstrippedBinaryRule)),
                Suppliers.ofInstance(ImmutableSortedSet.of()));
    Optional<BuildRule> exisitingRule = resolver.getRuleOptional(stripRuleParams.getBuildTarget());
    if (exisitingRule.isPresent()) {
      Preconditions.checkArgument(exisitingRule.get() instanceof CxxStrip);
      return (CxxStrip) exisitingRule.get();
    } else {
      CxxStrip cxxStrip =
          new CxxStrip(
              stripRuleParams,
              stripStyle,
              Preconditions.checkNotNull(unstrippedBinaryRule.getSourcePathToOutput()),
              cxxPlatform.getStrip(),
              CxxDescriptionEnhancer.getBinaryOutputPath(
                  stripRuleParams.getBuildTarget(),
                  params.getProjectFilesystem(),
                  cxxPlatform.getBinaryExtension()));
      resolver.addToIndex(cxxStrip);
      return cxxStrip;
    }
  }

  public static BuildRule createUberCompilationDatabase(
      BuildRuleParams params, BuildRuleResolver ruleResolver) throws NoSuchBuildTargetException {
    Optional<CxxCompilationDatabaseDependencies> compilationDatabases =
        ruleResolver.requireMetadata(
            params
                .withoutFlavor(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)
                .withAppendedFlavor(CxxCompilationDatabase.COMPILATION_DATABASE)
                .getBuildTarget(),
            CxxCompilationDatabaseDependencies.class);
    Preconditions.checkState(compilationDatabases.isPresent());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    return new JsonConcatenate(
        params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.copyOf(
                    ruleFinder.filterBuildRuleInputs(compilationDatabases.get().getSourcePaths()))),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        pathResolver.getAllAbsolutePaths(compilationDatabases.get().getSourcePaths()),
        "compilation-database-concatenate",
        "Concatenate compilation databases",
        "uber-compilation-database",
        "compile_commands.json");
  }

  public static Optional<CxxCompilationDatabaseDependencies> createCompilationDatabaseDependencies(
      BuildTarget buildTarget,
      FlavorDomain<CxxPlatform> platforms,
      BuildRuleResolver resolver,
      CxxConstructorArg args)
      throws NoSuchBuildTargetException {
    Preconditions.checkState(
        buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE));
    Optional<Flavor> cxxPlatformFlavor = platforms.getFlavor(buildTarget);
    Preconditions.checkState(
        cxxPlatformFlavor.isPresent(),
        "Could not find cxx platform in:\n%s",
        Joiner.on(", ").join(buildTarget.getFlavors()));
    ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
    for (BuildTarget dep : args.getDeps()) {
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
    BuildRule buildRule = resolver.requireRule(buildTarget);
    sourcePaths.add(buildRule.getSourcePathToOutput());
    return Optional.of(CxxCompilationDatabaseDependencies.of(sourcePaths.build()));
  }

  public static Optional<SymlinkTree> createSandboxTree(
      BuildRuleParams params, BuildRuleResolver ruleResolver, CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    return Optional.of(requireSandboxSymlinkTree(params, ruleResolver, cxxPlatform));
  }

  /**
   * @return the {@link BuildTarget} to use for the {@link BuildRule} generating the symlink tree of
   *     shared libraries.
   */
  public static BuildTarget createSharedLibrarySymlinkTreeTarget(
      BuildTarget target, Flavor platform) {
    return BuildTarget.builder(target)
        .addFlavors(SHARED_LIBRARY_SYMLINK_TREE_FLAVOR)
        .addFlavors(platform)
        .build();
  }

  /** @return the {@link Path} to use for the symlink tree of headers. */
  public static Path getSharedLibrarySymlinkTreePath(
      ProjectFilesystem filesystem, BuildTarget target, Flavor platform) {
    return BuildTargets.getGenPath(
        filesystem, createSharedLibrarySymlinkTreeTarget(target, platform), "%s");
  }

  /**
   * Build a {@link HeaderSymlinkTree} of all the shared libraries found via the top-level rule's
   * transitive dependencies.
   */
  public static SymlinkTree createSharedLibrarySymlinkTree(
      SourcePathRuleFinder ruleFinder,
      BuildTarget baseBuildTarget,
      ProjectFilesystem filesystem,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> deps,
      Predicate<Object> traverse,
      Predicate<Object> skip)
      throws NoSuchBuildTargetException {

    BuildTarget symlinkTreeTarget =
        createSharedLibrarySymlinkTreeTarget(baseBuildTarget, cxxPlatform.getFlavor());
    Path symlinkTreeRoot =
        getSharedLibrarySymlinkTreePath(filesystem, baseBuildTarget, cxxPlatform.getFlavor());

    ImmutableSortedMap<String, SourcePath> libraries =
        NativeLinkables.getTransitiveSharedLibraries(cxxPlatform, deps, traverse, skip);

    ImmutableMap.Builder<Path, SourcePath> links = ImmutableMap.builder();
    for (Map.Entry<String, SourcePath> ent : libraries.entrySet()) {
      links.put(Paths.get(ent.getKey()), ent.getValue());
    }
    return new SymlinkTree(
        symlinkTreeTarget, filesystem, symlinkTreeRoot, links.build(), ruleFinder);
  }

  public static SymlinkTree createSharedLibrarySymlinkTree(
      SourcePathRuleFinder ruleFinder,
      BuildTarget baseBuildTarget,
      ProjectFilesystem filesystem,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> deps,
      Predicate<Object> traverse)
      throws NoSuchBuildTargetException {
    return createSharedLibrarySymlinkTree(
        ruleFinder, baseBuildTarget, filesystem, cxxPlatform, deps, traverse, x -> false);
  }

  public static SymlinkTree requireSharedLibrarySymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> deps,
      Predicate<Object> traverse)
      throws NoSuchBuildTargetException {
    BuildTarget target = createSharedLibrarySymlinkTreeTarget(buildTarget, cxxPlatform.getFlavor());
    SymlinkTree tree = resolver.getRuleOptionalWithType(target, SymlinkTree.class).orElse(null);
    if (tree == null) {
      tree =
          resolver.addToIndex(
              createSharedLibrarySymlinkTree(
                  ruleFinder, buildTarget, filesystem, cxxPlatform, deps, traverse));
    }
    return tree;
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
    throw new RuntimeException(String.format("Unsupported LinkableDepType: '%s'", linkableDepType));
  }

  public static SymlinkTree createSandboxTreeBuildRule(
      BuildRuleResolver resolver,
      CxxConstructorArg args,
      CxxPlatform platform,
      BuildRuleParams params)
      throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);
    ImmutableCollection<SourcePath> privateHeaders =
        parseHeaders(
                params.getBuildTarget(),
                resolver,
                ruleFinder,
                sourcePathResolver,
                Optional.of(platform),
                args)
            .values();
    ImmutableCollection<CxxSource> sources =
        parseCxxSources(
                params.getBuildTarget(), resolver, ruleFinder, sourcePathResolver, platform, args)
            .values();
    HashMap<Path, SourcePath> links = new HashMap<>();
    for (SourcePath headerPath : privateHeaders) {
      links.put(
          Paths.get(sourcePathResolver.getSourcePathName(params.getBuildTarget(), headerPath)),
          headerPath);
    }
    if (args instanceof CxxLibraryDescription.CommonArg) {
      ImmutableCollection<SourcePath> publicHeaders =
          CxxDescriptionEnhancer.parseExportedHeaders(
                  params.getBuildTarget(),
                  resolver,
                  ruleFinder,
                  sourcePathResolver,
                  Optional.of(platform),
                  (CxxLibraryDescription.CommonArg) args)
              .values();
      for (SourcePath headerPath : publicHeaders) {
        links.put(
            Paths.get(sourcePathResolver.getSourcePathName(params.getBuildTarget(), headerPath)),
            headerPath);
      }
    }
    for (CxxSource source : sources) {
      SourcePath sourcePath = source.getPath();
      links.put(
          Paths.get(sourcePathResolver.getSourcePathName(params.getBuildTarget(), sourcePath)),
          sourcePath);
    }
    return createSandboxSymlinkTree(params, platform, ImmutableMap.copyOf(links), ruleFinder);
  }

  /** Resolve the map of names to SourcePaths to a map of names to CxxSource objects. */
  private static ImmutableMap<String, CxxSource> resolveCxxSources(
      ImmutableMap<String, SourceWithFlags> sources) {

    ImmutableMap.Builder<String, CxxSource> cxxSources = ImmutableMap.builder();

    // For each entry in the input C/C++ source, build a CxxSource object to wrap
    // it's name, input path, and output object file path.
    for (ImmutableMap.Entry<String, SourceWithFlags> ent : sources.entrySet()) {
      String extension = Files.getFileExtension(ent.getKey());
      Optional<CxxSource.Type> type = CxxSource.Type.fromExtension(extension);
      if (!type.isPresent()) {
        throw new HumanReadableException("invalid extension \"%s\": %s", extension, ent.getKey());
      }
      cxxSources.put(
          ent.getKey(),
          CxxSource.of(type.get(), ent.getValue().getSourcePath(), ent.getValue().getFlags()));
    }

    return cxxSources.build();
  }

  public static ImmutableList<StringWithMacrosArg> toStringWithMacrosArgs(
      BuildTarget target,
      CellPathResolver cellPathResolver,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      Iterable<StringWithMacros> flags) {
    ImmutableList.Builder<StringWithMacrosArg> args = ImmutableList.builder();
    for (StringWithMacros flag : flags) {
      args.add(
          StringWithMacrosArg.of(
              flag,
              ImmutableList.of(new CxxLocationMacroExpander(cxxPlatform)),
              target,
              cellPathResolver,
              resolver));
    }
    return args.build();
  }

  public static String normalizeModuleName(String moduleName) {
    return moduleName.replaceAll("[^A-Za-z0-9]", "_");
  }
}
