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

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.ImplicitFlavorsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroException;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
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
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class CxxLibraryDescription implements
    Description<CxxLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<CxxLibraryDescription.Arg>,
    ImplicitFlavorsInferringDescription,
    Flavored,
    MetadataProvidingDescription<CxxLibraryDescription.Arg> {

  private static final Logger LOG = Logger.get(CxxLibraryDescription.class);

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.<String, MacroExpander>of(
              "location", new LocationMacroExpander()));

  public enum Type implements FlavorConvertible {
    HEADERS(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
    EXPORTED_HEADERS(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    STATIC_PIC(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR),
    MACH_O_BUNDLE(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR),
    ;

    private final Flavor flavor;

    Type(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  public static final BuildRuleType TYPE = BuildRuleType.of("cxx_library");

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("C/C++ Library Type", Type.class);

  private final CxxBuckConfig cxxBuckConfig;
  private final CxxPlatform defaultCxxPlatform;
  private final InferBuckConfig inferBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public CxxLibraryDescription(
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform defaultCxxPlatform,
      InferBuckConfig inferBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.inferBuckConfig = inferBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return cxxPlatforms.containsAnyOf(flavors) ||
        flavors.contains(CxxCompilationDatabase.COMPILATION_DATABASE) ||
        flavors.contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE) ||
        flavors.contains(CxxInferEnhancer.InferFlavors.INFER.get()) ||
        flavors.contains(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get()) ||
        flavors.contains(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.get());

  }

  public static ImmutableCollection<CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMultimap<CxxSource.Type, String> exportedPreprocessorFlags,
      ImmutableMap<Path, SourcePath> exportedHeaders,
      ImmutableSet<FrameworkPath> frameworks) throws NoSuchBuildTargetException {

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
          .getTransitiveCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PUBLIC)
          .values();
    }

    // Otherwise, construct it ourselves.
    HeaderSymlinkTree symlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            exportedHeaders,
            HeaderVisibility.PUBLIC);
    Map<BuildTarget, CxxPreprocessorInput> input = Maps.newLinkedHashMap();

    CxxSymlinkTreeHeaders.Builder headers = CxxSymlinkTreeHeaders.builder();
    headers.setIncludeType(CxxPreprocessables.IncludeType.LOCAL);
    headers.setRoot(
        new BuildTargetSourcePath(
            symlinkTree.getBuildTarget(),
            symlinkTree.getIncludePath()));
    headers.putAllNameToPathMap(symlinkTree.getLinks());
    if (symlinkTree.getHeaderMap().isPresent()) {
      headers.setHeaderMap(
          new BuildTargetSourcePath(
              symlinkTree.getBuildTarget(),
              symlinkTree.getHeaderMap().get()));
    }
    input.put(
        params.getBuildTarget(),
        CxxPreprocessorInput.builder()
            .putAllPreprocessorFlags(exportedPreprocessorFlags)
            .addIncludes(
                CxxSymlinkTreeHeaders.from(symlinkTree, CxxPreprocessables.IncludeType.LOCAL))
            .addAllFrameworks(frameworks)
            .build());
    for (BuildRule rule : params.getDeps()) {
      if (rule instanceof CxxPreprocessorDep) {
        input.putAll(
            ((CxxPreprocessorDep) rule).getTransitiveCxxPreprocessorInput(
                cxxPlatform,
                HeaderVisibility.PUBLIC));
      }
    }
    return ImmutableList.copyOf(input.values());
  }

  private static NativeLinkableInput getSharedLibraryNativeLinkTargetInput(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      Arg arg,
      ImmutableList<String> linkerFlags,
      ImmutableList<String> exportedLinkerFlags,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries)
      throws NoSuchBuildTargetException {

    // Create rules for compiling the PIC object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        CxxDescriptionEnhancer.requireObjects(
            params,
            ruleResolver,
            pathResolver,
            cxxBuckConfig,
            cxxPlatform,
            CxxSourceRuleFactory.PicType.PIC,
            arg);

    return NativeLinkableInput.builder()
        .addAllArgs(
            FluentIterable
                .from(linkerFlags)
                .append(exportedLinkerFlags)
                .transform(
                    MacroArg.toMacroArgFunction(
                        MACRO_HANDLER,
                        params.getBuildTarget(),
                        params.getCellRoots(),
                        ruleResolver)))
        .addAllArgs(SourcePathArg.from(pathResolver, objects.values()))
        .setFrameworks(frameworks)
        .setLibraries(libraries)
        .build();
  }

  /**
   * Create all build rules needed to generate the shared library.
   *
   * @return the {@link CxxLink} rule representing the actual shared library.
   */
  private static BuildRule createSharedLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescription.Arg args,
      ImmutableList<String> linkerFlags,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      Optional<String> soname,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType,
      Linker.LinkType linkType,
      Linker.LinkableDepType linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist)
      throws NoSuchBuildTargetException {

    // Create rules for compiling the PIC object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        CxxDescriptionEnhancer.requireObjects(
            params,
            ruleResolver,
            pathResolver,
            cxxBuckConfig,
            cxxPlatform,
            CxxSourceRuleFactory.PicType.PIC,
            args);

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
              params.getCellRoots()),
          pathResolver);
    }

    String sharedLibrarySoname = CxxDescriptionEnhancer.getSharedLibrarySoname(
        soname,
        params.getBuildTarget(),
        cxxPlatform);
    Path sharedLibraryPath = CxxDescriptionEnhancer.getSharedLibraryPath(
        params.getBuildTarget(),
        sharedLibrarySoname,
        cxxPlatform);
    ImmutableList.Builder<String> extraLdFlagsBuilder = ImmutableList.builder();
    extraLdFlagsBuilder.addAll(linkerFlags);
    ImmutableList<String> extraLdFlags = extraLdFlagsBuilder.build();

    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        params,
        ruleResolver,
        pathResolver,
        sharedTarget,
        linkType,
        Optional.of(sharedLibrarySoname),
        sharedLibraryPath,
        linkableDepType,
        Iterables.filter(params.getDeps(), NativeLinkable.class),
        cxxRuntimeType,
        bundleLoader,
        blacklist,
        NativeLinkableInput.builder()
            .addAllArgs(
                FluentIterable.from(extraLdFlags)
                    .transform(
                        MacroArg.toMacroArgFunction(
                            MACRO_HANDLER,
                            params.getBuildTarget(),
                            params.getCellRoots(),
                            ruleResolver)))
            .addAllArgs(SourcePathArg.from(pathResolver, objects.values()))
            .setFrameworks(frameworks)
            .setLibraries(libraries)
            .build());
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  public static Arg createEmptyConstructorArg() {
    Arg arg = new Arg();
    arg.deps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.exportedDeps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
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
    arg.langCompilerFlags =
        Optional.of(ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
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
        CxxDescriptionEnhancer.parseHeaders(
            params.getBuildTarget(),
            new SourcePathResolver(resolver),
            Optional.of(cxxPlatform),
            args),
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
        CxxDescriptionEnhancer.parseExportedHeaders(
            params.getBuildTarget(),
            new SourcePathResolver(resolver),
            Optional.of(cxxPlatform),
            args),
        HeaderVisibility.PUBLIC);
  }

  /**
   * Create all build rules needed to generate the static library.
   *
   * @return build rule that builds the static library version of this C/C++ library.
   */
  private static BuildRule createStaticLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      Arg args,
      CxxSourceRuleFactory.PicType pic) throws NoSuchBuildTargetException {
    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);

    // Create rules for compiling the object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        CxxDescriptionEnhancer.requireObjects(
            params,
            resolver,
            sourcePathResolver,
            cxxBuckConfig,
            cxxPlatform,
            pic,
            args);

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
              params.getCellRoots()),
          sourcePathResolver);
    }

    Path staticLibraryPath =
        CxxDescriptionEnhancer.getStaticLibraryPath(
            params.getBuildTarget(),
            cxxPlatform.getFlavor(),
            pic);
    return Archive.from(
        staticTarget,
        params,
        sourcePathResolver,
        cxxPlatform.getAr(),
        cxxPlatform.getRanlib(),
        cxxBuckConfig.getArchiveContents(),
        staticLibraryPath,
        ImmutableList.copyOf(objects.values()));
  }

  /**
   * @return a {@link CxxLink} rule which builds a shared library version of this C/C++ library.
   */
  private static <A extends Arg> BuildRule createSharedLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      A args,
      Linker.LinkType linkType,
      Linker.LinkableDepType linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist)
      throws NoSuchBuildTargetException {
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

    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    return createSharedLibrary(
        params,
        resolver,
        sourcePathResolver,
        cxxBuckConfig,
        cxxPlatform,
        args,
        linkerFlags.build(),
        args.frameworks.or(ImmutableSortedSet.<FrameworkPath>of()),
        args.libraries.or(ImmutableSortedSet.<FrameworkPath>of()),
        args.soname,
        args.cxxRuntimeType,
        linkType,
        linkableDepType,
        bundleLoader,
        blacklist);
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    return createBuildRule(
        params,
        resolver,
        args,
        args.linkStyle,
        Optional.<SourcePath>absent(),
        ImmutableSet.<BuildTarget>of());
  }

  public <A extends Arg> BuildRule createBuildRule(
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args,
      Optional<Linker.LinkableDepType> linkableDepType,
      final Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist) throws NoSuchBuildTargetException {
    BuildTarget buildTarget = params.getBuildTarget();
    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(buildTarget);
    Optional<CxxPlatform> platform = cxxPlatforms.getValue(buildTarget);

    if (params.getBuildTarget().getFlavors()
        .contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      // XXX: This needs bundleLoader for tests..
      CxxPlatform cxxPlatform = platform.or(defaultCxxPlatform);
      SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
      return CxxDescriptionEnhancer.createCompilationDatabase(
          params,
          resolver,
          sourcePathResolver,
          cxxBuckConfig,
          cxxPlatform,
          args);
    } else if (params.getBuildTarget().getFlavors()
        .contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)) {
      return CxxDescriptionEnhancer.createUberCompilationDatabase(
          platform.isPresent() ?
              params :
              params.withFlavor(defaultCxxPlatform.getFlavor()),
          resolver);
    } else if (params.getBuildTarget().getFlavors()
        .contains(CxxInferEnhancer.InferFlavors.INFER.get())) {
      return CxxInferEnhancer.requireInferAnalyzeAndReportBuildRuleForCxxDescriptionArg(
          params,
          resolver,
          new SourcePathResolver(resolver),
          cxxBuckConfig,
          platform.or(defaultCxxPlatform),
          args,
          inferBuckConfig,
          new CxxInferSourceFilter(inferBuckConfig));
    } else if (params.getBuildTarget().getFlavors()
        .contains(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get())) {
      return CxxInferEnhancer.requireInferAnalyzeBuildRuleForCxxDescriptionArg(
          params,
          resolver,
          new SourcePathResolver(resolver),
          cxxBuckConfig,
          platform.or(defaultCxxPlatform),
          args,
          inferBuckConfig,
          new CxxInferSourceFilter(inferBuckConfig));
    } else if (params.getBuildTarget().getFlavors()
        .contains(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.get())) {
      return CxxInferEnhancer.requireAllTransitiveCaptureBuildRules(
          params,
          resolver,
          cxxBuckConfig,
          platform.or(defaultCxxPlatform),
          inferBuckConfig,
          new CxxInferSourceFilter(inferBuckConfig),
          args);
    } else if (params.getBuildTarget().getFlavors()
        .contains(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ONLY.get())) {
      return CxxInferEnhancer.requireInferCaptureAggregatorBuildRuleForCxxDescriptionArg(
          params,
          resolver,
          new SourcePathResolver(resolver),
          cxxBuckConfig,
          platform.or(defaultCxxPlatform),
          args,
          inferBuckConfig,
          new CxxInferSourceFilter(inferBuckConfig));
    } else if (type.isPresent() && platform.isPresent()) {
      // If we *are* building a specific type of this lib, call into the type specific
      // rule builder methods.

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
      switch (type.get().getValue()) {
        case HEADERS:
          return createHeaderSymlinkTreeBuildRule(
              typeParams,
              resolver,
              platform.get(),
              args);
        case EXPORTED_HEADERS:
          return createExportedHeaderSymlinkTreeBuildRule(
              typeParams,
              resolver,
              platform.get(),
              args);
        case SHARED:
          return createSharedLibraryBuildRule(
              typeParams,
              resolver,
              cxxBuckConfig,
              platform.get(),
              args,
              Linker.LinkType.SHARED,
              linkableDepType.or(Linker.LinkableDepType.SHARED),
              Optional.<SourcePath>absent(),
              blacklist);
        case MACH_O_BUNDLE:
          return createSharedLibraryBuildRule(
              typeParams,
              resolver,
              cxxBuckConfig,
              platform.get(),
              args,
              Linker.LinkType.MACH_O_BUNDLE,
              linkableDepType.or(Linker.LinkableDepType.SHARED),
              bundleLoader,
              blacklist);
        case STATIC:
          return createStaticLibraryBuildRule(
              typeParams,
              resolver,
              cxxBuckConfig,
              platform.get(),
              args,
              CxxSourceRuleFactory.PicType.PDC);
        case STATIC_PIC:
          return createStaticLibraryBuildRule(
              typeParams,
              resolver,
              cxxBuckConfig,
              platform.get(),
              args,
              CxxSourceRuleFactory.PicType.PIC);
      }
      throw new RuntimeException("unhandled library build type");
    }

    boolean hasObjectsForAnyPlatform = !args.srcs.get().isEmpty();
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

    Predicate<CxxPlatform> hasExportedHeaders;
    if (!args.exportedHeaders.get().isEmpty()) {
      hasExportedHeaders = Predicates.alwaysTrue();
    } else {
      hasExportedHeaders =
          new Predicate<CxxPlatform>() {
            @Override
            public boolean apply(CxxPlatform input) {
              return !args.exportedPlatformHeaders.get()
                  .getMatchingValues(input.getFlavor().toString()).isEmpty();
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
        FluentIterable.from(args.exportedDeps.get())
            .transform(resolver.getRuleFunction()),
        hasExportedHeaders,
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
        new Function<CxxPlatform, Iterable<com.facebook.buck.rules.args.Arg>>() {
          @Override
          public Iterable<com.facebook.buck.rules.args.Arg> apply(
              CxxPlatform input) {
            ImmutableList<String> flags = CxxFlags.getFlags(
                args.exportedLinkerFlags,
                args.exportedPlatformLinkerFlags,
                input);
            return Iterables.transform(
                flags,
                MacroArg.toMacroArgFunction(
                    MACRO_HANDLER,
                    params.getBuildTarget(),
                    params.getCellRoots(),
                    resolver));
          }
        },
        new Function<CxxPlatform, NativeLinkableInput>() {
          @Override
          public NativeLinkableInput apply(CxxPlatform cxxPlatform) {
            try {
              return getSharedLibraryNativeLinkTargetInput(
                  params,
                  resolver,
                  pathResolver,
                  cxxBuckConfig,
                  cxxPlatform,
                  args,
                  CxxFlags.getFlags(
                      args.linkerFlags,
                      args.platformLinkerFlags,
                      cxxPlatform),
                  CxxFlags.getFlags(
                      args.exportedLinkerFlags,
                      args.exportedPlatformLinkerFlags,
                      cxxPlatform),
                  args.frameworks.or(ImmutableSortedSet.<FrameworkPath>of()),
                  args.libraries.or(ImmutableSortedSet.<FrameworkPath>of()));
            } catch (NoSuchBuildTargetException e) {
              throw new RuntimeException(e);
            }
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
      Function<Optional<String>, Path> cellRoots,
      Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    // Get any parse time deps from the C/C++ platforms.
    deps.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatforms.getValues()));

    try {
      for (ImmutableList<String> values :
          Optional.presentInstances(ImmutableList.of(
                  constructorArg.linkerFlags,
                  constructorArg.exportedLinkerFlags))) {
        for (String val : values) {
          deps.addAll(MACRO_HANDLER.extractParseTimeDeps(buildTarget, cellRoots, val));
        }
      }
      for (PatternMatchedCollection<ImmutableList<String>> values :
          Optional.presentInstances(ImmutableList.of(
                  constructorArg.platformLinkerFlags,
                  constructorArg.exportedPlatformLinkerFlags))) {
        for (Pair<Pattern, ImmutableList<String>> pav : values.getPatternsAndValues()) {
          for (String val : pav.getSecond()) {
            deps.addAll(
                MACRO_HANDLER.extractParseTimeDeps(buildTarget, cellRoots, val));
          }
        }
      }
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
    }

    return deps.build();
  }

  public FlavorDomain<CxxPlatform> getCxxPlatforms() {
    return cxxPlatforms;
  }

  public CxxPlatform getDefaultCxxPlatform() {
    return defaultCxxPlatform;
  }

  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      A args,
      final Class<U> metadataClass) throws NoSuchBuildTargetException {
    if (!metadataClass.isAssignableFrom(CxxCompilationDatabaseDependencies.class) ||
        !buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      return Optional.absent();
    }
    return CxxDescriptionEnhancer
        .createCompilationDatabaseDependencies(buildTarget, cxxPlatforms, resolver, args)
        .transform(
            new Function<CxxCompilationDatabaseDependencies, U>() {
              @Override
              public U apply(CxxCompilationDatabaseDependencies input) {
                return metadataClass.cast(input);
              }
            }
        );
  }

  @Override
  public ImmutableSortedSet<Flavor> addImplicitFlavors(
      ImmutableSortedSet<Flavor> argDefaultFlavors) {
    return addImplicitFlavorsForRuleTypes(
        argDefaultFlavors,
        TYPE);
  }

  public ImmutableSortedSet<Flavor> addImplicitFlavorsForRuleTypes(
      ImmutableSortedSet<Flavor> argDefaultFlavors,
      BuildRuleType... types) {
    Optional<Flavor> typeFlavor = LIBRARY_TYPE.getFlavor(argDefaultFlavors);
    Optional<Flavor> platformFlavor = getCxxPlatforms().getFlavor(argDefaultFlavors);

    LOG.debug("Got arg default type %s platform %s", typeFlavor, platformFlavor);

    for (BuildRuleType type : types) {
      ImmutableMap<String, Flavor> libraryDefaults =
          cxxBuckConfig.getDefaultFlavorsForRuleType(type);

      if (!typeFlavor.isPresent()) {
        typeFlavor = Optional.fromNullable(
            libraryDefaults.get(CxxBuckConfig.DEFAULT_FLAVOR_LIBRARY_TYPE));
      }

      if (!platformFlavor.isPresent()) {
        platformFlavor = Optional.fromNullable(
            libraryDefaults.get(CxxBuckConfig.DEFAULT_FLAVOR_PLATFORM));
      }
    }

    ImmutableSortedSet<Flavor> result = ImmutableSortedSet.of(
        // Default to static if not otherwise specified.
        typeFlavor.or(CxxDescriptionEnhancer.STATIC_FLAVOR),
        platformFlavor.or(defaultCxxPlatform.getFlavor()));

    LOG.debug(
        "Got default flavors %s for rule types %s",
        result,
        Arrays.toString(types));
    return result;
  }

  @SuppressFieldNotInitialized
  public static class Arg extends LinkableCxxConstructorArg {
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
  }

}
