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
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.ImplicitFlavorsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class CxxLibraryDescription implements
    Description<CxxLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<CxxLibraryDescription.Arg>,
    ImplicitFlavorsInferringDescription,
    Flavored,
    MetadataProvidingDescription<CxxLibraryDescription.Arg>,
    VersionPropagator<CxxLibraryDescription.Arg> {

  private static final Logger LOG = Logger.get(CxxLibraryDescription.class);

  public enum Type implements FlavorConvertible {
    HEADERS(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
    EXPORTED_HEADERS(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
    SANDBOX_TREE(CxxDescriptionEnhancer.SANDBOX_TREE_FLAVOR),
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    SHARED_INTERFACE(InternalFlavor.of("shared-interface")),
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

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("C/C++ Library Type", Type.class);

  public enum MetadataType implements FlavorConvertible {

    COMPILATION_DATABASE_DEPS(InternalFlavor.of("compilation-database-deps")),
    CXX_HEADERS(InternalFlavor.of("header-symlink-tree")),
    CXX_PREPROCESSOR_INPUT(InternalFlavor.of("cxx-preprocessor-input")),
    ;

    private final Flavor flavor;

    MetadataType(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }

  }

  public static final FlavorDomain<MetadataType> METADATA_TYPE =
      FlavorDomain.from("C/C++ Metadata Type", MetadataType.class);

  private static final FlavorDomain<HeaderVisibility> HEADER_VISIBILITY =
      FlavorDomain.from("C/C++ Header Visibility", HeaderVisibility.class);

  private static final FlavorDomain<CxxPreprocessables.HeaderMode> HEADER_MODE =
      FlavorDomain.from("C/C++ Header Mode", CxxPreprocessables.HeaderMode.class);

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
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return
        Optional.of(
            ImmutableSet.of(
                // Missing: CXX Compilation Database
                // Missing: CXX Description Enhancer
                // Missing: CXX Infer Enhancer
                cxxPlatforms,
                LinkerMapMode.FLAVOR_DOMAIN,
                StripStyle.FLAVOR_DOMAIN
            )
        );
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return cxxPlatforms.containsAnyOf(flavors) ||
        flavors.contains(CxxCompilationDatabase.COMPILATION_DATABASE) ||
        flavors.contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE) ||
        flavors.contains(CxxInferEnhancer.InferFlavors.INFER.get()) ||
        flavors.contains(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get()) ||
        flavors.contains(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.get()) ||
        flavors.contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR) ||
        LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(flavors);

  }

  private static ImmutableMap<CxxPreprocessAndCompile, SourcePath> requireObjects(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxSourceRuleFactory.PicType pic,
      CxxLibraryDescription.Arg args,
      ImmutableSet<BuildRule> deps)
      throws NoSuchBuildTargetException {

    boolean shouldCreatePrivateHeadersSymlinks = args.xcodePrivateHeadersSymlinks.orElse(true);

    HeaderSymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params,
            ruleResolver,
            cxxPlatform,
            CxxDescriptionEnhancer.parseHeaders(
                params.getBuildTarget(),
                ruleResolver,
                ruleFinder,
                sourcePathResolver,
                Optional.of(cxxPlatform),
                args),
            HeaderVisibility.PRIVATE,
            shouldCreatePrivateHeadersSymlinks);

    Optional<SymlinkTree> sandboxTree = Optional.empty();
    if (cxxBuckConfig.sandboxSources()) {
      sandboxTree =
          CxxDescriptionEnhancer.createSandboxTree(
              params,
              ruleResolver,
              cxxPlatform);
    }

    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInputFromDependencies =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            params,
            cxxPlatform,
            deps,
            CxxFlags.getLanguageFlags(
                args.preprocessorFlags,
                args.platformPreprocessorFlags,
                args.langPreprocessorFlags,
                cxxPlatform),
            ImmutableList.of(headerSymlinkTree),
            ImmutableSet.of(),
            getTransitiveCxxPreprocessorInput(
                params,
                ruleResolver,
                cxxPlatform,
                deps),
            args.includeDirs,
            sandboxTree);

    // Create rule to build the object files.
    return CxxSourceRuleFactory.requirePreprocessAndCompileRules(
        params,
        ruleResolver,
        sourcePathResolver,
        ruleFinder,
        cxxBuckConfig,
        cxxPlatform,
        cxxPreprocessorInputFromDependencies,
        CxxFlags.getLanguageFlags(
            args.compilerFlags,
            args.platformCompilerFlags,
            args.langCompilerFlags,
            cxxPlatform),
        args.prefixHeader,
        args.precompiledHeader,
        CxxDescriptionEnhancer.parseCxxSources(
            params.getBuildTarget(),
            ruleResolver,
            ruleFinder,
            sourcePathResolver,
            cxxPlatform,
            args),
        pic,
        sandboxTree);
  }

  public static ImmutableCollection<CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      ImmutableSet<BuildRule> deps)
      throws NoSuchBuildTargetException {

    // Check if there is a target node representative for the library in the action graph and,
    // if so, grab the cached transitive C/C++ preprocessor input from that.
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

    Map<BuildTarget, CxxPreprocessorInput> input = Maps.newLinkedHashMap();
    input.put(
        params.getBuildTarget(),
        ruleResolver
            .requireMetadata(
                params.getBuildTarget().withAppendedFlavors(
                    MetadataType.CXX_PREPROCESSOR_INPUT.getFlavor(),
                    cxxPlatform.getFlavor(),
                    HeaderVisibility.PUBLIC.getFlavor()),
                CxxPreprocessorInput.class)
            .orElseThrow(IllegalStateException::new));
    for (BuildRule rule : deps) {
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
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      Arg arg,
      ImmutableSet<BuildRule> deps,
      ImmutableList<StringWithMacros> linkerFlags,
      ImmutableList<StringWithMacros> exportedLinkerFlags,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries)
      throws NoSuchBuildTargetException {

    // Create rules for compiling the PIC object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        requireObjects(
            params,
            ruleResolver,
            pathResolver,
            ruleFinder,
            cxxBuckConfig,
            cxxPlatform,
            CxxSourceRuleFactory.PicType.PIC,
            arg,
            deps);

    return NativeLinkableInput.builder()
        .addAllArgs(
            CxxDescriptionEnhancer.toStringWithMacrosArgs(
                params.getBuildTarget(),
                cellRoots,
                ruleResolver,
                cxxPlatform,
                Iterables.concat(linkerFlags, exportedLinkerFlags)))
        .addAllArgs(SourcePathArg.from(objects.values()))
        .setFrameworks(frameworks)
        .setLibraries(libraries)
        .build();
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
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      Arg args,
      ImmutableSet<BuildRule> deps,
      ImmutableList<StringWithMacros> linkerFlags,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      Optional<String> soname,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType,
      Linker.LinkType linkType,
      Linker.LinkableDepType linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist)
      throws NoSuchBuildTargetException {
    Optional<LinkerMapMode> flavoredLinkerMapMode = LinkerMapMode.FLAVOR_DOMAIN.getValue(
        params.getBuildTarget());
    params = LinkerMapMode.removeLinkerMapModeFlavorInParams(params, flavoredLinkerMapMode);

    // Create rules for compiling the PIC object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        requireObjects(
            params,
            ruleResolver,
            pathResolver,
            ruleFinder,
            cxxBuckConfig,
            cxxPlatform,
            CxxSourceRuleFactory.PicType.PIC,
            args,
            deps);

    // Setup the rules to link the shared library.
    BuildTarget sharedTarget =
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
            LinkerMapMode.restoreLinkerMapModeFlavorInParams(params, flavoredLinkerMapMode)
                .getBuildTarget(),
            cxxPlatform.getFlavor(),
            linkType);

    String sharedLibrarySoname = CxxDescriptionEnhancer.getSharedLibrarySoname(
        soname,
        params.getBuildTarget(),
        cxxPlatform);
    Path sharedLibraryPath = CxxDescriptionEnhancer.getSharedLibraryPath(
        params.getProjectFilesystem(),
        sharedTarget,
        sharedLibrarySoname);
    ImmutableList.Builder<StringWithMacros> extraLdFlagsBuilder = ImmutableList.builder();
    extraLdFlagsBuilder.addAll(linkerFlags);
    ImmutableList<StringWithMacros> extraLdFlags = extraLdFlagsBuilder.build();

    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        LinkerMapMode.restoreLinkerMapModeFlavorInParams(params, flavoredLinkerMapMode),
        ruleResolver,
        pathResolver,
        ruleFinder,
        sharedTarget,
        linkType,
        Optional.of(sharedLibrarySoname),
        sharedLibraryPath,
        linkableDepType,
        RichStream.from(deps)
            .filter(NativeLinkable.class)
            .toImmutableList(),
        cxxRuntimeType,
        bundleLoader,
        blacklist,
        NativeLinkableInput.builder()
            .addAllArgs(
                CxxDescriptionEnhancer.toStringWithMacrosArgs(
                    params.getBuildTarget(),
                    cellRoots,
                    ruleResolver,
                    cxxPlatform,
                    extraLdFlags))
            .addAllArgs(SourcePathArg.from(objects.values()))
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
    arg.deps = ImmutableSortedSet.of();
    arg.exportedDeps = ImmutableSortedSet.of();
    arg.srcs = ImmutableSortedSet.of();
    arg.platformSrcs = PatternMatchedCollection.of();
    arg.prefixHeader = Optional.empty();
    arg.precompiledHeader = Optional.empty();
    arg.headers = SourceList.ofUnnamedSources(ImmutableSortedSet.of());
    arg.platformHeaders = PatternMatchedCollection.of();
    arg.exportedHeaders = SourceList.ofUnnamedSources(ImmutableSortedSet.of());
    arg.exportedPlatformHeaders = PatternMatchedCollection.of();
    arg.compilerFlags = ImmutableList.of();
    arg.platformCompilerFlags = PatternMatchedCollection.of();
    arg.langCompilerFlags = ImmutableMap.of();
    arg.exportedPreprocessorFlags = ImmutableList.of();
    arg.exportedPlatformPreprocessorFlags = PatternMatchedCollection.of();
    arg.exportedLangPreprocessorFlags = ImmutableMap.of();
    arg.preprocessorFlags = ImmutableList.of();
    arg.platformPreprocessorFlags = PatternMatchedCollection.of();
    arg.langPreprocessorFlags = ImmutableMap.of();
    arg.linkerFlags = ImmutableList.of();
    arg.exportedLinkerFlags = ImmutableList.of();
    arg.platformLinkerFlags = PatternMatchedCollection.of();
    arg.exportedPlatformLinkerFlags = PatternMatchedCollection.of();
    arg.cxxRuntimeType = Optional.empty();
    arg.forceStatic = Optional.empty();
    arg.preferredLinkage = Optional.empty();
    arg.linkWhole = Optional.empty();
    arg.headerNamespace = Optional.empty();
    arg.soname = Optional.empty();
    arg.frameworks = ImmutableSortedSet.of();
    arg.libraries = ImmutableSortedSet.of();
    arg.tests = ImmutableSortedSet.of();
    arg.supportedPlatformsRegex = Optional.empty();
    arg.linkStyle = Optional.empty();
    arg.bridgingHeader = Optional.empty();
    arg.moduleName = Optional.empty();
    arg.xcodePublicHeadersSymlinks = Optional.empty();
    arg.xcodePrivateHeadersSymlinks = Optional.empty();
    return arg;
  }

  /**
   * @return a {@link HeaderSymlinkTree} for the headers of this C/C++ library.
   */
  private <A extends Arg> HeaderSymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args)
      throws NoSuchBuildTargetException {
    boolean shouldCreatePrivateHeaderSymlinks = args.xcodePrivateHeadersSymlinks.orElse(true);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        params,
        resolver,
        cxxPlatform,
        CxxDescriptionEnhancer.parseHeaders(
            params.getBuildTarget(),
            resolver,
            ruleFinder,
            pathResolver,
            Optional.of(cxxPlatform),
            args),
        HeaderVisibility.PRIVATE,
        shouldCreatePrivateHeaderSymlinks);
  }

  /**
   * @return a {@link HeaderSymlinkTree} for the exported headers of this C/C++ library.
   */
  private <A extends Arg> HeaderSymlinkTree createExportedHeaderSymlinkTreeBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPreprocessables.HeaderMode mode,
      A args)
      throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        params,
        resolver,
        mode,
        CxxDescriptionEnhancer.parseExportedHeaders(
            params.getBuildTarget(),
            resolver,
            ruleFinder,
            pathResolver,
            Optional.empty(),
            args),
        HeaderVisibility.PUBLIC);
  }

  /**
   * @return a {@link HeaderSymlinkTree} for the exported headers of this C/C++ library.
   */
  private <A extends Arg> HeaderSymlinkTree createExportedPlatformHeaderSymlinkTreeBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args)
      throws NoSuchBuildTargetException {
    boolean shouldCreatePublicHeaderSymlinks = args.xcodePublicHeadersSymlinks.orElse(true);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        params,
        resolver,
        cxxPlatform,
        CxxDescriptionEnhancer.parseExportedPlatformHeaders(
            params.getBuildTarget(),
            resolver,
            ruleFinder,
            pathResolver,
            cxxPlatform,
            args),
        HeaderVisibility.PUBLIC,
        shouldCreatePublicHeaderSymlinks);
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
      ImmutableSet<BuildRule> deps,
      CxxSourceRuleFactory.PicType pic) throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);

    // Create rules for compiling the object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        requireObjects(
            params,
            resolver,
            sourcePathResolver,
            ruleFinder,
            cxxBuckConfig,
            cxxPlatform,
            pic,
            args,
            deps);

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
              Suppliers.ofInstance(ImmutableSortedSet.of()),
              Suppliers.ofInstance(ImmutableSortedSet.of()),
              ImmutableSortedSet.of(),
              params.getProjectFilesystem()));
    }

    Path staticLibraryPath =
        CxxDescriptionEnhancer.getStaticLibraryPath(
            params.getProjectFilesystem(),
            params.getBuildTarget(),
            cxxPlatform.getFlavor(),
            pic,
            cxxPlatform.getStaticLibraryExtension());
    return Archive.from(
        staticTarget,
        params,
        ruleFinder,
        cxxPlatform,
        cxxBuckConfig.getArchiveContents(),
        staticLibraryPath,
        ImmutableList.copyOf(objects.values()));
  }

  /**
   * @return a {@link CxxLink} rule which builds a shared library version of this C/C++ library.
   */
  private static <A extends Arg> CxxLink createSharedLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      A args,
      ImmutableSet<BuildRule> deps,
      Linker.LinkType linkType,
      Linker.LinkableDepType linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist)
      throws NoSuchBuildTargetException {
    ImmutableList.Builder<StringWithMacros> linkerFlags = ImmutableList.builder();

    linkerFlags.addAll(
        CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
            args.linkerFlags,
            args.platformLinkerFlags,
            cxxPlatform));

    linkerFlags.addAll(
        CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
            args.exportedLinkerFlags,
            args.exportedPlatformLinkerFlags,
            cxxPlatform));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver sourcePathResolver =
        new SourcePathResolver(ruleFinder);
    return createSharedLibrary(
        params,
        resolver,
        sourcePathResolver,
        ruleFinder,
        cellRoots,
        cxxBuckConfig,
        cxxPlatform,
        args,
        deps,
        linkerFlags.build(),
        args.frameworks,
        args.libraries,
        args.soname,
        args.cxxRuntimeType,
        linkType,
        linkableDepType,
        bundleLoader,
        blacklist);
  }

  private static <A extends Arg> BuildRule createSharedLibraryInterface(
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    BuildTarget baseTarget = baseParams.getBuildTarget();

    Optional<SharedLibraryInterfaceFactory> factory =
        cxxPlatform.getSharedLibraryInterfaceFactory();
    if (!factory.isPresent()) {
      throw new HumanReadableException(
          "%s: C/C++ platform %s does not support shared library interfaces",
          baseTarget,
          cxxPlatform.getFlavor());
    }

    CxxLink sharedLibrary = (CxxLink) resolver.requireRule(
        baseTarget.withAppendedFlavors(
            cxxPlatform.getFlavor(),
            Type.SHARED.getFlavor()));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    return factory.get().createSharedInterfaceLibrary(
        baseTarget.withAppendedFlavors(
            Type.SHARED_INTERFACE.getFlavor(),
            cxxPlatform.getFlavor()),
        baseParams,
        resolver,
        pathResolver,
        ruleFinder,
        sharedLibrary.getSourcePathToOutput());
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    return createBuildRule(
        params,
        resolver,
        cellRoots,
        args,
        args.linkStyle,
        Optional.empty(),
        ImmutableSet.of(),
        ImmutableSortedSet.of());
  }

  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams metadataRuleParams,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      final A args,
      Optional<Linker.LinkableDepType> linkableDepType,
      final Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      ImmutableSortedSet<BuildTarget> extraDeps)
      throws NoSuchBuildTargetException {

    // Create a copy of the metadata-rule params with the deps removed to pass around into library
    // code.  This should prevent this code from using the over-specified deps when constructing
    // build rules.
    BuildRuleParams params = metadataRuleParams.copyInvalidatingDeps();

    BuildTarget buildTarget = params.getBuildTarget();
    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, Type>> type = getLibType(buildTarget);
    Optional<CxxPlatform> platform = cxxPlatforms.getValue(buildTarget);
    CxxDeps cxxDeps =
        CxxDeps.builder()
            .addDeps(args.getCxxDeps())
            .addDeps(extraDeps)
            .build();

    if (params.getBuildTarget().getFlavors()
        .contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      // XXX: This needs bundleLoader for tests..
      CxxPlatform cxxPlatform = platform.orElse(defaultCxxPlatform);
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
      SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);
      BuildRuleParams paramsWithoutFlavor = params.withoutFlavor(
          CxxCompilationDatabase.COMPILATION_DATABASE);
      ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
          requireObjects(
              paramsWithoutFlavor,
              resolver,
              sourcePathResolver,
              ruleFinder,
              cxxBuckConfig,
              cxxPlatform,
              CxxSourceRuleFactory.PicType.PIC,
              args,
              cxxDeps.get(resolver, cxxPlatform));
      return CxxCompilationDatabase.createCompilationDatabase(params, objects.keySet());
    } else if (params.getBuildTarget().getFlavors()
        .contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)) {
      return CxxDescriptionEnhancer.createUberCompilationDatabase(
          platform.isPresent() ?
              params :
              params.withAppendedFlavor(defaultCxxPlatform.getFlavor()),
          resolver);
    } else if (params.getBuildTarget().getFlavors()
        .contains(CxxInferEnhancer.InferFlavors.INFER.get())) {
      return CxxInferEnhancer.requireInferAnalyzeAndReportBuildRuleForCxxDescriptionArg(
          params,
          resolver,
          cxxBuckConfig,
          platform.orElse(defaultCxxPlatform),
          args,
          inferBuckConfig,
          new CxxInferSourceFilter(inferBuckConfig));
    } else if (params.getBuildTarget().getFlavors()
        .contains(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.get())) {
      return CxxInferEnhancer.requireInferAnalyzeBuildRuleForCxxDescriptionArg(
          params,
          resolver,
          cxxBuckConfig,
          platform.orElse(defaultCxxPlatform),
          args,
          inferBuckConfig,
          new CxxInferSourceFilter(inferBuckConfig));
    } else if (params.getBuildTarget().getFlavors()
        .contains(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.get())) {
      return CxxInferEnhancer.requireAllTransitiveCaptureBuildRules(
          params,
          resolver,
          cxxBuckConfig,
          platform.orElse(defaultCxxPlatform),
          inferBuckConfig,
          new CxxInferSourceFilter(inferBuckConfig),
          args);
    } else if (params.getBuildTarget().getFlavors()
        .contains(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ONLY.get())) {
      return CxxInferEnhancer.requireInferCaptureAggregatorBuildRuleForCxxDescriptionArg(
          params,
          resolver,
          cxxBuckConfig,
          platform.orElse(defaultCxxPlatform),
          args,
          inferBuckConfig,
          new CxxInferSourceFilter(inferBuckConfig));
    } else if (type.isPresent() && !platform.isPresent()) {
      BuildRuleParams untypedParams = getUntypedParams(params);
      switch (type.get().getValue()) {
        case EXPORTED_HEADERS:
          Optional<CxxPreprocessables.HeaderMode> mode =
              HEADER_MODE.getValue(params.getBuildTarget());
          if (mode.isPresent()) {
            return createExportedHeaderSymlinkTreeBuildRule(
                untypedParams,
                resolver,
                mode.get(),
                args);
          }
          break;
        // $CASES-OMITTED$
        default:
      }

    } else if (type.isPresent() && platform.isPresent()) {
      // If we *are* building a specific type of this lib, call into the type specific
      // rule builder methods.

      BuildRuleParams untypedParams = getUntypedParams(params);
      switch (type.get().getValue()) {
        case HEADERS:
          return createHeaderSymlinkTreeBuildRule(
              untypedParams,
              resolver,
              platform.get(),
              args);
        case EXPORTED_HEADERS:
          return createExportedPlatformHeaderSymlinkTreeBuildRule(
              untypedParams,
              resolver,
              platform.get(),
              args);
        case SHARED:
          return createSharedLibraryBuildRule(
              untypedParams,
              resolver,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(resolver, platform.get()),
              Linker.LinkType.SHARED,
              linkableDepType.orElse(Linker.LinkableDepType.SHARED),
              Optional.empty(),
              blacklist);
        case SHARED_INTERFACE:
          return createSharedLibraryInterface(
              untypedParams,
              resolver,
              platform.get());
        case MACH_O_BUNDLE:
          return createSharedLibraryBuildRule(
              untypedParams,
              resolver,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(resolver, platform.get()),
              Linker.LinkType.MACH_O_BUNDLE,
              linkableDepType.orElse(Linker.LinkableDepType.SHARED),
              bundleLoader,
              blacklist);
        case STATIC:
          return createStaticLibraryBuildRule(
              untypedParams,
              resolver,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(resolver, platform.get()),
              CxxSourceRuleFactory.PicType.PDC);
        case STATIC_PIC:
          return createStaticLibraryBuildRule(
              untypedParams,
              resolver,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(resolver, platform.get()),
              CxxSourceRuleFactory.PicType.PIC);
        case SANDBOX_TREE:
          return CxxDescriptionEnhancer.createSandboxTreeBuildRule(
              resolver,
              args,
              platform.get(),
              untypedParams);
      }
      throw new RuntimeException("unhandled library build type");
    }

    boolean hasObjectsForAnyPlatform = !args.srcs.isEmpty();
    Predicate<CxxPlatform> hasObjects;
    if (hasObjectsForAnyPlatform) {
      hasObjects = x -> true;
    } else {
      hasObjects = input -> !args.platformSrcs.getMatchingValues(
          input.getFlavor().toString()).isEmpty();
    }

    Predicate<CxxPlatform> hasExportedHeaders;
    if (!args.exportedHeaders.isEmpty()) {
      hasExportedHeaders = x -> true;
    } else {
      hasExportedHeaders =
          input -> !args.exportedPlatformHeaders
              .getMatchingValues(input.getFlavor().toString()).isEmpty();
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    final SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    return new CxxLibrary(
        metadataRuleParams,
        resolver,
        args.getPrivateCxxDeps(),
        args.getExportedCxxDeps(),
        hasExportedHeaders,
        Predicates.not(hasObjects),
        input -> {
          ImmutableList<StringWithMacros> flags =
              CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                  args.exportedLinkerFlags,
                  args.exportedPlatformLinkerFlags,
                  input);
          return CxxDescriptionEnhancer.toStringWithMacrosArgs(
              params.getBuildTarget(),
              cellRoots,
              resolver,
              input,
              flags);
        },
        cxxPlatform -> {
          try {
            return getSharedLibraryNativeLinkTargetInput(
                params,
                resolver,
                pathResolver,
                ruleFinder,
                cellRoots,
                cxxBuckConfig,
                cxxPlatform,
                args,
                cxxDeps.get(resolver, cxxPlatform),
                CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                    args.linkerFlags,
                    args.platformLinkerFlags,
                    cxxPlatform),
                CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                    args.exportedLinkerFlags,
                    args.exportedPlatformLinkerFlags,
                    cxxPlatform),
                args.frameworks,
                args.libraries);
          } catch (NoSuchBuildTargetException e) {
            throw new RuntimeException(e);
          }
        },
        args.supportedPlatformsRegex,
        args.frameworks,
        args.libraries,
        args.forceStatic.orElse(false)
            ? NativeLinkable.Linkage.STATIC
            : args.preferredLinkage.orElse(NativeLinkable.Linkage.ANY),
        args.linkWhole.orElse(false),
        args.soname,
        args.tests,
        args.canBeAsset.orElse(false),
        !params.getBuildTarget().getFlavors()
            .contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR));
  }

  public static Optional<Map.Entry<Flavor, Type>> getLibType(BuildTarget buildTarget) {
    return LIBRARY_TYPE.getFlavorAndValue(buildTarget);
  }

  static BuildRuleParams getUntypedParams(BuildRuleParams params) {
    Optional<Map.Entry<Flavor, Type>> type = getLibType(params.getBuildTarget());
    if (!type.isPresent()) {
      return params;
    }
    Set<Flavor> flavors = Sets.newHashSet(params.getBuildTarget().getFlavors());
    flavors.remove(type.get().getKey());
    BuildTarget target = BuildTarget
        .builder(params.getBuildTarget().getUnflavoredBuildTarget())
        .addAllFlavors(flavors)
        .build();
    return params.withBuildTarget(target);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Get any parse time deps from the C/C++ platforms.
    extraDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatforms.getValues()));
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
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      final Class<U> metadataClass) throws NoSuchBuildTargetException {

    Map.Entry<Flavor, MetadataType> type =
        METADATA_TYPE.getFlavorAndValue(buildTarget).orElseThrow(IllegalArgumentException::new);
    BuildTarget baseTarget = buildTarget.withoutFlavors(type.getKey());

    switch (type.getValue()) {

      case CXX_HEADERS: {
        Optional<CxxHeaders> symlinkTree = Optional.empty();
        if (!args.exportedHeaders.isEmpty()) {
          CxxPreprocessables.HeaderMode mode = HEADER_MODE.getRequiredValue(buildTarget);
          baseTarget = baseTarget.withoutFlavors(mode.getFlavor());
          symlinkTree =
              Optional.of(
                  CxxSymlinkTreeHeaders.from(
                      (HeaderSymlinkTree) resolver.requireRule(
                          baseTarget.withAppendedFlavors(
                              Type.EXPORTED_HEADERS.getFlavor(),
                              mode.getFlavor())),
                      CxxPreprocessables.IncludeType.LOCAL));
        }
        return symlinkTree.map(metadataClass::cast);
      }

      case CXX_PREPROCESSOR_INPUT: {
        Map.Entry<Flavor, CxxPlatform> platform =
            cxxPlatforms.getFlavorAndValue(buildTarget).orElseThrow(IllegalArgumentException::new);
        Map.Entry<Flavor, HeaderVisibility> visibility =
            HEADER_VISIBILITY.getFlavorAndValue(buildTarget)
                .orElseThrow(IllegalArgumentException::new);
        baseTarget =
            baseTarget.withoutFlavors(
                platform.getKey(),
                visibility.getKey());

        CxxPreprocessorInput.Builder cxxPreprocessorInputBuilder = CxxPreprocessorInput.builder();

        // TODO(agallagher): We currently always add exported flags and frameworks to the
        // preprocessor input to mimic existing behavior, but this should likely be fixed.
        cxxPreprocessorInputBuilder.putAllPreprocessorFlags(
            CxxFlags.getLanguageFlags(
                args.exportedPreprocessorFlags,
                args.exportedPlatformPreprocessorFlags,
                args.exportedLangPreprocessorFlags,
                platform.getValue()));
        cxxPreprocessorInputBuilder.addAllFrameworks(args.frameworks);

        if (visibility.getValue() == HeaderVisibility.PRIVATE &&
            !args.headers.isEmpty()) {
          HeaderSymlinkTree symlinkTree =
              (HeaderSymlinkTree) resolver.requireRule(
                  baseTarget.withAppendedFlavors(
                      platform.getKey(),
                      Type.HEADERS.getFlavor()));
          cxxPreprocessorInputBuilder.addIncludes(
              CxxSymlinkTreeHeaders.from(symlinkTree, CxxPreprocessables.IncludeType.LOCAL));
        }

        if (visibility.getValue() == HeaderVisibility.PUBLIC) {

          // Add platform-agnostic headers.
          boolean shouldCreatePublicHeaderSymlinks = args.xcodePublicHeadersSymlinks.orElse(true);
          CxxPreprocessables.HeaderMode mode =
              CxxDescriptionEnhancer.getHeaderModeForPlatform(
                  resolver,
                  platform.getValue(),
                  shouldCreatePublicHeaderSymlinks);
          Optional<CxxHeaders> exportedHeaders =
              resolver.requireMetadata(
                  baseTarget.withAppendedFlavors(
                      MetadataType.CXX_HEADERS.getFlavor(),
                      mode.getFlavor()),
                  CxxHeaders.class);
          exportedHeaders.ifPresent(cxxPreprocessorInputBuilder::addIncludes);

          // Add platform-specific headers.
          if (!args.exportedPlatformHeaders.getMatchingValues(platform.getKey().toString())
                  .isEmpty()) {
            HeaderSymlinkTree symlinkTree =
                (HeaderSymlinkTree) resolver.requireRule(
                    baseTarget.withAppendedFlavors(
                        platform.getKey(),
                        Type.EXPORTED_HEADERS.getFlavor()));
            cxxPreprocessorInputBuilder.addIncludes(
                CxxSymlinkTreeHeaders.from(symlinkTree, CxxPreprocessables.IncludeType.LOCAL));
          }
        }

        CxxPreprocessorInput cxxPreprocessorInput = cxxPreprocessorInputBuilder.build();
        return Optional.of(cxxPreprocessorInput).map(metadataClass::cast);
      }

      case COMPILATION_DATABASE_DEPS: {
        return CxxDescriptionEnhancer
            .createCompilationDatabaseDependencies(buildTarget, cxxPlatforms, resolver, args).map(
                metadataClass::cast);
      }

    }

    throw new IllegalStateException(String.format("unhandled metadata type: %s", type.getValue()));
  }

  @Override
  public ImmutableSortedSet<Flavor> addImplicitFlavors(
      ImmutableSortedSet<Flavor> argDefaultFlavors) {
    return addImplicitFlavorsForRuleTypes(argDefaultFlavors, Description.getBuildRuleType(this));
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
        typeFlavor = Optional.ofNullable(
            libraryDefaults.get(CxxBuckConfig.DEFAULT_FLAVOR_LIBRARY_TYPE));
      }

      if (!platformFlavor.isPresent()) {
        platformFlavor = Optional.ofNullable(
            libraryDefaults.get(CxxBuckConfig.DEFAULT_FLAVOR_PLATFORM));
      }
    }

    ImmutableSortedSet<Flavor> result = ImmutableSortedSet.of(
        // Default to static if not otherwise specified.
        typeFlavor.orElse(CxxDescriptionEnhancer.STATIC_FLAVOR),
        platformFlavor.orElse(defaultCxxPlatform.getFlavor()));

    LOG.debug(
        "Got default flavors %s for rule types %s",
        result,
        Arrays.toString(types));
    return result;
  }

  @SuppressFieldNotInitialized
  public static class Arg extends LinkableCxxConstructorArg {

    public SourceList exportedHeaders = SourceList.EMPTY;
    public PatternMatchedCollection<SourceList> exportedPlatformHeaders =
        PatternMatchedCollection.of();
    public ImmutableList<String> exportedPreprocessorFlags = ImmutableList.of();
    public PatternMatchedCollection<ImmutableList<String>>
        exportedPlatformPreprocessorFlags = PatternMatchedCollection.of();
    public ImmutableMap<CxxSource.Type, ImmutableList<String>>
        exportedLangPreprocessorFlags = ImmutableMap.of();
    public ImmutableList<StringWithMacros> exportedLinkerFlags = ImmutableList.of();
    public PatternMatchedCollection<ImmutableList<StringWithMacros>> exportedPlatformLinkerFlags =
        PatternMatchedCollection.of();
    public ImmutableSortedSet<BuildTarget> exportedDeps = ImmutableSortedSet.of();
    public PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> exportedPlatformDeps =
        PatternMatchedCollection.of();
    public Optional<Pattern> supportedPlatformsRegex;
    public Optional<String> soname;
    public Optional<Boolean> forceStatic;
    public Optional<Boolean> linkWhole;
    public Optional<Boolean> canBeAsset;
    public Optional<NativeLinkable.Linkage> preferredLinkage;
    public Optional<Boolean> xcodePublicHeadersSymlinks;
    public Optional<Boolean> xcodePrivateHeadersSymlinks;

    // These fields are passed through to SwiftLibrary for mixed C/Swift targets; they are not
    // used otherwise.
    public Optional<SourcePath> bridgingHeader;
    public Optional<String> moduleName;

    /**
     * @return C/C++ deps which are propagated to dependents.
     */
    CxxDeps getExportedCxxDeps() {
      return CxxDeps.builder()
          .addDeps(exportedDeps)
          .addPlatformDeps(exportedPlatformDeps)
          .build();
    }

    /**
     * @return C/C++ deps which are *not* propagated to dependents.
     */
    CxxDeps getPrivateCxxDeps() {
      return super.getCxxDeps();
    }

    /**
     * Override parent class's deps to include exported deps.
     *
     * @return the C/C++ deps this rule builds against.
     */
    @Override
    public CxxDeps getCxxDeps() {
      return CxxDeps.concat(
          getPrivateCxxDeps(),
          getExportedCxxDeps());
    }

  }

}
