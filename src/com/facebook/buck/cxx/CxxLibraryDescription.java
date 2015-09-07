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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroException;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreIterables;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class CxxLibraryDescription implements
    Description<CxxLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<CxxLibraryDescription.Arg>,
    Flavored {

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.<String, MacroExpander>of(
              "location", new LocationMacroExpander()));

  public enum Type {
    HEADERS,
    EXPORTED_HEADERS,
    SHARED,
    STATIC_PIC,
    STATIC,
    MACH_O_BUNDLE,
  }

  public static final BuildRuleType TYPE = BuildRuleType.of("cxx_library");

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      new FlavorDomain<>(
          "C/C++ Library Type",
          ImmutableMap.<Flavor, Type>builder()
              .put(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR, Type.HEADERS)
              .put(
                  CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
                  Type.EXPORTED_HEADERS)
              .put(CxxDescriptionEnhancer.SHARED_FLAVOR, Type.SHARED)
              .put(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR, Type.STATIC_PIC)
              .put(CxxDescriptionEnhancer.STATIC_FLAVOR, Type.STATIC)
              .put(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR, Type.MACH_O_BUNDLE)
              .build());

  private final CxxBuckConfig cxxBuckConfig;
  private final InferBuckConfig inferBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxPreprocessMode preprocessMode;

  public CxxLibraryDescription(
      CxxBuckConfig cxxBuckConfig,
      InferBuckConfig inferBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxPreprocessMode preprocessMode) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.inferBuckConfig = inferBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
    this.preprocessMode = preprocessMode;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return cxxPlatforms.containsAnyOf(flavors) ||
        flavors.contains(CxxCompilationDatabase.COMPILATION_DATABASE) ||
        flavors.contains(CxxInferEnhancer.INFER) ||
        flavors.contains(CxxInferEnhancer.INFER_ANALYZE);

  }

  public static ImmutableCollection<CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMultimap<CxxSource.Type, String> exportedPreprocessorFlags,
      ImmutableMap<Path, SourcePath> exportedHeaders,
      ImmutableSet<Path> frameworkSearchPaths) {

    // Check if there is a target node representative for the library in the action graph and,
    // if so, grab the cached transitive C/C++ preprocessor input from that.  We===
    BuildTarget rawTarget =
        params.getBuildTarget()
            .withoutFlavors(
                ImmutableSet.<Flavor>builder()
                    .addAll(LIBRARY_TYPE.getFlavors())
                    .add(cxxPlatform.getFlavor())
                    .build());
    Optional<BuildRule> rawRule = ruleResolver.getRuleOptional(rawTarget);
    if (rawRule.isPresent()) {
      CxxLibrary rule = (CxxLibrary) rawRule.get();
      return rule
          .getTransitiveCxxPreprocessorInput(targetGraph, cxxPlatform, HeaderVisibility.PUBLIC)
          .values();
    }

    // Otherwise, construct it ourselves.
    HeaderSymlinkTree symlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            /* includeLexYaccHeaders */ false,
            ImmutableMap.<String, SourcePath>of(),
            ImmutableMap.<String, SourcePath>of(),
            exportedHeaders,
            HeaderVisibility.PUBLIC);
    Map<BuildTarget, CxxPreprocessorInput> input = Maps.newLinkedHashMap();
    input.put(
        params.getBuildTarget(),
        CxxPreprocessorInput.builder()
            .addRules(symlinkTree.getBuildTarget())
            .putAllPreprocessorFlags(exportedPreprocessorFlags)
            .setIncludes(
                CxxHeaders.builder()
                    .putAllNameToPathMap(symlinkTree.getLinks())
                    .putAllFullNameToPathMap(symlinkTree.getFullLinks())
                    .build())
            .addIncludeRoots(symlinkTree.getIncludePath())
            .addAllHeaderMaps(symlinkTree.getHeaderMap().asSet())
            .addAllFrameworkRoots(frameworkSearchPaths)
            .build());
    for (BuildRule rule : params.getDeps()) {
      if (rule instanceof CxxPreprocessorDep) {
        input.putAll(
            ((CxxPreprocessorDep) rule).getTransitiveCxxPreprocessorInput(
                targetGraph,
                cxxPlatform,
                HeaderVisibility.PUBLIC));
      }
    }
    return ImmutableList.copyOf(input.values());
  }

  private static ImmutableMap<CxxPreprocessAndCompile, SourcePath> requireObjects(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableMultimap<CxxSource.Type, String> exportedPreprocessorFlags,
      Optional<SourcePath> prefixHeader,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableMap<Path, SourcePath> exportedHeaders,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableSet<Path> frameworkSearchPaths,
      CxxPreprocessMode preprocessMode,
      CxxSourceRuleFactory.PicType pic) {

    CxxHeaderSourceSpec lexYaccSources =
        CxxDescriptionEnhancer.requireLexYaccSources(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            lexSources,
            yaccSources);

    HeaderSymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            /* includeLexYaccHeaders */ true,
            lexSources,
            yaccSources,
            headers,
            HeaderVisibility.PRIVATE);

    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInputFromDependencies =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            targetGraph,
            params,
            cxxPlatform,
            preprocessorFlags,
            ImmutableList.of(headerSymlinkTree),
            ImmutableSet.<Path>of(),
            getTransitiveCxxPreprocessorInput(
                targetGraph,
                params,
                ruleResolver,
                pathResolver,
                cxxPlatform,
                exportedPreprocessorFlags,
                exportedHeaders,
                frameworkSearchPaths));

    ImmutableMap<String, CxxSource> allSources =
        ImmutableMap.<String, CxxSource>builder()
            .putAll(sources)
            .putAll(lexYaccSources.getCxxSources())
            .build();

    // Create rule to build the object files.
    return CxxSourceRuleFactory.requirePreprocessAndCompileRules(
        params,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        cxxPreprocessorInputFromDependencies,
        compilerFlags,
        prefixHeader,
        preprocessMode,
        allSources,
        pic);
  }

  /**
   * Create all build rules needed to generate the static library.
   *
   * @return the {@link Archive} rule representing the actual static library.
   */
  private static BuildRule createStaticLibrary(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableMultimap<CxxSource.Type, String> exportedPreprocessorFlags,
      Optional<SourcePath> prefixHeader,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableMap<Path, SourcePath> exportedHeaders,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableSet<Path> frameworkSearchPaths,
      CxxPreprocessMode preprocessMode,
      CxxSourceRuleFactory.PicType pic) {

    // Create rules for compiling the non-PIC object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects = requireObjects(
        targetGraph,
        params,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        lexSources,
        yaccSources,
        preprocessorFlags,
        exportedPreprocessorFlags,
        prefixHeader,
        headers,
        exportedHeaders,
        compilerFlags,
        sources,
        frameworkSearchPaths,
        preprocessMode,
        pic);

    // Write a build rule to create the archive for this C/C++ library.
    BuildTarget staticTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor(),
            pic);

    if (objects.isEmpty()) {
      return new NoopBuildRule(
          new BuildRuleParams(
              staticTarget,
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
              params.getProjectFilesystem(),
              params.getRuleKeyBuilderFactory()),
          pathResolver);
    }

    Path staticLibraryPath =
        CxxDescriptionEnhancer.getStaticLibraryPath(
            params.getBuildTarget(),
            cxxPlatform.getFlavor(),
            pic);
    return Archives.createArchiveRule(
        pathResolver,
        staticTarget,
        params,
        cxxPlatform.getAr(),
        staticLibraryPath,
        ImmutableList.copyOf(objects.values()));
  }

  /**
   * Create all build rules needed to generate the shared library.
   *
   * @return the {@link CxxLink} rule representing the actual shared library.
   */
  private static BuildRule createSharedLibrary(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableMultimap<CxxSource.Type, String> exportedPreprocessorFlags,
      Optional<SourcePath> prefixHeader,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableMap<Path, SourcePath> exportedHeaders,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableList<String> linkerFlags,
      ImmutableSet<Path> frameworkSearchPaths,
      Optional<String> soname,
      CxxPreprocessMode preprocessMode,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType,
      Linker.LinkType linkType,
      Linker.LinkableDepType linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildRule> blacklist) {

    // Create rules for compiling the PIC object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects = requireObjects(
        targetGraph,
        params,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        lexSources,
        yaccSources,
        preprocessorFlags,
        exportedPreprocessorFlags,
        prefixHeader,
        headers,
        exportedHeaders,
        compilerFlags,
        sources,
        frameworkSearchPaths,
        preprocessMode,
        CxxSourceRuleFactory.PicType.PIC);

    // Setup the rules to link the shared library.
    BuildTarget sharedTarget =
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor());

    if (objects.isEmpty()) {
      return new NoopBuildRule(
          new BuildRuleParams(
              sharedTarget,
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
              params.getProjectFilesystem(),
              params.getRuleKeyBuilderFactory()),
          pathResolver);
    }

    String sharedLibrarySoname =
        soname.or(
            CxxDescriptionEnhancer.getDefaultSharedLibrarySoname(
                params.getBuildTarget(), cxxPlatform));
    Path sharedLibraryPath = CxxDescriptionEnhancer.getSharedLibraryPath(
        params.getBuildTarget(),
        sharedLibrarySoname,
        cxxPlatform);
    ImmutableList.Builder<String> extraLdFlagsBuilder = ImmutableList.builder();
    extraLdFlagsBuilder.addAll(linkerFlags);
    extraLdFlagsBuilder.addAll(
        MoreIterables.zipAndConcat(
            Iterables.cycle("-F"),
            Iterables.transform(frameworkSearchPaths, Functions.toStringFunction())));
    ImmutableList<String> extraLdFlags = extraLdFlagsBuilder.build();

    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        targetGraph,
        cxxPlatform,
        params,
        pathResolver,
        FluentIterable.from(extraLdFlags)
            .transform(
                MACRO_HANDLER.getExpander(
                    params.getBuildTarget(),
                    ruleResolver,
                    params.getProjectFilesystem()))
            .toList(),
        sharedTarget,
        linkType,
        Optional.of(sharedLibrarySoname),
        sharedLibraryPath,
        objects.values(),
        FluentIterable
            .from(
                getExtraMacroBuildInputs(
                    params.getBuildTarget(),
                    ruleResolver,
                    extraLdFlags))
            .transform(SourcePaths.getToBuildTargetSourcePath())
            .toList(),
        linkableDepType,
        params.getDeps(),
        cxxRuntimeType,
        bundleLoader,
        blacklist);
  }

  /**
   * Create all build rules needed to generate the compilation database.
   *
   * @return the {@link CxxCompilationDatabase} rule representing the actual compilation database.
   */
  private static CxxCompilationDatabase createCompilationDatabase(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableMultimap<CxxSource.Type, String> exportedPreprocessorFlags,
      Optional<SourcePath> prefixHeader,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableMap<Path, SourcePath> exportedHeaders,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableSet<Path> frameworkSearchPaths,
      CxxPreprocessMode preprocessMode) {
    BuildRuleParams paramsWithoutCompilationDatabaseFlavor = CxxCompilationDatabase
        .paramsWithoutCompilationDatabaseFlavor(params);
    // Invoking requireObjects has the side-effect of invoking
    // CxxSourceRuleFactory.requirePreprocessAndCompileRules(), which has the side-effect of
    // creating CxxPreprocessAndCompile rules and adding them to the ruleResolver.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects = requireObjects(
        targetGraph,
        paramsWithoutCompilationDatabaseFlavor,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        lexSources,
        yaccSources,
        preprocessorFlags,
        exportedPreprocessorFlags,
        prefixHeader,
        headers,
        exportedHeaders,
        compilerFlags,
        sources,
        frameworkSearchPaths,
        preprocessMode,
        CxxSourceRuleFactory.PicType.PIC);

    return CxxCompilationDatabase.createCompilationDatabase(
        params,
        pathResolver,
        preprocessMode,
        objects.keySet());
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  public static Arg createEmptyConstructorArg() {
    Arg arg = new Arg();
    arg.deps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.srcs = Optional.of(ImmutableSortedSet.<SourceWithFlags>of());
    arg.platformSrcs = Optional.of(
        PatternMatchedCollection.<ImmutableSortedSet<SourceWithFlags>>of());
    arg.prefixHeader = Optional.absent();
    arg.headers = Optional.of(SourceList.ofUnnamedSources(ImmutableSortedSet.<SourcePath>of()));
    arg.platformHeaders = Optional.of(PatternMatchedCollection.<SourceList>of());
    arg.exportedHeaders = Optional.of(
        SourceList.ofUnnamedSources(ImmutableSortedSet.<SourcePath>of()));
    arg.exportedPlatformHeaders = Optional.of(PatternMatchedCollection.<SourceList>of());
    arg.compilerFlags = Optional.of(ImmutableList.<String>of());
    arg.platformCompilerFlags =
        Optional.of(PatternMatchedCollection.<ImmutableList<String>>of());
    arg.exportedPreprocessorFlags = Optional.of(ImmutableList.<String>of());
    arg.exportedPlatformPreprocessorFlags =
        Optional.of(PatternMatchedCollection.<ImmutableList<String>>of());
    arg.exportedLangPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    arg.preprocessorFlags = Optional.of(ImmutableList.<String>of());
    arg.platformPreprocessorFlags =
        Optional.of(PatternMatchedCollection.<ImmutableList<String>>of());
    arg.langPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    arg.linkerFlags = Optional.of(ImmutableList.<String>of());
    arg.exportedLinkerFlags = Optional.of(ImmutableList.<String>of());
    arg.platformLinkerFlags = Optional.of(PatternMatchedCollection.<ImmutableList<String>>of());
    arg.exportedPlatformLinkerFlags = Optional.of(
        PatternMatchedCollection.<ImmutableList<String>>of());
    arg.cxxRuntimeType = Optional.absent();
    arg.forceStatic = Optional.absent();
    arg.linkWhole = Optional.absent();
    arg.lexSrcs = Optional.of(ImmutableList.<SourcePath>of());
    arg.yaccSrcs = Optional.of(ImmutableList.<SourcePath>of());
    arg.headerNamespace = Optional.absent();
    arg.soname = Optional.absent();
    arg.frameworks = Optional.of(ImmutableSortedSet.<FrameworkPath>of());
    arg.libraries = Optional.of(ImmutableSortedSet.<FrameworkPath>of());
    arg.tests = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.supportedPlatformsRegex = Optional.absent();
    arg.linkStyle = Optional.absent();
    return arg;
  }

  /**
   * @return a {@link HeaderSymlinkTree} for the headers of this C/C++ library.
   */
  public static <A extends Arg> HeaderSymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args) {
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        /* includeLexYaccHeaders */ true,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        CxxDescriptionEnhancer.parseHeaders(params, resolver, cxxPlatform, args),
        HeaderVisibility.PRIVATE);
  }

  /**
   * @return a {@link HeaderSymlinkTree} for the exported headers of this C/C++ library.
   */
  public static <A extends Arg> HeaderSymlinkTree createExportedHeaderSymlinkTreeBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args) {
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        /* includeLexYaccHeaders */ false,
        ImmutableMap.<String, SourcePath>of(),
        ImmutableMap.<String, SourcePath>of(),
        CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, cxxPlatform, args),
        HeaderVisibility.PUBLIC);
  }

  /**
   * @return a {@link Archive} rule which builds a static library version of this C/C++ library.
   */
  private static <A extends Arg> BuildRule createStaticLibraryBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args,
      CxxPreprocessMode preprocessMode,
      CxxSourceRuleFactory.PicType pic) {
    return createStaticLibrary(
        targetGraph,
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        CxxFlags.getLanguageFlags(
            args.preprocessorFlags,
            args.platformPreprocessorFlags,
            args.langPreprocessorFlags,
            cxxPlatform),
        CxxFlags.getLanguageFlags(
            args.exportedPreprocessorFlags,
            args.exportedPlatformPreprocessorFlags,
            args.exportedLangPreprocessorFlags,
            cxxPlatform),
        args.prefixHeader,
        CxxDescriptionEnhancer.parseHeaders(params, resolver, cxxPlatform, args),
        CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, cxxPlatform, args),
        CxxFlags.getFlags(
            args.compilerFlags,
            args.platformCompilerFlags,
            cxxPlatform),
        CxxDescriptionEnhancer.parseCxxSources(params, resolver, cxxPlatform, args),
        CxxDescriptionEnhancer.getFrameworkSearchPaths(
            args.frameworks,
            cxxPlatform,
            new SourcePathResolver(resolver)),
        preprocessMode,
        pic);
  }

  /**
   * @return a {@link CxxLink} rule which builds a shared library version of this C/C++ library.
   */
  private static <A extends Arg> BuildRule createSharedLibraryBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args,
      CxxPreprocessMode preprocessMode,
      Linker.LinkType linkType,
      Linker.LinkableDepType linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildRule> blacklist) {
    ImmutableList.Builder<String> linkerFlags = ImmutableList.builder();

    linkerFlags.addAll(
        CxxFlags.getFlags(
            args.linkerFlags,
            args.platformLinkerFlags,
            cxxPlatform));

    linkerFlags.addAll(
        CxxFlags.getFlags(
            args.exportedLinkerFlags,
            args.exportedPlatformLinkerFlags,
            cxxPlatform));

    return createSharedLibrary(
        targetGraph,
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        CxxFlags.getLanguageFlags(
            args.preprocessorFlags,
            args.platformPreprocessorFlags,
            args.langPreprocessorFlags,
            cxxPlatform),
        CxxFlags.getLanguageFlags(
            args.exportedPreprocessorFlags,
            args.exportedPlatformPreprocessorFlags,
            args.exportedLangPreprocessorFlags,
            cxxPlatform),
        args.prefixHeader,
        CxxDescriptionEnhancer.parseHeaders(params, resolver, cxxPlatform, args),
        CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, cxxPlatform, args),
        CxxFlags.getFlags(
            args.compilerFlags,
            args.platformCompilerFlags,
            cxxPlatform),
        CxxDescriptionEnhancer.parseCxxSources(params, resolver, cxxPlatform, args),
        linkerFlags.build(),
        CxxDescriptionEnhancer.getFrameworkSearchPaths(
            args.frameworks,
            cxxPlatform,
            new SourcePathResolver(resolver)),
        args.soname,
        preprocessMode,
        args.cxxRuntimeType,
        linkType,
        linkableDepType,
        bundleLoader,
        blacklist);
  }

  /**
   * @return a {@link CxxCompilationDatabase} rule which builds a compilation database for this
   * C/C++ library.
   */
  private static <A extends Arg> CxxCompilationDatabase createCompilationDatabaseBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args,
      CxxPreprocessMode preprocessMode) {
    return createCompilationDatabase(
        targetGraph,
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        CxxFlags.getLanguageFlags(
            args.preprocessorFlags,
            args.platformPreprocessorFlags,
            args.langPreprocessorFlags,
            cxxPlatform),
        CxxFlags.getLanguageFlags(
            args.exportedPreprocessorFlags,
            args.exportedPlatformPreprocessorFlags,
            args.exportedLangPreprocessorFlags,
            cxxPlatform),
        args.prefixHeader,
        CxxDescriptionEnhancer.parseHeaders(params, resolver, cxxPlatform, args),
        CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, cxxPlatform, args),
        CxxFlags.getFlags(
            args.compilerFlags,
            args.platformCompilerFlags,
            cxxPlatform),
        CxxDescriptionEnhancer.parseCxxSources(params, resolver, cxxPlatform, args),
        CxxDescriptionEnhancer.getFrameworkSearchPaths(
            args.frameworks,
            cxxPlatform,
            new SourcePathResolver(resolver)),
        preprocessMode);
  }

  public static TypeAndPlatform getTypeAndPlatform(
      BuildTarget buildTarget,
      FlavorDomain<CxxPlatform> platforms) {
    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, Type>> type;
    Optional<Map.Entry<Flavor, CxxPlatform>> platform;
    try {
      type = LIBRARY_TYPE.getFlavorAndValue(
          ImmutableSet.copyOf(buildTarget.getFlavors()));
      platform = platforms.getFlavorAndValue(
          ImmutableSet.copyOf(buildTarget.getFlavors()));
    } catch (FlavorDomainException e) {
      throw new HumanReadableException("%s: %s", buildTarget, e.getMessage());
    }
    return TypeAndPlatform.of(type, platform);
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    TypeAndPlatform typeAndPlatform = getTypeAndPlatform(
        params.getBuildTarget(),
        cxxPlatforms);
    return createBuildRule(
        targetGraph,
        params,
        resolver,
        args,
        typeAndPlatform,
        args.linkStyle,
        Optional.<SourcePath>absent(),
        ImmutableSet.<BuildRule>of());
  }

  private static ImmutableList<BuildRule> getExtraMacroBuildInputs(
      BuildTarget target,
      BuildRuleResolver resolver,
      Iterable<String> flags) {
    ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
    try {
      for (String flag : flags) {
        deps.addAll(MACRO_HANDLER.extractAdditionalBuildTimeDeps(target, resolver, flag));
      }
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
    return deps.build();
  }

  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args,
      TypeAndPlatform typeAndPlatform,
      Optional<Linker.LinkableDepType> linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildRule> blacklist) {
    Optional<Map.Entry<Flavor, CxxPlatform>> platform = typeAndPlatform.getPlatform();

    if (params.getBuildTarget().getFlavors()
        .contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      // XXX: This needs bundleLoader for tests..
      return createCompilationDatabaseBuildRule(
              targetGraph,
              params,
              resolver,
              platform.isPresent()
                  ? platform.get().getValue()
                  : DefaultCxxPlatforms.build(cxxBuckConfig),
              args,
              preprocessMode);
    }

    if (params.getBuildTarget().getFlavors().contains(CxxInferEnhancer.INFER)) {
      return CxxInferEnhancer.requireInferAnalyzeAndReportBuildRuleForCxxDescriptionArg(
          targetGraph,
          params,
          resolver,
          new SourcePathResolver(resolver),
          platform.isPresent()
              ? platform.get().getValue()
              : DefaultCxxPlatforms.build(cxxBuckConfig),
          args,
          new CxxInferTools(inferBuckConfig));
    }

    if (params.getBuildTarget().getFlavors().contains(CxxInferEnhancer.INFER_ANALYZE)) {
      return CxxInferEnhancer.requireInferAnalyzeBuildRuleForCxxDescriptionArg(
          targetGraph,
          params,
          resolver,
          new SourcePathResolver(resolver),
          platform.isPresent()
              ? platform.get().getValue()
              : DefaultCxxPlatforms.build(cxxBuckConfig),
          args,
          new CxxInferTools(inferBuckConfig));
    }

    Optional<Map.Entry<Flavor, Type>> type = typeAndPlatform.getType();

    // If we *are* building a specific type of this lib, call into the type specific
    // rule builder methods.
    if (type.isPresent() && platform.isPresent()) {
      Set<Flavor> flavors = Sets.newHashSet(params.getBuildTarget().getFlavors());
      flavors.remove(type.get().getKey());
      BuildTarget target = BuildTarget
          .builder(params.getBuildTarget().getUnflavoredBuildTarget())
          .addAllFlavors(flavors)
          .build();
      BuildRuleParams typeParams =
          params.copyWithChanges(
              target,
              params.getDeclaredDeps(),
              params.getExtraDeps());
      if (type.get().getValue().equals(Type.HEADERS)) {
        return createHeaderSymlinkTreeBuildRule(
            typeParams,
            resolver,
            platform.get().getValue(),
            args);
      } else if (type.get().getValue().equals(Type.EXPORTED_HEADERS)) {
          return createExportedHeaderSymlinkTreeBuildRule(
              typeParams,
              resolver,
              platform.get().getValue(),
              args);
      } else if (type.get().getValue().equals(Type.SHARED)) {
        return createSharedLibraryBuildRule(
            targetGraph,
            typeParams,
            resolver,
            platform.get().getValue(),
            args,
            preprocessMode,
            Linker.LinkType.SHARED,
            linkableDepType.or(Linker.LinkableDepType.SHARED),
            Optional.<SourcePath>absent(),
            blacklist);
      } else if (type.get().getValue().equals(Type.MACH_O_BUNDLE)) {
        return createSharedLibraryBuildRule(
            targetGraph,
            typeParams,
            resolver,
            platform.get().getValue(),
            args,
            preprocessMode,
            Linker.LinkType.MACH_O_BUNDLE,
            linkableDepType.or(Linker.LinkableDepType.SHARED),
            bundleLoader,
            blacklist);
      } else if (type.get().getValue().equals(Type.STATIC)) {
        return createStaticLibraryBuildRule(
            targetGraph,
            typeParams,
            resolver,
            platform.get().getValue(),
            args,
            preprocessMode,
            CxxSourceRuleFactory.PicType.PDC);
      } else {
        return createStaticLibraryBuildRule(
            targetGraph,
            typeParams,
            resolver,
            platform.get().getValue(),
            args,
            preprocessMode,
            CxxSourceRuleFactory.PicType.PIC);
      }
    }

    boolean hasObjectsForAnyPlatform = !args.srcs.get().isEmpty() ||
        !args.lexSrcs.get().isEmpty() ||
        !args.yaccSrcs.get().isEmpty();
    Predicate<CxxPlatform> hasObjects;
    if (hasObjectsForAnyPlatform) {
      hasObjects = Predicates.alwaysTrue();
    } else {
      hasObjects = new Predicate<CxxPlatform>() {
        @Override
        public boolean apply(CxxPlatform input) {
          return !args.platformSrcs.get().getMatchingValues(input.getFlavor().toString()).isEmpty();
        }
      };
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    final SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return new CxxLibrary(
        params,
        resolver,
        pathResolver,
        Predicates.not(hasObjects),
        new Function<CxxPlatform, ImmutableMultimap<CxxSource.Type, String>>() {
          @Override
          public ImmutableMultimap<CxxSource.Type, String> apply(CxxPlatform input) {
            return CxxFlags.getLanguageFlags(
                args.exportedPreprocessorFlags,
                args.exportedPlatformPreprocessorFlags,
                args.exportedLangPreprocessorFlags,
                input);
          }
        },
        new Function<CxxPlatform, Pair<ImmutableList<String>, ImmutableSet<SourcePath>>>() {
          @Override
          public Pair<ImmutableList<String>, ImmutableSet<SourcePath>> apply(
              CxxPlatform input) {
            ImmutableList<String> flags = CxxFlags.getFlags(
                args.exportedLinkerFlags,
                args.exportedPlatformLinkerFlags,
                input);
            return new Pair<>(
                FluentIterable.from(flags)
                    .transform(
                        MACRO_HANDLER.getExpander(
                            params.getBuildTarget(),
                            resolver,
                            params.getProjectFilesystem()))
                    .toList(),
                FluentIterable.from(
                    getExtraMacroBuildInputs(
                        params.getBuildTarget(),
                        resolver,
                        flags))
                    .transform(SourcePaths.getToBuildTargetSourcePath())
                    .toSet());
          }
        },
        args.supportedPlatformsRegex,
        args.frameworks.or(ImmutableSortedSet.<FrameworkPath>of()),
        args.libraries.or(ImmutableSortedSet.<FrameworkPath>of()),
        args.forceStatic.or(false) ? NativeLinkable.Linkage.STATIC : NativeLinkable.Linkage.ANY,
        args.linkWhole.or(false),
        args.soname,
        args.tests.get(),
        args.canBeAsset.or(false));
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    if (constructorArg.lexSrcs.isPresent() && !constructorArg.lexSrcs.get().isEmpty()) {
      deps.add(cxxBuckConfig.getLexDep());
    }

    try {
      for (ImmutableList<String> values :
          Optional.presentInstances(ImmutableList.of(
                  constructorArg.linkerFlags,
                  constructorArg.exportedLinkerFlags))) {
        for (String val : values) {
          deps.addAll(MACRO_HANDLER.extractParseTimeDeps(buildTarget, val));
        }
      }
      for (PatternMatchedCollection<ImmutableList<String>> values :
          Optional.presentInstances(ImmutableList.of(
                  constructorArg.platformLinkerFlags,
                  constructorArg.exportedPlatformLinkerFlags))) {
        for (Pair<Pattern, ImmutableList<String>> pav : values.getPatternsAndValues()) {
          for (String val : pav.getSecond()) {
            deps.addAll(
                MACRO_HANDLER.extractParseTimeDeps(buildTarget, val));
          }
        }
      }
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
    }

    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxConstructorArg {
    public Optional<SourceList> exportedHeaders;
    public Optional<PatternMatchedCollection<SourceList>> exportedPlatformHeaders;
    public Optional<ImmutableList<String>> exportedPreprocessorFlags;
    public Optional<PatternMatchedCollection<ImmutableList<String>>>
        exportedPlatformPreprocessorFlags;
    public Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>>
        exportedLangPreprocessorFlags;
    public Optional<ImmutableList<String>> exportedLinkerFlags;
    public Optional<PatternMatchedCollection<ImmutableList<String>>> exportedPlatformLinkerFlags;
    public Optional<ImmutableSortedSet<BuildTarget>> exportedDeps;
    public Optional<Pattern> supportedPlatformsRegex;
    public Optional<String> soname;
    public Optional<Boolean> forceStatic;
    public Optional<Boolean> linkWhole;
    public Optional<Boolean> canBeAsset;
    public Optional<Linker.LinkableDepType> linkStyle;
  }

}
