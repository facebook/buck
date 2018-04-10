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

import com.facebook.buck.cxx.AbstractCxxSource.Type;
import com.facebook.buck.cxx.CxxBinaryDescription.CommonArg;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.CxxRuntimeType;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.cxx.toolchain.linker.Linkers;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.json.JsonConcatenate;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.UserFlavor;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.AbstractMacroExpanderWithoutPrecomputedWork;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.OutputMacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.shell.ExportFile;
import com.facebook.buck.shell.ExportFileDescription.Mode;
import com.facebook.buck.shell.ExportFileDirectoryAction;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimaps;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Function;
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
  public static final Flavor BINARY_WITH_SHARED_LIBRARIES_SYMLINK_TREE_FLAVOR =
      InternalFlavor.of("binary-with-shared-libraries-symlink-tree");

  public static final Flavor CXX_LINK_BINARY_FLAVOR = InternalFlavor.of("binary");

  public static final Flavor CXX_LINK_MAP_FLAVOR = UserFlavor.of("linkmap", "LinkMap file");

  private static final Pattern SONAME_EXT_MACRO_PATTERN =
      Pattern.compile("\\$\\(ext(?: ([.0-9]+))?\\)");

  private CxxDescriptionEnhancer() {}

  public static HeaderMode getHeaderModeForPlatform(
      BuildRuleResolver resolver, CxxPlatform cxxPlatform, boolean shouldCreateHeadersSymlinks) {
    boolean useHeaderMap =
        (cxxPlatform.getCpp().resolve(resolver).supportsHeaderMaps()
            && cxxPlatform.getCxxpp().resolve(resolver).supportsHeaderMaps());
    return !useHeaderMap
        ? HeaderMode.SYMLINK_TREE_ONLY
        : (shouldCreateHeadersSymlinks
            ? HeaderMode.SYMLINK_TREE_WITH_HEADER_MAP
            : HeaderMode.HEADER_MAP_ONLY);
  }

  public static HeaderSymlinkTree createHeaderSymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      HeaderMode mode,
      ImmutableMap<Path, SourcePath> headers,
      HeaderVisibility headerVisibility,
      Flavor... flavors) {
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            buildTarget, headerVisibility, flavors);
    Path headerSymlinkTreeRoot =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            projectFilesystem, buildTarget, headerVisibility, flavors);
    return CxxPreprocessables.createHeaderSymlinkTreeBuildRule(
        headerSymlinkTreeTarget,
        projectFilesystem,
        ruleFinder,
        headerSymlinkTreeRoot,
        headers,
        mode);
  }

  public static HeaderSymlinkTree createHeaderSymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<Path, SourcePath> headers,
      HeaderVisibility headerVisibility,
      boolean shouldCreateHeadersSymlinks) {
    return createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        getHeaderModeForPlatform(resolver, cxxPlatform, shouldCreateHeadersSymlinks),
        headers,
        headerVisibility,
        cxxPlatform.getFlavor());
  }

  public static SymlinkTree createSandboxSymlinkTree(
      BuildTarget baseBuildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      ImmutableMap<Path, SourcePath> map) {
    BuildTarget sandboxSymlinkTreeTarget =
        CxxDescriptionEnhancer.createSandboxSymlinkTreeTarget(
            baseBuildTarget, cxxPlatform.getFlavor());
    Path sandboxSymlinkTreeRoot =
        CxxDescriptionEnhancer.getSandboxSymlinkTreePath(
            projectFilesystem, sandboxSymlinkTreeTarget);

    return new SymlinkTree(
        "cxx_sandbox",
        sandboxSymlinkTreeTarget,
        projectFilesystem,
        sandboxSymlinkTreeRoot,
        map,
        ImmutableMultimap.of(),
        ruleFinder);
  }

  public static HeaderSymlinkTree requireHeaderSymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<Path, SourcePath> headers,
      HeaderVisibility headerVisibility,
      boolean shouldCreateHeadersSymlinks) {
    BuildTarget untypedTarget = CxxLibraryDescription.getUntypedBuildTarget(buildTarget);

    return (HeaderSymlinkTree)
        ruleResolver.computeIfAbsent(
            // TODO(yiding): this build target gets recomputed in createHeaderSymlinkTree, it should
            // be passed down instead.
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                untypedTarget, headerVisibility, cxxPlatform.getFlavor()),
            (ignored) ->
                createHeaderSymlinkTree(
                    untypedTarget,
                    projectFilesystem,
                    ruleFinder,
                    ruleResolver,
                    cxxPlatform,
                    headers,
                    headerVisibility,
                    shouldCreateHeadersSymlinks));
  }

  private static SymlinkTree requireSandboxSymlinkTree(
      BuildTarget buildTarget, BuildRuleResolver ruleResolver, CxxPlatform cxxPlatform) {
    BuildTarget untypedTarget = CxxLibraryDescription.getUntypedBuildTarget(buildTarget);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createSandboxSymlinkTreeTarget(
            untypedTarget, cxxPlatform.getFlavor());
    BuildRule rule = ruleResolver.requireRule(headerSymlinkTreeTarget);
    Preconditions.checkState(
        rule instanceof SymlinkTree, rule.getBuildTarget() + " " + rule.getClass());
    return (SymlinkTree) rule;
  }

  /**
   * @return the {@link BuildTarget} to use for the {@link BuildRule} generating the symlink tree of
   *     headers.
   */
  @VisibleForTesting
  public static BuildTarget createHeaderSymlinkTreeTarget(
      BuildTarget target, HeaderVisibility headerVisibility, Flavor... flavors) {
    return target.withAppendedFlavors(
        ImmutableSet.<Flavor>builder()
            .add(getHeaderSymlinkTreeFlavor(headerVisibility))
            .add(flavors)
            .build());
  }

  @VisibleForTesting
  public static BuildTarget createSandboxSymlinkTreeTarget(BuildTarget target, Flavor platform) {
    return target.withAppendedFlavors(platform, SANDBOX_TREE_FLAVOR);
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
      PatternMatchedCollection<SourceList> platformHeaders) {
    ImmutableMap.Builder<String, SourcePath> parsed = ImmutableMap.builder();

    Function<SourcePath, SourcePath> fixup =
        path -> {
          return CxxGenruleDescription.fixupSourcePath(resolver, ruleFinder, cxxPlatform, path);
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
      CxxConstructorArg args) {
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
      CxxLibraryDescription.CommonArg args) {
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
      CxxLibraryDescription.CommonArg args) {
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
                      return s.withSourcePath(
                          CxxGenruleDescription.fixupSourcePath(
                              resolver,
                              ruleFinder,
                              cxxPlatform,
                              Preconditions.checkNotNull(s.getSourcePath())));
                    })
                .collect(ImmutableList.toImmutableList()),
            x -> true,
            SourceWithFlags::getSourcePath));
  }

  public static ImmutableList<CxxPreprocessorInput> collectCxxPreprocessorInput(
      BuildTarget target,
      CxxPlatform cxxPlatform,
      BuildRuleResolver ruleResolver,
      Iterable<BuildRule> deps,
      ImmutableMultimap<CxxSource.Type, ? extends Arg> preprocessorFlags,
      ImmutableList<HeaderSymlinkTree> headerSymlinkTrees,
      ImmutableSet<FrameworkPath> frameworks,
      Iterable<CxxPreprocessorInput> cxxPreprocessorInputFromDeps,
      ImmutableList<String> includeDirs,
      Optional<SymlinkTree> symlinkTree,
      ImmutableSortedSet<SourcePath> rawHeaders) {

    // Add the private includes of any rules which this rule depends on, and which list this rule as
    // a test.
    BuildTarget targetWithoutFlavor = BuildTarget.of(target.getUnflavoredBuildTarget());
    ImmutableList.Builder<CxxPreprocessorInput> cxxPreprocessorInputFromTestedRulesBuilder =
        ImmutableList.builder();
    for (BuildRule rule : deps) {
      if (rule instanceof NativeTestable) {
        NativeTestable testable = (NativeTestable) rule;
        if (testable.isTestedBy(targetWithoutFlavor)) {
          LOG.debug(
              "Adding private includes of tested rule %s to testing rule %s",
              rule.getBuildTarget(), target);
          cxxPreprocessorInputFromTestedRulesBuilder.add(
              testable.getPrivateCxxPreprocessorInput(cxxPlatform, ruleResolver));

          // Add any dependent headers
          cxxPreprocessorInputFromTestedRulesBuilder.addAll(
              CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                  cxxPlatform, ruleResolver, ImmutableList.of(rule)));
        }
      }
    }

    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInputFromTestedRules =
        cxxPreprocessorInputFromTestedRulesBuilder.build();
    LOG.verbose(
        "Rules tested by target %s added private includes %s",
        target, cxxPreprocessorInputFromTestedRules);

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

    if (!rawHeaders.isEmpty()) {
      builder.addIncludes(CxxRawHeaders.of(rawHeaders));
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
      BuildTarget target, Flavor platform, PicType pic) {
    return target.withAppendedFlavors(
        platform, pic == PicType.PDC ? STATIC_FLAVOR : STATIC_PIC_FLAVOR);
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
    return target.withAppendedFlavors(platform, linkFlavor);
  }

  public static Path getStaticLibraryPath(
      ProjectFilesystem filesystem,
      BuildTarget target,
      Flavor platform,
      PicType pic,
      Optional<String> staticLibraryBasename,
      String extension,
      boolean uniqueLibraryNameEnabled) {
    return getStaticLibraryPath(
        filesystem,
        target,
        platform,
        pic,
        staticLibraryBasename,
        extension,
        "",
        uniqueLibraryNameEnabled);
  }

  public static String getStaticLibraryBasename(
      BuildTarget target, String suffix, boolean uniqueLibraryNameEnabled) {
    String postfix = "";
    if (uniqueLibraryNameEnabled) {
      String hashedPath =
          BaseEncoding.base64Url()
              .omitPadding()
              .encode(
                  Hashing.sha1()
                      .hashString(
                          target.getUnflavoredBuildTarget().getFullyQualifiedName(), Charsets.UTF_8)
                      .asBytes())
              .substring(0, 10);
      postfix = "-" + hashedPath;
    }
    return target.getShortName() + postfix + suffix;
  }

  public static Path getStaticLibraryPath(
      ProjectFilesystem filesystem,
      BuildTarget target,
      Flavor platform,
      PicType pic,
      Optional<String> staticLibraryBasename,
      String extension,
      String suffix,
      boolean uniqueLibraryNameEnabled) {
    String basename;
    if (staticLibraryBasename.isPresent()) {
      basename = staticLibraryBasename.get();
    } else {
      basename = getStaticLibraryBasename(target, suffix, uniqueLibraryNameEnabled);
    }
    String name = String.format("lib%s.%s", basename, extension);
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
      CxxPlatform cxxPlatform, SourcePathResolver resolver) {
    return new FrameworkPathToSearchPathFunction(cxxPlatform, resolver);
  }

  private static class FrameworkPathToSearchPathFunction
      implements RuleKeyAppendableFunction<FrameworkPath, Path> {
    private final SourcePathResolver resolver;
    @AddToRuleKey private final RuleKeyAppendableFunction<String, String> translateMacrosFn;

    public FrameworkPathToSearchPathFunction(CxxPlatform cxxPlatform, SourcePathResolver resolver) {
      this.resolver = resolver;
      this.translateMacrosFn =
          new CxxFlags.TranslateMacrosAppendableFunction(
              ImmutableSortedMap.copyOf(cxxPlatform.getFlagMacros()), cxxPlatform);
    }

    @Override
    public void appendToRuleKey(RuleKeyObjectSink sink) {}

    @Override
    public Path apply(FrameworkPath input) {
      String pathAsString =
          FrameworkPath.getUnexpandedSearchPath(
                  resolver::getAbsolutePath, Functions.identity(), input)
              .toString();
      return Paths.get(translateMacrosFn.apply(pathAsString));
    }
  }

  public static CxxLinkAndCompileRules createBuildRulesForCxxBinaryDescriptionArg(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CommonArg args,
      ImmutableSet<BuildTarget> extraDeps,
      Optional<StripStyle> stripStyle,
      Optional<LinkerMapMode> flavoredLinkerMapMode) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ImmutableMap<String, CxxSource> srcs =
        parseCxxSources(target, resolver, ruleFinder, pathResolver, cxxPlatform, args);
    ImmutableMap<Path, SourcePath> headers =
        parseHeaders(target, resolver, ruleFinder, pathResolver, Optional.of(cxxPlatform), args);

    // Build the binary deps.
    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
    // Add original declared and extra deps.
    args.getCxxDeps().get(resolver, cxxPlatform).forEach(depsBuilder::add);
    // Add in deps found via deps query.
    ImmutableList<BuildRule> depQueryDeps =
        args.getDepsQuery()
            .map(query -> Preconditions.checkNotNull(query.getResolvedQuery()))
            .orElse(ImmutableSortedSet.of())
            .stream()
            .map(resolver::getRule)
            .collect(ImmutableList.toImmutableList());
    depsBuilder.addAll(depQueryDeps);
    // Add any extra deps passed in.
    extraDeps.stream().map(resolver::getRule).forEach(depsBuilder::add);
    ImmutableSortedSet<BuildRule> deps = depsBuilder.build();

    CxxLinkOptions linkOptions =
        CxxLinkOptions.of(
            args.getThinLto()
            );
    return createBuildRulesForCxxBinary(
        target,
        projectFilesystem,
        resolver,
        cellRoots,
        cxxBuckConfig,
        cxxPlatform,
        srcs,
        headers,
        deps,
        args.getLinkDepsQueryWhole()
            ? RichStream.from(depQueryDeps).map(BuildRule::getBuildTarget).toImmutableSet()
            : ImmutableSet.of(),
        stripStyle,
        flavoredLinkerMapMode,
        args.getLinkStyle().orElse(Linker.LinkableDepType.STATIC),
        linkOptions,
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
        args.getLinkerExtraOutputs(),
        args.getPlatformLinkerFlags(),
        args.getCxxRuntimeType(),
        args.getIncludeDirs(),
        args.getRawHeaders());
  }

  public static CxxLinkAndCompileRules createBuildRulesForCxxBinary(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, CxxSource> srcs,
      ImmutableMap<Path, SourcePath> headers,
      SortedSet<BuildRule> deps,
      ImmutableSet<BuildTarget> linkWholeDeps,
      Optional<StripStyle> stripStyle,
      Optional<LinkerMapMode> flavoredLinkerMapMode,
      LinkableDepType linkStyle,
      CxxLinkOptions linkOptions,
      ImmutableList<StringWithMacros> preprocessorFlags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformPreprocessorFlags,
      ImmutableMap<Type, ImmutableList<StringWithMacros>> langPreprocessorFlags,
      ImmutableSortedSet<FrameworkPath> frameworks,
      ImmutableSortedSet<FrameworkPath> libraries,
      ImmutableList<StringWithMacros> compilerFlags,
      ImmutableMap<Type, ImmutableList<StringWithMacros>> langCompilerFlags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformCompilerFlags,
      Optional<SourcePath> prefixHeader,
      Optional<SourcePath> precompiledHeader,
      ImmutableList<StringWithMacros> linkerFlags,
      ImmutableList<String> linkerExtraOutputs,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformLinkerFlags,
      Optional<CxxRuntimeType> cxxRuntimeType,
      ImmutableList<String> includeDirs,
      ImmutableSortedSet<SourcePath> rawHeaders) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
    //    TODO(beefon): should be:
    //    Path linkOutput = getLinkOutputPath(
    //        createCxxLinkTarget(params.getBuildTarget(), flavoredLinkerMapMode),
    //        projectFilesystem);

    Path linkOutput =
        getBinaryOutputPath(
            flavoredLinkerMapMode.isPresent()
                ? target.withAppendedFlavors(flavoredLinkerMapMode.get().getFlavor())
                : target,
            projectFilesystem,
            cxxPlatform.getBinaryExtension());

    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();
    CommandTool.Builder executableBuilder = new CommandTool.Builder();

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    boolean shouldCreatePrivateHeadersSymlinks = cxxBuckConfig.getPrivateHeadersSymlinksEnabled();
    HeaderSymlinkTree headerSymlinkTree =
        requireHeaderSymlinkTree(
            target,
            projectFilesystem,
            ruleFinder,
            resolver,
            cxxPlatform,
            headers,
            HeaderVisibility.PRIVATE,
            shouldCreatePrivateHeadersSymlinks);
    Optional<SymlinkTree> sandboxTree = Optional.empty();
    if (cxxBuckConfig.sandboxSources()) {
      sandboxTree = createSandboxTree(target, resolver, cxxPlatform);
    }
    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput =
        collectCxxPreprocessorInput(
            target,
            cxxPlatform,
            resolver,
            deps,
            ImmutableListMultimap.copyOf(
                Multimaps.transformValues(
                    CxxFlags.getLanguageFlagsWithMacros(
                        preprocessorFlags,
                        platformPreprocessorFlags,
                        langPreprocessorFlags,
                        cxxPlatform),
                    f -> toStringWithMacrosArgs(target, cellRoots, resolver, cxxPlatform, f))),
            ImmutableList.of(headerSymlinkTree),
            frameworks,
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                cxxPlatform,
                resolver,
                RichStream.from(deps)
                    .filter(CxxPreprocessorDep.class::isInstance)
                    .toImmutableList()),
            includeDirs,
            sandboxTree,
            rawHeaders);

    ImmutableListMultimap.Builder<CxxSource.Type, Arg> allCompilerFlagsBuilder =
        ImmutableListMultimap.builder();
    allCompilerFlagsBuilder.putAll(
        Multimaps.transformValues(
            CxxFlags.getLanguageFlagsWithMacros(
                compilerFlags, platformCompilerFlags, langCompilerFlags, cxxPlatform),
            f -> toStringWithMacrosArgs(target, cellRoots, resolver, cxxPlatform, f)));
    if (linkOptions.getThinLto()) {
      allCompilerFlagsBuilder.putAll(CxxFlags.toLanguageFlags(StringArg.from("-flto=thin")));
    }
    ImmutableListMultimap<CxxSource.Type, Arg> allCompilerFlags = allCompilerFlagsBuilder.build();

    // Generate and add all the build rules to preprocess and compile the source to the
    // resolver and get the `SourcePath`s representing the generated object files.
    PicType pic =
        linkStyle == Linker.LinkableDepType.STATIC
            ? PicType.PDC
            : cxxPlatform.getPicTypeForSharedLinking();
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        CxxSourceRuleFactory.of(
                projectFilesystem,
                target,
                resolver,
                sourcePathResolver,
                ruleFinder,
                cxxBuckConfig,
                cxxPlatform,
                cxxPreprocessorInput,
                allCompilerFlags,
                prefixHeader,
                precompiledHeader,
                pic,
                sandboxTree)
            .requirePreprocessAndCompileRules(srcs);

    BuildTarget linkRuleTarget = createCxxLinkTarget(target, flavoredLinkerMapMode);

    // Build up the linker flags, which support macro expansion.
    {
      ImmutableList<AbstractMacroExpanderWithoutPrecomputedWork<? extends Macro>> expanders =
          ImmutableList.of(new CxxLocationMacroExpander(cxxPlatform), new OutputMacroExpander());

      StringWithMacrosConverter macrosConverter =
          StringWithMacrosConverter.builder()
              .setBuildTarget(linkRuleTarget)
              .setCellPathResolver(cellRoots)
              .setResolver(resolver)
              .setExpanders(expanders)
              .setSanitizer(getStringWithMacrosArgSanitizer(cxxPlatform))
              .build();
      CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
              linkerFlags, platformLinkerFlags, cxxPlatform)
          .stream()
          .map(macrosConverter::convert)
          .forEach(argsBuilder::add);
    }

    Linker linker = cxxPlatform.getLd().resolve(resolver);

    // Special handling for dynamically linked binaries with rpath support
    if (linkStyle == Linker.LinkableDepType.SHARED
        && linker.getSharedLibraryLoadingType() == Linker.SharedLibraryLoadingType.RPATH) {
      // Create a symlink tree with for all shared libraries needed by this binary.
      SymlinkTree sharedLibraries =
          requireSharedLibrarySymlinkTree(target, projectFilesystem, resolver, cxxPlatform, deps);

      // Embed a origin-relative library path into the binary so it can find the shared libraries.
      // The shared libraries root is absolute. Also need an absolute path to the linkOutput
      Path absLinkOut = target.getCellPath().resolve(linkOutput);
      argsBuilder.addAll(
          StringArg.from(
              Linkers.iXlinker(
                  "-rpath",
                  String.format(
                      "%s/%s",
                      linker.origin(),
                      absLinkOut.getParent().relativize(sharedLibraries.getRoot()).toString()))));

      // Add all the shared libraries and the symlink tree as inputs to the tool that represents
      // this binary, so that users can attach the proper deps.
      executableBuilder.addNonHashableInput(sharedLibraries.getRootSourcePath());
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
            .collect(ImmutableList.toImmutableList());
    argsBuilder.addAll(FileListableLinkerInputArg.from(objectArgs));

    CxxLink cxxLink =
        (CxxLink)
            resolver.computeIfAbsent(
                linkRuleTarget,
                ignored ->
                    // Generate the final link rule.  We use the top-level target as the link rule's
                    // target, so that it corresponds to the actual binary we build.
                    CxxLinkableEnhancer.createCxxLinkableBuildRule(
                        cxxBuckConfig,
                        cxxPlatform,
                        projectFilesystem,
                        resolver,
                        sourcePathResolver,
                        ruleFinder,
                        linkRuleTarget,
                        Linker.LinkType.EXECUTABLE,
                        Optional.empty(),
                        linkOutput,
                        linkerExtraOutputs,
                        linkStyle,
                        linkOptions,
                        RichStream.from(deps).filter(NativeLinkable.class).toImmutableList(),
                        cxxRuntimeType,
                        Optional.empty(),
                        ImmutableSet.of(),
                        linkWholeDeps,
                        NativeLinkableInput.builder()
                            .setArgs(argsBuilder.build())
                            .setFrameworks(frameworks)
                            .setLibraries(libraries)
                            .build(),
                        Optional.empty(),
                        cellRoots));

    BuildRule binaryRuleForExecutable;
    Optional<CxxStrip> cxxStrip = Optional.empty();
    if (stripStyle.isPresent()) {
      BuildTarget cxxTarget = target;
      if (flavoredLinkerMapMode.isPresent()) {
        cxxTarget = cxxTarget.withAppendedFlavors(flavoredLinkerMapMode.get().getFlavor());
      }
      CxxStrip stripRule =
          createCxxStripRule(
              cxxTarget, projectFilesystem, resolver, stripStyle.get(), cxxLink, cxxPlatform);
      cxxStrip = Optional.of(stripRule);
      binaryRuleForExecutable = stripRule;
    } else {
      binaryRuleForExecutable = cxxLink;
    }

    SourcePath sourcePathToExecutable = binaryRuleForExecutable.getSourcePathToOutput();

    // Special handling for dynamically linked binaries requiring dependencies to be in the same
    // directory
    if (linkStyle == Linker.LinkableDepType.SHARED
        && linker.getSharedLibraryLoadingType()
            == Linker.SharedLibraryLoadingType.THE_SAME_DIRECTORY) {
      Path binaryName = linkOutput.getFileName();
      BuildTarget binaryWithSharedLibrariesTarget =
          createBinaryWithSharedLibrariesSymlinkTreeTarget(target, cxxPlatform.getFlavor());
      Path symlinkTreeRoot =
          getBinaryWithSharedLibrariesSymlinkTreePath(
              projectFilesystem, binaryWithSharedLibrariesTarget, cxxPlatform.getFlavor());
      Path appPath = symlinkTreeRoot.resolve(binaryName);
      SymlinkTree binaryWithSharedLibraries =
          requireBinaryWithSharedLibrariesSymlinkTree(
              target,
              projectFilesystem,
              resolver,
              ruleFinder,
              cxxPlatform,
              deps,
              binaryName,
              sourcePathToExecutable);

      executableBuilder.addNonHashableInput(binaryWithSharedLibraries.getRootSourcePath());
      executableBuilder.addInputs(binaryWithSharedLibraries.getLinks().values());
      sourcePathToExecutable =
          ExplicitBuildTargetSourcePath.of(binaryWithSharedLibrariesTarget, appPath);
    }

    // Add the output of the link as the lone argument needed to invoke this binary as a tool.
    executableBuilder.addArg(SourcePathArg.of(sourcePathToExecutable));

    return new CxxLinkAndCompileRules(
        cxxLink,
        cxxStrip,
        ImmutableSortedSet.copyOf(objects.keySet()),
        executableBuilder.build(),
        deps);
  }

  public static CxxStrip createCxxStripRule(
      BuildTarget baseBuildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      StripStyle stripStyle,
      BuildRule unstrippedBinaryRule,
      CxxPlatform cxxPlatform) {
    return (CxxStrip)
        resolver.computeIfAbsent(
            baseBuildTarget.withAppendedFlavors(CxxStrip.RULE_FLAVOR, stripStyle.getFlavor()),
            stripBuildTarget ->
                new CxxStrip(
                    stripBuildTarget,
                    projectFilesystem,
                    Preconditions.checkNotNull(
                        unstrippedBinaryRule.getSourcePathToOutput(),
                        "Cannot strip BuildRule with no output (%s)",
                        unstrippedBinaryRule.getBuildTarget()),
                    new SourcePathRuleFinder(resolver),
                    stripStyle,
                    cxxPlatform.getStrip(),
                    CxxDescriptionEnhancer.getBinaryOutputPath(
                        stripBuildTarget, projectFilesystem, cxxPlatform.getBinaryExtension())));
  }

  public static BuildRule createUberCompilationDatabase(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver) {
    Optional<CxxCompilationDatabaseDependencies> compilationDatabases =
        ruleResolver.requireMetadata(
            buildTarget
                .withoutFlavors(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)
                .withAppendedFlavors(CxxCompilationDatabase.COMPILATION_DATABASE),
            CxxCompilationDatabaseDependencies.class);
    Preconditions.checkState(compilationDatabases.isPresent());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return new JsonConcatenate(
        buildTarget,
        projectFilesystem,
        new BuildRuleParams(
            () ->
                ImmutableSortedSet.copyOf(
                    ruleFinder.filterBuildRuleInputs(compilationDatabases.get().getSourcePaths())),
            () -> ImmutableSortedSet.of(),
            ImmutableSortedSet.of()),
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
      ImmutableSortedSet<BuildTarget> deps) {
    Preconditions.checkState(
        buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE));
    Optional<Flavor> cxxPlatformFlavor = platforms.getFlavor(buildTarget);
    Preconditions.checkState(
        cxxPlatformFlavor.isPresent(),
        "Could not find cxx platform in:\n%s",
        Joiner.on(", ").join(buildTarget.getFlavors()));
    ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
    for (BuildTarget dep : deps) {
      Optional<CxxCompilationDatabaseDependencies> compilationDatabases =
          resolver.requireMetadata(
              dep.withAppendedFlavors(
                  CxxCompilationDatabase.COMPILATION_DATABASE, cxxPlatformFlavor.get()),
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
      BuildTarget buildTarget, BuildRuleResolver ruleResolver, CxxPlatform cxxPlatform) {
    return Optional.of(requireSandboxSymlinkTree(buildTarget, ruleResolver, cxxPlatform));
  }

  /**
   * @return the {@link BuildTarget} to use for the {@link BuildRule} generating the symlink tree of
   *     shared libraries.
   */
  public static BuildTarget createSharedLibrarySymlinkTreeTarget(
      BuildTarget target, Flavor platform) {
    return target.withAppendedFlavors(SHARED_LIBRARY_SYMLINK_TREE_FLAVOR, platform);
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
      BuildTarget baseBuildTarget,
      ProjectFilesystem filesystem,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> deps,
      Function<? super BuildRule, Optional<Iterable<? extends BuildRule>>> passthrough) {

    BuildTarget symlinkTreeTarget =
        createSharedLibrarySymlinkTreeTarget(baseBuildTarget, cxxPlatform.getFlavor());
    Path symlinkTreeRoot =
        getSharedLibrarySymlinkTreePath(filesystem, baseBuildTarget, cxxPlatform.getFlavor());

    ImmutableSortedMap<String, SourcePath> libraries =
        NativeLinkables.getTransitiveSharedLibraries(
            cxxPlatform, ruleResolver, deps, passthrough, false);

    ImmutableMap.Builder<Path, SourcePath> links = ImmutableMap.builder();
    for (Map.Entry<String, SourcePath> ent : libraries.entrySet()) {
      links.put(Paths.get(ent.getKey()), ent.getValue());
    }
    return new SymlinkTree(
        "cxx_binary",
        symlinkTreeTarget,
        filesystem,
        symlinkTreeRoot,
        links.build(),
        ImmutableMultimap.of(),
        ruleFinder);
  }

  public static SymlinkTree requireSharedLibrarySymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> deps) {
    return (SymlinkTree)
        resolver.computeIfAbsent(
            createSharedLibrarySymlinkTreeTarget(buildTarget, cxxPlatform.getFlavor()),
            ignored ->
                createSharedLibrarySymlinkTree(
                    buildTarget,
                    filesystem,
                    resolver,
                    new SourcePathRuleFinder(resolver),
                    cxxPlatform,
                    deps,
                    n -> Optional.empty()));
  }

  private static BuildTarget createBinaryWithSharedLibrariesSymlinkTreeTarget(
      BuildTarget target, Flavor platform) {
    return target.withAppendedFlavors(BINARY_WITH_SHARED_LIBRARIES_SYMLINK_TREE_FLAVOR, platform);
  }

  private static Path getBinaryWithSharedLibrariesSymlinkTreePath(
      ProjectFilesystem filesystem, BuildTarget target, Flavor platform) {
    return BuildTargets.getGenPath(
        filesystem, createBinaryWithSharedLibrariesSymlinkTreeTarget(target, platform), "%s");
  }

  private static SymlinkTree createBinaryWithSharedLibrariesSymlinkTree(
      BuildTarget baseBuildTarget,
      ProjectFilesystem filesystem,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> deps,
      Path binaryName,
      SourcePath binarySource) {

    BuildTarget symlinkTreeTarget =
        createBinaryWithSharedLibrariesSymlinkTreeTarget(baseBuildTarget, cxxPlatform.getFlavor());
    Path symlinkTreeRoot =
        getBinaryWithSharedLibrariesSymlinkTreePath(
            filesystem, baseBuildTarget, cxxPlatform.getFlavor());

    ImmutableSortedMap<String, SourcePath> libraries =
        NativeLinkables.getTransitiveSharedLibraries(
            cxxPlatform, ruleResolver, deps, n -> Optional.empty(), false);

    ImmutableMap.Builder<Path, SourcePath> links = ImmutableMap.builder();
    for (Map.Entry<String, SourcePath> ent : libraries.entrySet()) {
      links.put(Paths.get(ent.getKey()), ent.getValue());
    }
    links.put(binaryName, binarySource);
    return new SymlinkTree(
        "cxx_binary",
        symlinkTreeTarget,
        filesystem,
        symlinkTreeRoot,
        links.build(),
        ImmutableMultimap.of(),
        ruleFinder);
  }

  private static SymlinkTree requireBinaryWithSharedLibrariesSymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> deps,
      Path binaryName,
      SourcePath binarySource) {
    return (SymlinkTree)
        resolver.computeIfAbsent(
            createBinaryWithSharedLibrariesSymlinkTreeTarget(buildTarget, cxxPlatform.getFlavor()),
            ignored ->
                createBinaryWithSharedLibrariesSymlinkTree(
                    buildTarget,
                    filesystem,
                    resolver,
                    ruleFinder,
                    cxxPlatform,
                    deps,
                    binaryName,
                    binarySource));
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
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ImmutableCollection<SourcePath> privateHeaders =
        parseHeaders(
                buildTarget, resolver, ruleFinder, sourcePathResolver, Optional.of(platform), args)
            .values();
    ImmutableCollection<CxxSource> sources =
        parseCxxSources(buildTarget, resolver, ruleFinder, sourcePathResolver, platform, args)
            .values();
    HashMap<Path, SourcePath> links = new HashMap<>();
    for (SourcePath headerPath : privateHeaders) {
      links.put(
          Paths.get(sourcePathResolver.getSourcePathName(buildTarget, headerPath)), headerPath);
    }
    if (args instanceof CxxLibraryDescription.CommonArg) {
      ImmutableCollection<SourcePath> publicHeaders =
          CxxDescriptionEnhancer.parseExportedHeaders(
                  buildTarget,
                  resolver,
                  ruleFinder,
                  sourcePathResolver,
                  Optional.of(platform),
                  (CxxLibraryDescription.CommonArg) args)
              .values();
      for (SourcePath headerPath : publicHeaders) {
        links.put(
            Paths.get(sourcePathResolver.getSourcePathName(buildTarget, headerPath)), headerPath);
      }
    }
    for (CxxSource source : sources) {
      SourcePath sourcePath = source.getPath();
      links.put(
          Paths.get(sourcePathResolver.getSourcePathName(buildTarget, sourcePath)), sourcePath);
    }
    return createSandboxSymlinkTree(
        buildTarget, projectFilesystem, ruleFinder, platform, ImmutableMap.copyOf(links));
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

  public static Arg toStringWithMacrosArgs(
      BuildTarget target,
      CellPathResolver cellPathResolver,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      StringWithMacros flag) {
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(target)
            .setCellPathResolver(cellPathResolver)
            .setResolver(resolver)
            .addExpanders(new CxxLocationMacroExpander(cxxPlatform), new OutputMacroExpander())
            .setSanitizer(getStringWithMacrosArgSanitizer(cxxPlatform))
            .build();
    return macrosConverter.convert(flag);
  }

  private static Function<String, String> getStringWithMacrosArgSanitizer(CxxPlatform platform) {
    return platform.getCompilerDebugPathSanitizer().sanitize(Optional.empty());
  }

  public static String normalizeModuleName(String moduleName) {
    return moduleName.replaceAll("[^A-Za-z0-9]", "_");
  }

  /**
   * @return a {@link BuildRule} that produces a single file that contains linker map produced
   *     during the linking process.
   * @throws HumanReadableException if the linked does not support linker maps.
   */
  public static BuildRule createLinkMap(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      CxxLinkAndCompileRules cxxLinkAndCompileRules) {
    CxxLink cxxLink = cxxLinkAndCompileRules.getCxxLink();
    Optional<Path> linkerMap = cxxLink.getLinkerMapPath();
    if (!linkerMap.isPresent()) {
      throw new HumanReadableException(
          "Linker for target %s does not support linker maps.", target);
    }
    return new ExportFile(
        target,
        projectFilesystem,
        ruleFinder,
        "LinkerMap.txt",
        Mode.COPY,
        ExplicitBuildTargetSourcePath.of(cxxLink.getBuildTarget(), linkerMap.get()),
        ExportFileDirectoryAction.FAIL);
  }
}
