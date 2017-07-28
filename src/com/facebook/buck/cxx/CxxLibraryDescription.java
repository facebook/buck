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

import com.facebook.buck.cxx.platform.CxxPlatform;
import com.facebook.buck.cxx.platform.Linker;
import com.facebook.buck.cxx.platform.NativeLinkable;
import com.facebook.buck.cxx.platform.NativeLinkableInput;
import com.facebook.buck.cxx.platform.SharedLibraryInterfaceFactory;
import com.facebook.buck.io.ProjectFilesystem;
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
import com.facebook.buck.rules.DefaultSourcePathResolver;
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
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class CxxLibraryDescription
    implements Description<CxxLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<CxxLibraryDescription.CommonArg>,
        ImplicitFlavorsInferringDescription,
        Flavored,
        MetadataProvidingDescription<CxxLibraryDescriptionArg>,
        VersionPropagator<CxxLibraryDescriptionArg> {

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
    return Optional.of(
        ImmutableSet.of(
            // Missing: CXX Compilation Database
            // Missing: CXX Description Enhancer
            // Missing: CXX Infer Enhancer
            cxxPlatforms, LinkerMapMode.FLAVOR_DOMAIN, StripStyle.FLAVOR_DOMAIN));
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return cxxPlatforms.containsAnyOf(flavors)
        || flavors.contains(CxxCompilationDatabase.COMPILATION_DATABASE)
        || flavors.contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)
        || CxxInferEnhancer.INFER_FLAVOR_DOMAIN.containsAnyOf(flavors)
        || flavors.contains(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.getFlavor())
        || flavors.contains(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor())
        || flavors.contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
        || LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(flavors);
  }

  /**
   * This function is broken out so that CxxInferEnhancer can get a list of dependencies for
   * building the library.
   */
  static ImmutableList<CxxPreprocessorInput> getPreprocessorInputsForBuildingLibrarySources(
      BuildRuleResolver ruleResolver,
      CellPathResolver cellRoots,
      BuildTarget target,
      CommonArg args,
      CxxPlatform cxxPlatform,
      ImmutableSet<BuildRule> deps,
      TransitiveCxxPreprocessorInputFunction transitivePreprocessorInputs,
      HeaderSymlinkTree headerSymlinkTree,
      Optional<SymlinkTree> sandboxTree)
      throws NoSuchBuildTargetException {
    return CxxDescriptionEnhancer.collectCxxPreprocessorInput(
        target,
        cxxPlatform,
        deps,
        ImmutableListMultimap.copyOf(
            Multimaps.transformValues(
                CxxFlags.getLanguageFlagsWithMacros(
                    args.getPreprocessorFlags(),
                    args.getPlatformPreprocessorFlags(),
                    args.getLangPreprocessorFlags(),
                    cxxPlatform),
                f ->
                    CxxDescriptionEnhancer.toStringWithMacrosArgs(
                        target, cellRoots, ruleResolver, cxxPlatform, f))),
        ImmutableList.of(headerSymlinkTree),
        ImmutableSet.of(),
        RichStream.from(
                transitivePreprocessorInputs.apply(
                    target,
                    ruleResolver,
                    cxxPlatform,
                    deps,
                    // Also add private deps if we are _not_ reexporting all deps.
                    args.isReexportAllHeaderDependencies()
                        ? CxxDeps.EMPTY
                        : args.getPrivateCxxDeps()))
            .toOnceIterable(),
        args.getIncludeDirs(),
        sandboxTree);
  }

  private static ImmutableMap<CxxPreprocessAndCompile, SourcePath> requireObjects(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxSourceRuleFactory.PicType pic,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      TransitiveCxxPreprocessorInputFunction transitivePreprocessorInputs)
      throws NoSuchBuildTargetException {

    boolean shouldCreatePrivateHeadersSymlinks =
        args.getXcodePrivateHeadersSymlinks()
            .orElse(cxxPlatform.getPrivateHeadersSymlinksEnabled());

    HeaderSymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            buildTarget,
            projectFilesystem,
            ruleResolver,
            cxxPlatform,
            CxxDescriptionEnhancer.parseHeaders(
                buildTarget,
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
          CxxDescriptionEnhancer.createSandboxTree(buildTarget, ruleResolver, cxxPlatform);
    }

    // Create rule to build the object files.
    ImmutableMultimap<CxxSource.Type, Arg> compilerFlags =
        ImmutableListMultimap.copyOf(
            Multimaps.transformValues(
                CxxFlags.getLanguageFlagsWithMacros(
                    args.getCompilerFlags(),
                    args.getPlatformCompilerFlags(),
                    args.getLangCompilerFlags(),
                    cxxPlatform),
                f ->
                    CxxDescriptionEnhancer.toStringWithMacrosArgs(
                        buildTarget, cellRoots, ruleResolver, cxxPlatform, f)));
    return CxxSourceRuleFactory.of(
            projectFilesystem,
            buildTarget,
            ruleResolver,
            sourcePathResolver,
            ruleFinder,
            cxxBuckConfig,
            cxxPlatform,
            getPreprocessorInputsForBuildingLibrarySources(
                ruleResolver,
                cellRoots,
                buildTarget,
                args,
                cxxPlatform,
                deps,
                transitivePreprocessorInputs,
                headerSymlinkTree,
                sandboxTree),
            compilerFlags,
            args.getPrefixHeader(),
            args.getPrecompiledHeader(),
            pic,
            sandboxTree)
        .requirePreprocessAndCompileRules(
            CxxDescriptionEnhancer.parseCxxSources(
                buildTarget, ruleResolver, ruleFinder, sourcePathResolver, cxxPlatform, args));
  }

  private static NativeLinkableInput getSharedLibraryNativeLinkTargetInput(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg arg,
      ImmutableSet<BuildRule> deps,
      ImmutableList<StringWithMacros> linkerFlags,
      ImmutableList<StringWithMacros> exportedLinkerFlags,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      TransitiveCxxPreprocessorInputFunction transitiveCxxPreprocessorInputFunction)
      throws NoSuchBuildTargetException {

    // Create rules for compiling the PIC object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        requireObjects(
            buildTarget,
            projectFilesystem,
            ruleResolver,
            pathResolver,
            ruleFinder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            CxxSourceRuleFactory.PicType.PIC,
            arg,
            deps,
            transitiveCxxPreprocessorInputFunction);

    return NativeLinkableInput.builder()
        .addAllArgs(
            RichStream.<StringWithMacros>empty()
                .concat(linkerFlags.stream())
                .concat(exportedLinkerFlags.stream())
                .map(
                    f ->
                        CxxDescriptionEnhancer.toStringWithMacrosArgs(
                            buildTarget, cellRoots, ruleResolver, cxxPlatform, f))
                .toImmutableList())
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
      BuildTarget buildTargetMaybeWithLinkerMapMode,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      ImmutableList<StringWithMacros> linkerFlags,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      Optional<String> soname,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType,
      Linker.LinkType linkType,
      Linker.LinkableDepType linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      TransitiveCxxPreprocessorInputFunction transitiveCxxPreprocessorInputFunction)
      throws NoSuchBuildTargetException {
    BuildTarget buildTargetWithoutLinkerMapMode =
        LinkerMapMode.removeLinkerMapModeFlavorInTarget(
            buildTargetMaybeWithLinkerMapMode,
            LinkerMapMode.FLAVOR_DOMAIN.getValue(buildTargetMaybeWithLinkerMapMode));

    // Create rules for compiling the PIC object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        requireObjects(
            buildTargetWithoutLinkerMapMode,
            projectFilesystem,
            ruleResolver,
            pathResolver,
            ruleFinder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            CxxSourceRuleFactory.PicType.PIC,
            args,
            deps,
            transitiveCxxPreprocessorInputFunction);

    // Setup the rules to link the shared library.
    BuildTarget sharedTarget =
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
            buildTargetMaybeWithLinkerMapMode, cxxPlatform.getFlavor(), linkType);

    String sharedLibrarySoname =
        CxxDescriptionEnhancer.getSharedLibrarySoname(
            soname, buildTargetMaybeWithLinkerMapMode, cxxPlatform);
    Path sharedLibraryPath =
        CxxDescriptionEnhancer.getSharedLibraryPath(
            projectFilesystem, sharedTarget, sharedLibrarySoname);
    ImmutableList.Builder<StringWithMacros> extraLdFlagsBuilder = ImmutableList.builder();
    extraLdFlagsBuilder.addAll(linkerFlags);
    ImmutableList<StringWithMacros> extraLdFlags = extraLdFlagsBuilder.build();

    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        projectFilesystem,
        ruleResolver,
        pathResolver,
        ruleFinder,
        sharedTarget,
        linkType,
        Optional.of(sharedLibrarySoname),
        sharedLibraryPath,
        linkableDepType,
        args.getThinLto(),
        RichStream.from(deps).filter(NativeLinkable.class).toImmutableList(),
        cxxRuntimeType,
        bundleLoader,
        blacklist,
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .addAllArgs(
                RichStream.from(extraLdFlags)
                    .map(
                        f ->
                            CxxDescriptionEnhancer.toStringWithMacrosArgs(
                                buildTargetMaybeWithLinkerMapMode,
                                cellRoots,
                                ruleResolver,
                                cxxPlatform,
                                f))
                    .toImmutableList())
            .addAllArgs(SourcePathArg.from(objects.values()))
            .setFrameworks(frameworks)
            .setLibraries(libraries)
            .build(),
        Optional.empty());
  }

  @Override
  public Class<CxxLibraryDescriptionArg> getConstructorArgType() {
    return CxxLibraryDescriptionArg.class;
  }

  /** @return a {@link HeaderSymlinkTree} for the headers of this C/C++ library. */
  private HeaderSymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    boolean shouldCreatePrivateHeaderSymlinks =
        args.getXcodePrivateHeadersSymlinks()
            .orElse(cxxPlatform.getPrivateHeadersSymlinksEnabled());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        resolver,
        cxxPlatform,
        CxxDescriptionEnhancer.parseHeaders(
            buildTarget, resolver, ruleFinder, pathResolver, Optional.of(cxxPlatform), args),
        HeaderVisibility.PRIVATE,
        shouldCreatePrivateHeaderSymlinks);
  }

  /** @return a {@link HeaderSymlinkTree} for the exported headers of this C/C++ library. */
  private HeaderSymlinkTree createExportedHeaderSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPreprocessables.HeaderMode mode,
      CxxLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        mode,
        CxxDescriptionEnhancer.parseExportedHeaders(
            buildTarget, resolver, ruleFinder, pathResolver, Optional.empty(), args),
        HeaderVisibility.PUBLIC);
  }

  /** @return a {@link HeaderSymlinkTree} for the exported headers of this C/C++ library. */
  private HeaderSymlinkTree createExportedPlatformHeaderSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    boolean shouldCreatePublicHeaderSymlinks =
        args.getXcodePublicHeadersSymlinks().orElse(cxxPlatform.getPublicHeadersSymlinksEnabled());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        resolver,
        cxxPlatform,
        CxxDescriptionEnhancer.parseExportedPlatformHeaders(
            buildTarget, resolver, ruleFinder, pathResolver, cxxPlatform, args),
        HeaderVisibility.PUBLIC,
        shouldCreatePublicHeaderSymlinks);
  }

  /**
   * Create all build rules needed to generate the static library.
   *
   * @return build rule that builds the static library version of this C/C++ library.
   */
  private static BuildRule createStaticLibraryBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      CxxSourceRuleFactory.PicType pic,
      TransitiveCxxPreprocessorInputFunction transitiveCxxPreprocessorInputFunction)
      throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);

    // Create rules for compiling the object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        requireObjects(
            buildTarget,
            projectFilesystem,
            resolver,
            sourcePathResolver,
            ruleFinder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            pic,
            args,
            deps,
            transitiveCxxPreprocessorInputFunction);

    // Write a build rule to create the archive for this C/C++ library.
    BuildTarget staticTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            buildTarget, cxxPlatform.getFlavor(), pic);

    if (objects.isEmpty()) {
      return new NoopBuildRule(staticTarget, projectFilesystem) {
        @Override
        public SortedSet<BuildRule> getBuildDeps() {
          return ImmutableSortedSet.of();
        }
      };
    }

    Path staticLibraryPath =
        CxxDescriptionEnhancer.getStaticLibraryPath(
            projectFilesystem,
            buildTarget,
            cxxPlatform.getFlavor(),
            pic,
            cxxPlatform.getStaticLibraryExtension());
    return Archive.from(
        staticTarget,
        projectFilesystem,
        ruleFinder,
        cxxPlatform,
        cxxBuckConfig.getArchiveContents(),
        staticLibraryPath,
        ImmutableList.copyOf(objects.values()),
        /* cacheable */ true);
  }

  /** @return a {@link CxxLink} rule which builds a shared library version of this C/C++ library. */
  private static CxxLink createSharedLibraryBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      Linker.LinkType linkType,
      Linker.LinkableDepType linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      TransitiveCxxPreprocessorInputFunction transitiveCxxPreprocessorInputFunction)
      throws NoSuchBuildTargetException {
    ImmutableList.Builder<StringWithMacros> linkerFlags = ImmutableList.builder();

    linkerFlags.addAll(
        CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
            args.getLinkerFlags(), args.getPlatformLinkerFlags(), cxxPlatform));

    linkerFlags.addAll(
        CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
            args.getExportedLinkerFlags(), args.getExportedPlatformLinkerFlags(), cxxPlatform));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return createSharedLibrary(
        buildTarget,
        projectFilesystem,
        resolver,
        sourcePathResolver,
        ruleFinder,
        cellRoots,
        cxxBuckConfig,
        cxxPlatform,
        args,
        deps,
        linkerFlags.build(),
        args.getFrameworks(),
        args.getLibraries(),
        args.getSoname(),
        args.getCxxRuntimeType(),
        linkType,
        linkableDepType,
        bundleLoader,
        blacklist,
        transitiveCxxPreprocessorInputFunction);
  }

  private static BuildRule createSharedLibraryInterface(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {

    Optional<SharedLibraryInterfaceFactory> factory =
        cxxPlatform.getSharedLibraryInterfaceFactory();
    if (!factory.isPresent()) {
      throw new HumanReadableException(
          "%s: C/C++ platform %s does not support shared library interfaces",
          baseTarget, cxxPlatform.getFlavor());
    }

    CxxLink sharedLibrary =
        (CxxLink)
            resolver.requireRule(
                baseTarget.withAppendedFlavors(cxxPlatform.getFlavor(), Type.SHARED.getFlavor()));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return factory
        .get()
        .createSharedInterfaceLibrary(
            baseTarget.withAppendedFlavors(
                Type.SHARED_INTERFACE.getFlavor(), cxxPlatform.getFlavor()),
            projectFilesystem,
            resolver,
            pathResolver,
            ruleFinder,
            sharedLibrary.getSourcePathToOutput());
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    return createBuildRule(
        buildTarget,
        projectFilesystem,
        params,
        resolver,
        cellRoots,
        args,
        args.getLinkStyle(),
        Optional.empty(),
        ImmutableSet.of(),
        ImmutableSortedSet.of(),
        TransitiveCxxPreprocessorInputFunction.fromLibraryRule());
  }

  public BuildRule createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams metadataRuleParams,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      final CxxLibraryDescriptionArg args,
      Optional<Linker.LinkableDepType> linkableDepType,
      final Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      ImmutableSortedSet<BuildTarget> extraDeps,
      TransitiveCxxPreprocessorInputFunction transitiveCxxPreprocessorInputFunction)
      throws NoSuchBuildTargetException {

    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, Type>> type = getLibType(buildTarget);
    Optional<CxxPlatform> platform = cxxPlatforms.getValue(buildTarget);
    CxxDeps cxxDeps = CxxDeps.builder().addDeps(args.getCxxDeps()).addDeps(extraDeps).build();

    if (buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      // XXX: This needs bundleLoader for tests..
      CxxPlatform cxxPlatform = platform.orElse(defaultCxxPlatform);
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
      SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
      ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
          requireObjects(
              buildTarget.withoutFlavors(CxxCompilationDatabase.COMPILATION_DATABASE),
              projectFilesystem,
              resolver,
              sourcePathResolver,
              ruleFinder,
              cellRoots,
              cxxBuckConfig,
              cxxPlatform,
              CxxSourceRuleFactory.PicType.PIC,
              args,
              cxxDeps.get(resolver, cxxPlatform),
              transitiveCxxPreprocessorInputFunction);
      return CxxCompilationDatabase.createCompilationDatabase(
          buildTarget, projectFilesystem, objects.keySet());
    } else if (buildTarget
        .getFlavors()
        .contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)) {
      return CxxDescriptionEnhancer.createUberCompilationDatabase(
          platform.isPresent()
              ? buildTarget
              : buildTarget.withAppendedFlavors(defaultCxxPlatform.getFlavor()),
          projectFilesystem,
          resolver);
    } else if (CxxInferEnhancer.INFER_FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors())) {
      return CxxInferEnhancer.requireInferRule(
          buildTarget,
          projectFilesystem,
          resolver,
          cellRoots,
          cxxBuckConfig,
          platform.orElse(defaultCxxPlatform),
          args,
          inferBuckConfig);
    } else if (type.isPresent() && !platform.isPresent()) {
      BuildTarget untypedBuildTarget = getUntypedBuildTarget(buildTarget);
      switch (type.get().getValue()) {
        case EXPORTED_HEADERS:
          Optional<CxxPreprocessables.HeaderMode> mode = HEADER_MODE.getValue(buildTarget);
          if (mode.isPresent()) {
            return createExportedHeaderSymlinkTreeBuildRule(
                untypedBuildTarget, projectFilesystem, resolver, mode.get(), args);
          }
          break;
          // $CASES-OMITTED$
        default:
      }

    } else if (type.isPresent() && platform.isPresent()) {
      // If we *are* building a specific type of this lib, call into the type specific
      // rule builder methods.

      BuildTarget untypedBuildTarget = getUntypedBuildTarget(buildTarget);
      switch (type.get().getValue()) {
        case HEADERS:
          return createHeaderSymlinkTreeBuildRule(
              untypedBuildTarget, projectFilesystem, resolver, platform.get(), args);
        case EXPORTED_HEADERS:
          return createExportedPlatformHeaderSymlinkTreeBuildRule(
              untypedBuildTarget, projectFilesystem, resolver, platform.get(), args);
        case SHARED:
          return createSharedLibraryBuildRule(
              untypedBuildTarget,
              projectFilesystem,
              resolver,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(resolver, platform.get()),
              Linker.LinkType.SHARED,
              linkableDepType.orElse(Linker.LinkableDepType.SHARED),
              Optional.empty(),
              blacklist,
              transitiveCxxPreprocessorInputFunction);
        case SHARED_INTERFACE:
          return createSharedLibraryInterface(
              untypedBuildTarget, projectFilesystem, resolver, platform.get());
        case MACH_O_BUNDLE:
          return createSharedLibraryBuildRule(
              untypedBuildTarget,
              projectFilesystem,
              resolver,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(resolver, platform.get()),
              Linker.LinkType.MACH_O_BUNDLE,
              linkableDepType.orElse(Linker.LinkableDepType.SHARED),
              bundleLoader,
              blacklist,
              transitiveCxxPreprocessorInputFunction);
        case STATIC:
          return createStaticLibraryBuildRule(
              untypedBuildTarget,
              projectFilesystem,
              resolver,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(resolver, platform.get()),
              CxxSourceRuleFactory.PicType.PDC,
              transitiveCxxPreprocessorInputFunction);
        case STATIC_PIC:
          return createStaticLibraryBuildRule(
              untypedBuildTarget,
              projectFilesystem,
              resolver,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(resolver, platform.get()),
              CxxSourceRuleFactory.PicType.PIC,
              transitiveCxxPreprocessorInputFunction);
        case SANDBOX_TREE:
          return CxxDescriptionEnhancer.createSandboxTreeBuildRule(
              resolver, args, platform.get(), untypedBuildTarget, projectFilesystem);
      }
      throw new RuntimeException("unhandled library build type");
    }

    boolean hasObjectsForAnyPlatform = !args.getSrcs().isEmpty();
    Predicate<CxxPlatform> hasObjects;
    if (hasObjectsForAnyPlatform) {
      hasObjects = x -> true;
    } else {
      hasObjects =
          input ->
              !args.getPlatformSrcs().getMatchingValues(input.getFlavor().toString()).isEmpty();
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    final SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return new CxxLibrary(
        buildTarget,
        projectFilesystem,
        metadataRuleParams,
        resolver,
        args.getPrivateCxxDeps(),
        args.getExportedCxxDeps(),
        Predicates.not(hasObjects),
        input -> {
          ImmutableList<StringWithMacros> flags =
              CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                  args.getExportedLinkerFlags(), args.getExportedPlatformLinkerFlags(), input);
          return RichStream.from(flags)
              .map(
                  f ->
                      CxxDescriptionEnhancer.toStringWithMacrosArgs(
                          buildTarget, cellRoots, resolver, input, f))
              .toImmutableList();
        },
        cxxPlatform -> {
          try {
            return getSharedLibraryNativeLinkTargetInput(
                buildTarget,
                projectFilesystem,
                resolver,
                pathResolver,
                ruleFinder,
                cellRoots,
                cxxBuckConfig,
                cxxPlatform,
                args,
                cxxDeps.get(resolver, cxxPlatform),
                CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                    args.getLinkerFlags(), args.getPlatformLinkerFlags(), cxxPlatform),
                CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                    args.getExportedLinkerFlags(),
                    args.getExportedPlatformLinkerFlags(),
                    cxxPlatform),
                args.getFrameworks(),
                args.getLibraries(),
                transitiveCxxPreprocessorInputFunction);
          } catch (NoSuchBuildTargetException e) {
            throw new RuntimeException(e);
          }
        },
        args.getSupportedPlatformsRegex(),
        args.getFrameworks(),
        args.getLibraries(),
        args.getForceStatic().orElse(false)
            ? NativeLinkable.Linkage.STATIC
            : args.getPreferredLinkage().orElse(NativeLinkable.Linkage.ANY),
        args.getLinkWhole().orElse(false),
        args.getSoname(),
        args.getTests(),
        args.getCanBeAsset().orElse(false),
        !buildTarget
            .getFlavors()
            .contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
        args.isReexportAllHeaderDependencies());
  }

  public static Optional<Map.Entry<Flavor, Type>> getLibType(BuildTarget buildTarget) {
    return LIBRARY_TYPE.getFlavorAndValue(buildTarget);
  }

  static BuildTarget getUntypedBuildTarget(BuildTarget buildTarget) {
    Optional<Map.Entry<Flavor, Type>> type = getLibType(buildTarget);
    if (!type.isPresent()) {
      return buildTarget;
    }
    Set<Flavor> flavors = Sets.newHashSet(buildTarget.getFlavors());
    flavors.remove(type.get().getKey());
    BuildTarget target = buildTarget.withFlavors(flavors);
    return target;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      CommonArg constructorArg,
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

  /**
   * Convenience function to query the {@link CxxHeaders} metadata of a target.
   *
   * <p>Use this function instead of constructing the BuildTarget manually.
   */
  public static Optional<CxxHeaders> queryMetadataCxxHeaders(
      BuildRuleResolver resolver, BuildTarget baseTarget, CxxPreprocessables.HeaderMode mode)
      throws NoSuchBuildTargetException {
    return resolver.requireMetadata(
        baseTarget.withAppendedFlavors(MetadataType.CXX_HEADERS.getFlavor(), mode.getFlavor()),
        CxxHeaders.class);
  }

  /**
   * Convenience function to query the {@link CxxPreprocessorInput} metadata of a target.
   *
   * <p>Use this function instead of constructing the BuildTarget manually.
   */
  public static Optional<CxxPreprocessorInput> queryMetadataCxxPreprocessorInput(
      BuildRuleResolver resolver,
      BuildTarget baseTarget,
      CxxPlatform platform,
      HeaderVisibility visibility)
      throws NoSuchBuildTargetException {
    return resolver.requireMetadata(
        baseTarget.withAppendedFlavors(
            MetadataType.CXX_PREPROCESSOR_INPUT.getFlavor(),
            platform.getFlavor(),
            visibility.getFlavor()),
        CxxPreprocessorInput.class);
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxLibraryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      final Class<U> metadataClass)
      throws NoSuchBuildTargetException {

    Map.Entry<Flavor, MetadataType> type =
        METADATA_TYPE.getFlavorAndValue(buildTarget).orElseThrow(IllegalArgumentException::new);
    BuildTarget baseTarget = buildTarget.withoutFlavors(type.getKey());

    switch (type.getValue()) {
      case CXX_HEADERS:
        {
          Optional<CxxHeaders> symlinkTree = Optional.empty();
          if (!args.getExportedHeaders().isEmpty()) {
            CxxPreprocessables.HeaderMode mode = HEADER_MODE.getRequiredValue(buildTarget);
            baseTarget = baseTarget.withoutFlavors(mode.getFlavor());
            symlinkTree =
                Optional.of(
                    CxxSymlinkTreeHeaders.from(
                        (HeaderSymlinkTree)
                            resolver.requireRule(
                                baseTarget
                                    .withoutFlavors(LIBRARY_TYPE.getFlavors())
                                    .withAppendedFlavors(
                                        Type.EXPORTED_HEADERS.getFlavor(), mode.getFlavor())),
                        CxxPreprocessables.IncludeType.LOCAL));
          }
          return symlinkTree.map(metadataClass::cast);
        }

      case CXX_PREPROCESSOR_INPUT:
        {
          Map.Entry<Flavor, CxxPlatform> platform =
              cxxPlatforms
                  .getFlavorAndValue(buildTarget)
                  .orElseThrow(IllegalArgumentException::new);
          Map.Entry<Flavor, HeaderVisibility> visibility =
              HEADER_VISIBILITY
                  .getFlavorAndValue(buildTarget)
                  .orElseThrow(IllegalArgumentException::new);
          baseTarget = baseTarget.withoutFlavors(platform.getKey(), visibility.getKey());

          CxxPreprocessorInput.Builder cxxPreprocessorInputBuilder = CxxPreprocessorInput.builder();

          // TODO(agallagher): We currently always add exported flags and frameworks to the
          // preprocessor input to mimic existing behavior, but this should likely be fixed.
          cxxPreprocessorInputBuilder.putAllPreprocessorFlags(
              Multimaps.transformValues(
                  CxxFlags.getLanguageFlagsWithMacros(
                      args.getExportedPreprocessorFlags(),
                      args.getExportedPlatformPreprocessorFlags(),
                      args.getExportedLangPreprocessorFlags(),
                      platform.getValue()),
                  f ->
                      CxxDescriptionEnhancer.toStringWithMacrosArgs(
                          buildTarget, cellRoots, resolver, platform.getValue(), f)));
          cxxPreprocessorInputBuilder.addAllFrameworks(args.getFrameworks());

          if (visibility.getValue() == HeaderVisibility.PRIVATE && !args.getHeaders().isEmpty()) {
            HeaderSymlinkTree symlinkTree =
                (HeaderSymlinkTree)
                    resolver.requireRule(
                        baseTarget.withAppendedFlavors(
                            platform.getKey(), Type.HEADERS.getFlavor()));
            cxxPreprocessorInputBuilder.addIncludes(
                CxxSymlinkTreeHeaders.from(symlinkTree, CxxPreprocessables.IncludeType.LOCAL));
          }

          if (visibility.getValue() == HeaderVisibility.PUBLIC) {

            // Add platform-agnostic headers.
            queryMetadataCxxHeaders(
                    resolver,
                    baseTarget,
                    CxxDescriptionEnhancer.getHeaderModeForPlatform(
                        resolver,
                        platform.getValue(),
                        args.getXcodePublicHeadersSymlinks()
                            .orElse(platform.getValue().getPublicHeadersSymlinksEnabled())))
                .ifPresent(cxxPreprocessorInputBuilder::addIncludes);

            // Add platform-specific headers.
            if (!args.getExportedPlatformHeaders()
                .getMatchingValues(platform.getKey().toString())
                .isEmpty()) {
              HeaderSymlinkTree symlinkTree =
                  (HeaderSymlinkTree)
                      resolver.requireRule(
                          baseTarget
                              .withoutFlavors(LIBRARY_TYPE.getFlavors())
                              .withAppendedFlavors(
                                  Type.EXPORTED_HEADERS.getFlavor(), platform.getKey()));
              cxxPreprocessorInputBuilder.addIncludes(
                  CxxSymlinkTreeHeaders.from(symlinkTree, CxxPreprocessables.IncludeType.LOCAL));
            }
          }

          CxxPreprocessorInput cxxPreprocessorInput = cxxPreprocessorInputBuilder.build();
          return Optional.of(cxxPreprocessorInput).map(metadataClass::cast);
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
      ImmutableSortedSet<Flavor> argDefaultFlavors, BuildRuleType... types) {
    Optional<Flavor> typeFlavor = LIBRARY_TYPE.getFlavor(argDefaultFlavors);
    Optional<Flavor> platformFlavor = getCxxPlatforms().getFlavor(argDefaultFlavors);

    LOG.debug("Got arg default type %s platform %s", typeFlavor, platformFlavor);

    for (BuildRuleType type : types) {
      ImmutableMap<String, Flavor> libraryDefaults =
          cxxBuckConfig.getDefaultFlavorsForRuleType(type);

      if (!typeFlavor.isPresent()) {
        typeFlavor =
            Optional.ofNullable(libraryDefaults.get(CxxBuckConfig.DEFAULT_FLAVOR_LIBRARY_TYPE));
      }

      if (!platformFlavor.isPresent()) {
        platformFlavor =
            Optional.ofNullable(libraryDefaults.get(CxxBuckConfig.DEFAULT_FLAVOR_PLATFORM));
      }
    }

    ImmutableSortedSet<Flavor> result =
        ImmutableSortedSet.of(
            // Default to static if not otherwise specified.
            typeFlavor.orElse(CxxDescriptionEnhancer.STATIC_FLAVOR),
            platformFlavor.orElse(defaultCxxPlatform.getFlavor()));

    LOG.debug("Got default flavors %s for rule types %s", result, Arrays.toString(types));
    return result;
  }

  /**
   * This is a hack to allow fine grained control over how the transitive {@code
   * CxxPreprocessorInput}s are found. Since not all {@code Description}s which use {@code
   * CxxLibraryDescription} generate a {@code CxxLibrary}, blinding attempting to require it will
   * not work.
   *
   * <p>Therefore for those other rules, we create the list from scratch.
   */
  @FunctionalInterface
  public interface TransitiveCxxPreprocessorInputFunction {
    Stream<CxxPreprocessorInput> apply(
        BuildTarget target,
        BuildRuleResolver ruleResolver,
        CxxPlatform cxxPlatform,
        ImmutableSet<BuildRule> deps,
        CxxDeps privateDeps)
        throws NoSuchBuildTargetException;

    /**
     * Retrieve the transitive CxxPreprocessorInput from the CxxLibrary rule.
     *
     * <p>This is used by CxxLibrary and AppleLibrary. Rules that do not generate a CxxLibrary rule
     * (namely AppleTest) cannot use this.
     */
    static TransitiveCxxPreprocessorInputFunction fromLibraryRule() {
      return (target, ruleResolver, cxxPlatform, ignored, privateDeps) -> {
        BuildTarget rawTarget =
            target.withoutFlavors(
                ImmutableSet.<Flavor>builder()
                    .addAll(LIBRARY_TYPE.getFlavors())
                    .add(cxxPlatform.getFlavor())
                    .build());
        BuildRule rawRule = ruleResolver.requireRule(rawTarget);
        CxxLibrary rule = (CxxLibrary) rawRule;
        ImmutableMap<BuildTarget, CxxPreprocessorInput> inputs =
            rule.getTransitiveCxxPreprocessorInput(cxxPlatform);

        ImmutableList<CxxPreprocessorDep> privateDepsForPlatform =
            RichStream.from(privateDeps.get(ruleResolver, cxxPlatform))
                .filter(CxxPreprocessorDep.class)
                .toImmutableList();
        if (privateDepsForPlatform.isEmpty()) {
          // Nothing to add.
          return inputs.values().stream();
        } else {
          Map<BuildTarget, CxxPreprocessorInput> result = new LinkedHashMap<>();
          result.putAll(inputs);
          for (CxxPreprocessorDep dep : privateDepsForPlatform) {
            result.putAll(dep.getTransitiveCxxPreprocessorInput(cxxPlatform));
          }
          return result.values().stream();
        }
      };
    }

    /**
     * Retrieve the transtiive {@link CxxPreprocessorInput} from an explicitly specified deps list.
     *
     * <p>This is used by AppleTest, which doesn't generate a CxxLibrary rule that computes this.
     */
    static TransitiveCxxPreprocessorInputFunction fromDeps() {
      return (target, ruleResolver, cxxPlatform, deps, privateDeps) -> {
        Map<BuildTarget, CxxPreprocessorInput> input = new LinkedHashMap<>();
        input.put(
            target,
            queryMetadataCxxPreprocessorInput(
                    ruleResolver, target, cxxPlatform, HeaderVisibility.PUBLIC)
                .orElseThrow(IllegalStateException::new));
        for (BuildRule rule : deps) {
          if (rule instanceof CxxPreprocessorDep) {
            input.putAll(
                ((CxxPreprocessorDep) rule).getTransitiveCxxPreprocessorInput(cxxPlatform));
          }
        }
        return input.values().stream();
      };
    }
  }

  public interface CommonArg extends LinkableCxxConstructorArg {
    @Value.Default
    default SourceList getExportedHeaders() {
      return SourceList.EMPTY;
    }

    @Value.Default
    default PatternMatchedCollection<SourceList> getExportedPlatformHeaders() {
      return PatternMatchedCollection.of();
    }

    ImmutableList<StringWithMacros> getExportedPreprocessorFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<StringWithMacros>>
        getExportedPlatformPreprocessorFlags() {
      return PatternMatchedCollection.of();
    }

    ImmutableMap<CxxSource.Type, ImmutableList<StringWithMacros>>
        getExportedLangPreprocessorFlags();

    ImmutableList<StringWithMacros> getExportedLinkerFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<StringWithMacros>>
        getExportedPlatformLinkerFlags() {
      return PatternMatchedCollection.of();
    }

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExportedDeps();

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getExportedPlatformDeps() {
      return PatternMatchedCollection.of();
    }

    Optional<Pattern> getSupportedPlatformsRegex();

    Optional<String> getSoname();

    Optional<Boolean> getForceStatic();

    Optional<Boolean> getLinkWhole();

    Optional<Boolean> getCanBeAsset();

    Optional<NativeLinkable.Linkage> getPreferredLinkage();

    Optional<Boolean> getXcodePublicHeadersSymlinks();

    Optional<Boolean> getXcodePrivateHeadersSymlinks();

    /**
     * extra_xcode_sources will add the files to the list of files to be compiled in the Xcode
     * target.
     */
    ImmutableList<SourcePath> getExtraXcodeSources();

    /**
     * extra_xcode_sources will add the files to the list of files in the project and won't add them
     * to an Xcode target.
     */
    ImmutableList<SourcePath> getExtraXcodeFiles();

    /**
     * Controls whether the headers of dependencies in "deps" is re-exported for compiling targets
     * that depend on this one.
     */
    @Value.Default
    default boolean isReexportAllHeaderDependencies() {
      return true;
    }

    /**
     * These fields are passed through to SwiftLibrary for mixed C/Swift targets; they are not used
     * otherwise.
     */
    Optional<SourcePath> getBridgingHeader();

    Optional<String> getModuleName();

    /** @return C/C++ deps which are propagated to dependents. */
    @Value.Derived
    default CxxDeps getExportedCxxDeps() {
      return CxxDeps.builder()
          .addDeps(getExportedDeps())
          .addPlatformDeps(getExportedPlatformDeps())
          .build();
    }

    /**
     * Override parent class's deps to include exported deps.
     *
     * @return the C/C++ deps this rule builds against.
     */
    @Override
    @Value.Derived
    default CxxDeps getCxxDeps() {
      return CxxDeps.concat(getPrivateCxxDeps(), getExportedCxxDeps());
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractCxxLibraryDescriptionArg extends CommonArg {}
}
