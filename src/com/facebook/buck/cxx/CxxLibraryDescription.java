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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

@Value.Nested
public class CxxLibraryDescription implements
    Description<CxxLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<CxxLibraryDescription.Arg>,
    Flavored {
  public static enum Type {
    HEADERS,
    EXPORTED_HEADERS,
    SHARED,
    STATIC,
  }

  public static final BuildRuleType TYPE = BuildRuleType.of("cxx_library");

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      new FlavorDomain<>(
          "C/C++ Library Type",
          ImmutableMap.of(
              CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR, Type.HEADERS,
              CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR, Type.EXPORTED_HEADERS,
              CxxDescriptionEnhancer.SHARED_FLAVOR, Type.SHARED,
              CxxDescriptionEnhancer.STATIC_FLAVOR, Type.STATIC));

  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxSourceRuleFactory.Strategy compileStrategy;

  public CxxLibraryDescription(
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxSourceRuleFactory.Strategy compileStrategy) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
    this.compileStrategy = compileStrategy;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return cxxPlatforms.containsAnyOf(flavors) ||
        flavors.contains(CxxCompilationDatabase.COMPILATION_DATABASE);
  }

  private static ImmutableList<SourcePath> requireObjects(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableList<SourcePath> prefixHeaders,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableMap<Path, SourcePath> exportedHeaders,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableList<Path> frameworkSearchPaths,
      CxxSourceRuleFactory.Strategy compileStrategy,
      CxxSourceRuleFactory.PicType pic) {

    CxxHeaderSourceSpec lexYaccSources =
        CxxDescriptionEnhancer.requireLexYaccSources(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            lexSources,
            yaccSources);

    SymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            /* includeLexYaccHeaders */ true,
            lexSources,
            yaccSources,
            headers,
            CxxDescriptionEnhancer.HeaderVisibility.PRIVATE);

    SymlinkTree exportedHeaderSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            /* includeLexYaccHeaders */ false,
            ImmutableMap.<String, SourcePath>of(),
            ImmutableMap.<String, SourcePath>of(),
            exportedHeaders,
            CxxDescriptionEnhancer.HeaderVisibility.PUBLIC);

    CxxPreprocessorInput cxxPreprocessorInputFromDependencies =
        CxxDescriptionEnhancer.combineCxxPreprocessorInput(
            params,
            cxxPlatform,
            preprocessorFlags,
            prefixHeaders,
            ImmutableList.of(
                headerSymlinkTree,
                exportedHeaderSymlinkTree),
            frameworkSearchPaths);

    ImmutableMap<String, CxxSource> allSources =
        ImmutableMap.<String, CxxSource>builder()
            .putAll(sources)
            .putAll(lexYaccSources.getCxxSources())
            .build();

    // Create rule to build the object files.
    return CxxSourceRuleFactory.createPreprocessAndCompileRules(
        params,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        cxxPreprocessorInputFromDependencies,
        compilerFlags,
        compileStrategy,
        allSources,
        pic);
  }

  /**
   * Create all build rules needed to generate the static library.
   *
   * @return the {@link Archive} rule representing the actual static library.
   */
  private static Archive createStaticLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableList<SourcePath> prefixHeaders,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableMap<Path, SourcePath> exportedHeaders,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableList<Path> frameworkSearchPaths,
      CxxSourceRuleFactory.Strategy compileStrategy) {

    // Create rules for compiling the non-PIC object files.
    ImmutableList<SourcePath> objects = requireObjects(
        params,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        lexSources,
        yaccSources,
        preprocessorFlags,
        prefixHeaders,
        headers,
        exportedHeaders,
        compilerFlags,
        sources,
        frameworkSearchPaths,
        compileStrategy,
        CxxSourceRuleFactory.PicType.PDC);

    // Write a build rule to create the archive for this C/C++ library.
    BuildTarget staticTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor());
    Path staticLibraryPath =
        CxxDescriptionEnhancer.getStaticLibraryPath(
            params.getBuildTarget(),
            cxxPlatform.getFlavor());
    Archive staticLibraryBuildRule = Archives.createArchiveRule(
        pathResolver,
        staticTarget,
        params,
        cxxPlatform.getAr(),
        staticLibraryPath,
        objects);

    return staticLibraryBuildRule;
  }

  /**
   * Create all build rules needed to generate the shared library.
   *
   * @return the {@link CxxLink} rule representing the actual shared library.
   */
  private static CxxLink createSharedLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableList<SourcePath> prefixHeaders,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableMap<Path, SourcePath> exportedHeaders,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableList<String> linkerFlags,
      ImmutableList<Path> frameworkSearchPaths,
      Optional<String> soname,
      CxxSourceRuleFactory.Strategy compileStrategy) {

    // Create rules for compiling the PIC object files.
    ImmutableList<SourcePath> objects = requireObjects(
        params,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        lexSources,
        yaccSources,
        preprocessorFlags,
        prefixHeaders,
        headers,
        exportedHeaders,
        compilerFlags,
        sources,
        frameworkSearchPaths,
        compileStrategy,
        CxxSourceRuleFactory.PicType.PIC);

    // Setup the rules to link the shared library.
    BuildTarget sharedTarget =
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor());
    String sharedLibrarySoname =
        soname.or(
            CxxDescriptionEnhancer.getSharedLibrarySoname(params.getBuildTarget(), cxxPlatform));
    Path sharedLibraryPath = CxxDescriptionEnhancer.getSharedLibraryPath(
        params.getBuildTarget(),
        cxxPlatform);
    ImmutableList.Builder<String> extraCxxLdFlagsBuilder = ImmutableList.builder();
    extraCxxLdFlagsBuilder.addAll(
        MoreIterables.zipAndConcat(
            Iterables.cycle("-F"),
            Iterables.transform(frameworkSearchPaths, Functions.toStringFunction())));
    ImmutableList<String> extraCxxLdFlags = extraCxxLdFlagsBuilder.build();

    CxxLink sharedLibraryBuildRule =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            cxxPlatform,
            params,
            pathResolver,
            extraCxxLdFlags,
            linkerFlags,
            sharedTarget,
            Linker.LinkType.SHARED,
            Optional.of(sharedLibrarySoname),
            sharedLibraryPath,
            objects,
            Linker.LinkableDepType.SHARED,
            params.getDeps());

    return sharedLibraryBuildRule;
  }

  /**
   * Create all build rules needed to generate the compilation database.
   *
   * @return the {@link CxxCompilationDatabase} rule representing the actual compilation database.
   */
  private static CxxCompilationDatabase createCompilationDatabase(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableList<SourcePath> prefixHeaders,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableMap<Path, SourcePath> exportedHeaders,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableList<Path> frameworkSearchPaths,
      CxxSourceRuleFactory.Strategy compileStrategy) {

    Set<Flavor> flavors = Sets.newHashSet(params.getBuildTarget().getFlavors());
    flavors.remove(CxxCompilationDatabase.COMPILATION_DATABASE);
    BuildTarget target = BuildTarget
        .builder(params.getBuildTarget().getUnflavoredBuildTarget())
        .addAllFlavors(flavors)
        .build();

    BuildRuleParams paramsWithoutCompilationDatabaseFlavor =
        params.copyWithChanges(
            params.getBuildRuleType(),
            target,
            Suppliers.ofInstance(params.getDeclaredDeps()),
            Suppliers.ofInstance(params.getExtraDeps()));

    // Create rules for compiling the PIC object files.
    requireObjects(
        paramsWithoutCompilationDatabaseFlavor,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        lexSources,
        yaccSources,
        preprocessorFlags,
        prefixHeaders,
        headers,
        exportedHeaders,
        compilerFlags,
        sources,
        frameworkSearchPaths,
        compileStrategy,
        CxxSourceRuleFactory.PicType.PIC);

    return CxxCompilationDatabase.createCompilationDatabase(
        params,
        ruleResolver,
        pathResolver,
        compileStrategy);
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  public static Arg createEmptyConstructorArg() {
    Arg arg = new Arg();
    arg.deps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.srcs = Optional.of(
        Either.<ImmutableList<SourceWithFlags>, ImmutableMap<String, SourceWithFlags>>ofLeft(
            ImmutableList.<SourceWithFlags>of()));
    arg.prefixHeaders = Optional.of(ImmutableList.<SourcePath>of());
    arg.headers = Optional.of(
        Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
            ImmutableList.<SourcePath>of()));
    arg.exportedHeaders = Optional.of(
        Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
            ImmutableList.<SourcePath>of()));
    arg.compilerFlags = Optional.of(ImmutableList.<String>of());
    arg.platformCompilerFlags =
        Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    arg.exportedPreprocessorFlags = Optional.of(ImmutableList.<String>of());
    arg.exportedPlatformPreprocessorFlags =
        Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    arg.exportedLangPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    arg.preprocessorFlags = Optional.of(ImmutableList.<String>of());
    arg.platformPreprocessorFlags =
        Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    arg.langPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    arg.linkerFlags = Optional.of(ImmutableList.<String>of());
    arg.platformLinkerFlags = Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    arg.linkWhole = Optional.absent();
    arg.lexSrcs = Optional.of(ImmutableList.<SourcePath>of());
    arg.yaccSrcs = Optional.of(ImmutableList.<SourcePath>of());
    arg.headerNamespace = Optional.absent();
    arg.soname = Optional.absent();
    arg.frameworkSearchPaths = Optional.of(ImmutableList.<Path>of());
    arg.tests = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    return arg;
  }

  /**
   * @return a {@link SymlinkTree} for the headers of this C/C++ library.
   */
  public static <A extends Arg> SymlinkTree createHeaderSymlinkTreeBuildRule(
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
        CxxDescriptionEnhancer.parseHeaders(params, resolver, args),
        CxxDescriptionEnhancer.HeaderVisibility.PRIVATE);
  }

  /**
   * @return a {@link SymlinkTree} for the exported headers of this C/C++ library.
   */
  public static <A extends Arg> SymlinkTree createExportedHeaderSymlinkTreeBuildRule(
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
        CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, args),
        CxxDescriptionEnhancer.HeaderVisibility.PUBLIC);
  }

  /**
   * @return a {@link Archive} rule which builds a static library version of this C/C++ library.
   */
  public static <A extends Arg> Archive createStaticLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args,
      CxxSourceRuleFactory.Strategy compileStrategy) {
    return createStaticLibrary(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        ImmutableMultimap.<CxxSource.Type, String>builder()
            .putAll(
                CxxFlags.getLanguageFlags(
                    args.preprocessorFlags,
                    args.platformPreprocessorFlags,
                    args.langPreprocessorFlags,
                    cxxPlatform.getFlavor()))
            .putAll(
                CxxFlags.getLanguageFlags(
                    args.exportedPreprocessorFlags,
                    args.exportedPlatformPreprocessorFlags,
                    args.exportedLangPreprocessorFlags,
                    cxxPlatform.getFlavor()))
            .build(),
        args.prefixHeaders.get(),
        CxxDescriptionEnhancer.parseHeaders(params, resolver, args),
        CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, args),
        CxxFlags.getFlags(
            args.compilerFlags,
            args.platformCompilerFlags,
            cxxPlatform.getFlavor()),
        CxxDescriptionEnhancer.parseCxxSources(params, resolver, args),
        args.frameworkSearchPaths.get(),
        compileStrategy);
  }

  /**
   * @return a {@link CxxLink} rule which builds a shared library version of this C/C++ library.
   */
  public static <A extends Arg> CxxLink createSharedLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args,
      CxxSourceRuleFactory.Strategy compileStrategy) {
    ImmutableList.Builder<String> linkerFlags = ImmutableList.builder();

    linkerFlags.addAll(
        CxxFlags.getFlags(
            args.linkerFlags,
            args.platformLinkerFlags,
            cxxPlatform.getFlavor()));

    linkerFlags.addAll(
        CxxFlags.getFlags(
            args.exportedLinkerFlags,
            args.exportedPlatformLinkerFlags,
            cxxPlatform.getFlavor()));

    return createSharedLibrary(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        ImmutableMultimap.<CxxSource.Type, String>builder()
            .putAll(
                CxxFlags.getLanguageFlags(
                    args.preprocessorFlags,
                    args.platformPreprocessorFlags,
                    args.langPreprocessorFlags,
                    cxxPlatform.getFlavor()))
            .putAll(
                CxxFlags.getLanguageFlags(
                    args.exportedPreprocessorFlags,
                    args.exportedPlatformPreprocessorFlags,
                    args.exportedLangPreprocessorFlags,
                    cxxPlatform.getFlavor()))
            .build(),
        args.prefixHeaders.get(),
        CxxDescriptionEnhancer.parseHeaders(params, resolver, args),
        CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, args),
        CxxFlags.getFlags(
            args.compilerFlags,
            args.platformCompilerFlags,
            cxxPlatform.getFlavor()),
        CxxDescriptionEnhancer.parseCxxSources(params, resolver, args),
        linkerFlags.build(),
        args.frameworkSearchPaths.get(),
        args.soname,
        compileStrategy);
  }

  /**
   * @return a {@link CxxCompilationDatabase} rule which builds a compilation database for this
   * C/C++ library.
   */
  public static <A extends Arg> CxxCompilationDatabase createCompilationDatabaseBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args,
      CxxSourceRuleFactory.Strategy compileStrategy) {
    return createCompilationDatabase(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        ImmutableMultimap.<CxxSource.Type, String>builder()
            .putAll(
                CxxFlags.getLanguageFlags(
                    args.preprocessorFlags,
                    args.platformPreprocessorFlags,
                    args.langPreprocessorFlags,
                    cxxPlatform.getFlavor()))
            .putAll(
                CxxFlags.getLanguageFlags(
                    args.exportedPreprocessorFlags,
                    args.exportedPlatformPreprocessorFlags,
                    args.exportedLangPreprocessorFlags,
                    cxxPlatform.getFlavor()))
            .build(),
        args.prefixHeaders.get(),
        CxxDescriptionEnhancer.parseHeaders(params, resolver, args),
        CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, args),
        CxxFlags.getFlags(
            args.compilerFlags,
            args.platformCompilerFlags,
            cxxPlatform.getFlavor()),
        CxxDescriptionEnhancer.parseCxxSources(params, resolver, args),
        args.frameworkSearchPaths.get(),
        compileStrategy);
  }

  @Value.Immutable
  @BuckStyleImmutable
  public static interface TypeAndPlatform {
    @Value.Parameter
    Optional<Map.Entry<Flavor, Type>> getType();

    @Value.Parameter
    Optional<Map.Entry<Flavor, CxxPlatform>> getPlatform();
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
    return ImmutableCxxLibraryDescription.TypeAndPlatform.of(type, platform);
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    TypeAndPlatform typeAndPlatform = getTypeAndPlatform(
        params.getBuildTarget(),
        cxxPlatforms);
    return createBuildRule(params, resolver, args, typeAndPlatform);
  }

  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      final A args,
      TypeAndPlatform typeAndPlatform) {
    Optional<Map.Entry<Flavor, CxxPlatform>> platform = typeAndPlatform.getPlatform();

    if (params.getBuildTarget().getFlavors()
        .contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {

      return createCompilationDatabaseBuildRule(
          params,
          resolver,
          platform.get().getValue(),
          args,
          compileStrategy);
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
              params.getBuildRuleType(),
              target,
              Suppliers.ofInstance(params.getDeclaredDeps()),
              Suppliers.ofInstance(params.getExtraDeps()));
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
            typeParams,
            resolver,
            platform.get().getValue(),
            args,
            compileStrategy);
      } else {
        return createStaticLibraryBuildRule(
            typeParams,
            resolver,
            platform.get().getValue(),
            args,
            compileStrategy);
      }
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return new CxxLibrary(
        params,
        resolver,
        pathResolver,
        new Function<CxxPlatform, ImmutableMultimap<CxxSource.Type, String>>() {
          @Override
          public ImmutableMultimap<CxxSource.Type, String> apply(CxxPlatform input) {
            return CxxFlags.getLanguageFlags(
                args.exportedPreprocessorFlags,
                args.exportedPlatformPreprocessorFlags,
                args.exportedLangPreprocessorFlags,
                input.getFlavor());
          }
        },
        new Function<CxxPlatform, ImmutableList<String>>() {
          @Override
          public ImmutableList<String> apply(CxxPlatform input) {
            return CxxFlags.getFlags(
                args.exportedLinkerFlags,
                args.exportedPlatformLinkerFlags,
                input.getFlavor());
          }
        },
        args.frameworkSearchPaths.get(),
        args.linkWhole.or(false),
        args.soname,
        args.tests.get());
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

    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxConstructorArg {
    public Optional<Either<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>>
        exportedHeaders;
    public Optional<ImmutableList<String>> exportedPreprocessorFlags;
    public Optional<ImmutableList<Pair<String, ImmutableList<String>>>>
        exportedPlatformPreprocessorFlags;
    public Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>>
        exportedLangPreprocessorFlags;
    public Optional<ImmutableList<String>> exportedLinkerFlags;
    public Optional<ImmutableList<Pair<String, ImmutableList<String>>>>
        exportedPlatformLinkerFlags;
    public Optional<String> soname;
    public Optional<Boolean> linkWhole;
  }

}
