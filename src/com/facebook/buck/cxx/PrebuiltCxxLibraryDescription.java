/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.model.MacroFinder;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.rules.macros.StringExpander;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Preconditions;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class PrebuiltCxxLibraryDescription
    implements Description<PrebuiltCxxLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<
            PrebuiltCxxLibraryDescription.AbstractPrebuiltCxxLibraryDescriptionArg>,
        VersionPropagator<PrebuiltCxxLibraryDescriptionArg> {

  private static final MacroFinder MACRO_FINDER = new MacroFinder();

  enum Type implements FlavorConvertible {
    EXPORTED_HEADERS(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    SHARED_INTERFACE(InternalFlavor.of("shared-interface"));

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

  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public PrebuiltCxxLibraryDescription(
      CxxBuckConfig cxxBuckConfig, FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public Class<PrebuiltCxxLibraryDescriptionArg> getConstructorArgType() {
    return PrebuiltCxxLibraryDescriptionArg.class;
  }

  // Using the {@code MACRO_FINDER} above, return the given string with any `platform` or
  // `location` macros replaced with the name of the given platform or build rule location.
  private static String expandMacros(
      MacroHandler handler,
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver ruleResolver,
      String arg) {
    try {
      return MACRO_FINDER.replace(
          handler.getMacroReplacers(target, cellNames, ruleResolver), arg, true);
    } catch (MacroException e) {
      throw new HumanReadableException("%s: %s", target, e.getMessage());
    }
  }

  // Platform unlike most macro expanders needs access to the cxx build flavor.
  // Because of that it can't be like normal expanders. So just create a handler here.
  private static MacroHandler getMacroHandler(final Optional<CxxPlatform> cxxPlatform) {
    String flav = cxxPlatform.map(input -> input.getFlavor().toString()).orElse("");
    return new MacroHandler(
        ImmutableMap.of(
            "location", new LocationMacroExpander(),
            "platform", new StringExpander(flav)));
  }

  private static SourcePath getApplicableSourcePath(
      final BuildTarget target,
      final CellPathResolver cellRoots,
      final ProjectFilesystem filesystem,
      final BuildRuleResolver ruleResolver,
      final CxxPlatform cxxPlatform,
      Optional<String> versionSubDir,
      final String basePathString,
      final Optional<String> addedPathString) {
    ImmutableList<BuildRule> deps;
    MacroHandler handler = getMacroHandler(Optional.of(cxxPlatform));
    try {
      deps = handler.extractBuildTimeDeps(target, cellRoots, ruleResolver, basePathString);
    } catch (MacroException e) {
      deps = ImmutableList.of();
    }
    Path libDirPath =
        filesystem.getPath(expandMacros(handler, target, cellRoots, ruleResolver, basePathString));

    if (versionSubDir.isPresent()) {
      libDirPath = filesystem.getPath(versionSubDir.get()).resolve(libDirPath);
    }

    // If there are no deps then this is just referencing a path that should already be there
    // So just expand the macros and return a PathSourcePath
    if (deps.isEmpty()) {
      Path resultPath = libDirPath;
      if (addedPathString.isPresent()) {
        resultPath =
            libDirPath.resolve(
                expandMacros(handler, target, cellRoots, ruleResolver, addedPathString.get()));
      }
      resultPath = target.getBasePath().resolve(resultPath);
      return new PathSourcePath(filesystem, resultPath);
    }

    // If we get here then this is referencing the output from a build rule.
    // This always return a ExplicitBuildTargetSourcePath
    Path p = filesystem.resolve(libDirPath);
    if (addedPathString.isPresent()) {
      p = p.resolve(addedPathString.get());
    }
    p = filesystem.relativize(p);
    return new ExplicitBuildTargetSourcePath(deps.iterator().next().getBuildTarget(), p);
  }

  public static String getSoname(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      Optional<String> soname,
      Optional<String> libName) {

    String unexpanded =
        soname.orElse(
            String.format(
                "lib%s.%s",
                libName.orElse(target.getShortName()), cxxPlatform.getSharedLibraryExtension()));
    return expandMacros(
        getMacroHandler(Optional.of(cxxPlatform)), target, cellNames, ruleResolver, unexpanded);
  }

  private static SourcePath getLibraryPath(
      BuildTarget target,
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      Optional<String> versionSubDir,
      Optional<String> libDir,
      Optional<String> libName,
      String suffix) {

    String libDirString = libDir.orElse("lib");
    String fileNameString = String.format("lib%s%s", libName.orElse(target.getShortName()), suffix);

    return getApplicableSourcePath(
        target,
        cellRoots,
        filesystem,
        ruleResolver,
        cxxPlatform,
        versionSubDir,
        libDirString,
        Optional.of(fileNameString));
  }

  static SourcePath getSharedLibraryPath(
      BuildTarget target,
      CellPathResolver cellNames,
      ProjectFilesystem filesystem,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      Optional<String> versionSubDir,
      Optional<String> libDir,
      Optional<String> libName) {
    return getLibraryPath(
        target,
        cellNames,
        filesystem,
        ruleResolver,
        cxxPlatform,
        versionSubDir,
        libDir,
        libName,
        String.format(".%s", cxxPlatform.getSharedLibraryExtension()));
  }

  static SourcePath getStaticLibraryPath(
      BuildTarget target,
      CellPathResolver cellNames,
      ProjectFilesystem filesystem,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      Optional<String> versionSubDir,
      Optional<String> libDir,
      Optional<String> libName) {
    return getLibraryPath(
        target,
        cellNames,
        filesystem,
        ruleResolver,
        cxxPlatform,
        versionSubDir,
        libDir,
        libName,
        ".a");
  }

  private static SourcePath getStaticPicLibraryPath(
      BuildTarget target,
      CellPathResolver cellNames,
      final ProjectFilesystem filesystem,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      Optional<String> versionSubDir,
      Optional<String> libDir,
      Optional<String> libName) {
    return getLibraryPath(
        target,
        cellNames,
        filesystem,
        ruleResolver,
        cxxPlatform,
        versionSubDir,
        libDir,
        libName,
        "_pic.a");
  }

  /**
   * @return a {@link HeaderSymlinkTree} for the exported headers of this prebuilt C/C++ library.
   */
  private static HeaderSymlinkTree createExportedHeaderSymlinkTreeBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      PrebuiltCxxLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        params,
        resolver,
        cxxPlatform,
        parseExportedHeaders(params, resolver, cxxPlatform, args),
        HeaderVisibility.PUBLIC,
        true);
  }

  private static ImmutableMap<Path, SourcePath> parseExportedHeaders(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      PrebuiltCxxLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    ImmutableMap.Builder<String, SourcePath> headers = ImmutableMap.builder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    headers.putAll(
        CxxDescriptionEnhancer.parseOnlyHeaders(
            params.getBuildTarget(),
            ruleFinder,
            pathResolver,
            "exported_headers",
            args.getExportedHeaders()));
    headers.putAll(
        CxxDescriptionEnhancer.parseOnlyPlatformHeaders(
            params.getBuildTarget(),
            resolver,
            ruleFinder,
            pathResolver,
            cxxPlatform,
            "exported_headers",
            args.getExportedHeaders(),
            "exported_platform, headers",
            args.getExportedPlatformHeaders()));
    return CxxPreprocessables.resolveHeaderMap(
        args.getHeaderNamespace().map(Paths::get).orElse(params.getBuildTarget().getBasePath()),
        headers.build());
  }

  /** @return a {@link CxxLink} rule for a shared library version of this prebuilt C/C++ library. */
  private BuildRule createSharedLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      PrebuiltCxxLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    final SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    BuildTarget target = params.getBuildTarget();
    String soname =
        getSoname(
            target, cellRoots, ruleResolver, cxxPlatform, args.getSoname(), args.getLibName());

    Optional<String> versionSubDir =
        selectedVersions.isPresent() && args.getVersionedSubDir().isPresent()
            ? Optional.of(
                args.getVersionedSubDir().get().getOnlyMatchingValue(selectedVersions.get()))
            : Optional.empty();

    // Use the static PIC variant, if available.
    SourcePath staticLibraryPath =
        getStaticPicLibraryPath(
            target,
            cellRoots,
            params.getProjectFilesystem(),
            ruleResolver,
            cxxPlatform,
            versionSubDir,
            args.getLibDir(),
            args.getLibName());
    if (!params.getProjectFilesystem().exists(pathResolver.getAbsolutePath(staticLibraryPath))) {
      staticLibraryPath =
          getStaticLibraryPath(
              target,
              cellRoots,
              params.getProjectFilesystem(),
              ruleResolver,
              cxxPlatform,
              versionSubDir,
              args.getLibDir(),
              args.getLibName());
    }

    // Otherwise, we need to build it from the static lib.
    BuildTarget sharedTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();

    // If not, setup a single link rule to link it from the static lib.
    Path builtSharedLibraryPath =
        BuildTargets.getGenPath(params.getProjectFilesystem(), sharedTarget, "%s").resolve(soname);
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        params
            .copyAppendingExtraDeps(
                getBuildRules(
                    params.getBuildTarget(),
                    cellRoots,
                    ruleResolver,
                    Optionals.toStream(args.getLibDir()).collect(MoreCollectors.toImmutableList())))
            .copyAppendingExtraDeps(
                getBuildRules(
                    params.getBuildTarget(), cellRoots, ruleResolver, args.getIncludeDirs())),
        ruleResolver,
        pathResolver,
        ruleFinder,
        sharedTarget,
        Linker.LinkType.SHARED,
        Optional.of(soname),
        builtSharedLibraryPath,
        Linker.LinkableDepType.SHARED,
        /* thinLto */ false,
        FluentIterable.from(params.getBuildDeps()).filter(NativeLinkable.class),
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .addAllArgs(
                StringArg.from(
                    CxxFlags.getFlagsWithPlatformMacroExpansion(
                        args.getExportedLinkerFlags(),
                        args.getExportedPlatformLinkerFlags(),
                        cxxPlatform)))
            .addAllArgs(
                cxxPlatform
                    .getLd()
                    .resolve(ruleResolver)
                    .linkWhole(SourcePathArg.of(staticLibraryPath)))
            .build(),
        Optional.empty());
  }

  /**
   * Makes sure all build rules needed to produce the shared library are added to the action graph.
   *
   * @return the {@link SourcePath} representing the actual shared library.
   */
  private SourcePath requireSharedLibrary(
      BuildTarget target,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      CxxPlatform cxxPlatform,
      Optional<String> versionSubdir,
      PrebuiltCxxLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    SourcePath sharedLibraryPath =
        PrebuiltCxxLibraryDescription.getSharedLibraryPath(
            target,
            cellRoots,
            filesystem,
            resolver,
            cxxPlatform,
            versionSubdir,
            args.getLibDir(),
            args.getLibName());

    // If the shared library is prebuilt, just return a reference to it.
    // TODO(alisdair): this code misbehaves. whether the file exists should have been figured
    // out earlier during parsing/target graph creation, or it should be later when steps being
    // produced. This is preventing distributed build loading files lazily.
    if (sharedLibraryPath instanceof BuildTargetSourcePath
        || filesystem.exists(pathResolver.getAbsolutePath(sharedLibraryPath))) {
      return sharedLibraryPath;
    }

    // Otherwise, generate it's build rule.
    CxxLink sharedLibrary =
        (CxxLink)
            resolver.requireRule(
                target.withAppendedFlavors(
                    cxxPlatform.getFlavor(), CxxDescriptionEnhancer.SHARED_FLAVOR));

    return sharedLibrary.getSourcePathToOutput();
  }

  private BuildRule createSharedLibraryInterface(
      BuildTarget baseTarget,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<String> versionSubdir,
      PrebuiltCxxLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {

    if (!args.isSupportsSharedLibraryInterface()) {
      throw new HumanReadableException(
          "%s: rule does not support shared library interfaces",
          baseTarget, cxxPlatform.getFlavor());
    }

    Optional<SharedLibraryInterfaceFactory> factory =
        cxxPlatform.getSharedLibraryInterfaceFactory();
    if (!factory.isPresent()) {
      throw new HumanReadableException(
          "%s: C/C++ platform %s does not support shared library interfaces",
          baseTarget, cxxPlatform.getFlavor());
    }

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    SourcePath sharedLibrary =
        requireSharedLibrary(
            baseTarget,
            resolver,
            pathResolver,
            cellRoots,
            baseParams.getProjectFilesystem(),
            cxxPlatform,
            versionSubdir,
            args);

    return factory
        .get()
        .createSharedInterfaceLibrary(
            baseTarget.withAppendedFlavors(
                Type.SHARED_INTERFACE.getFlavor(), cxxPlatform.getFlavor()),
            baseParams,
            resolver,
            pathResolver,
            ruleFinder,
            sharedLibrary);
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver ruleResolver,
      CellPathResolver cellRoots,
      final PrebuiltCxxLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {

    // See if we're building a particular "type" of this library, and if so, extract
    // it as an enum.
    Optional<Map.Entry<Flavor, Type>> type =
        LIBRARY_TYPE.getFlavorAndValue(params.getBuildTarget());
    Optional<Map.Entry<Flavor, CxxPlatform>> platform =
        cxxPlatforms.getFlavorAndValue(params.getBuildTarget());

    Optional<ImmutableMap<BuildTarget, Version>> selectedVersions =
        targetGraph.get(params.getBuildTarget()).getSelectedVersions();
    final Optional<String> versionSubdir =
        selectedVersions.isPresent() && args.getVersionedSubDir().isPresent()
            ? Optional.of(
                args.getVersionedSubDir().get().getOnlyMatchingValue(selectedVersions.get()))
            : Optional.empty();

    // If we *are* building a specific type of this lib, call into the type specific
    // rule builder methods.  Currently, we only support building a shared lib from the
    // pre-existing static lib, which we do here.
    if (type.isPresent()) {
      Preconditions.checkState(platform.isPresent());
      BuildTarget baseTarget =
          params.getBuildTarget().withoutFlavors(type.get().getKey(), platform.get().getKey());
      if (type.get().getValue() == Type.EXPORTED_HEADERS) {
        return createExportedHeaderSymlinkTreeBuildRule(
            params, ruleResolver, platform.get().getValue(), args);
      } else if (type.get().getValue() == Type.SHARED) {
        return createSharedLibraryBuildRule(
            params, ruleResolver, cellRoots, platform.get().getValue(), selectedVersions, args);
      } else if (type.get().getValue() == Type.SHARED_INTERFACE) {
        return createSharedLibraryInterface(
            baseTarget,
            params,
            ruleResolver,
            cellRoots,
            platform.get().getValue(),
            versionSubdir,
            args);
      }
    }

    if (selectedVersions.isPresent() && args.getVersionedSubDir().isPresent()) {
      ImmutableList<String> versionSubDirs =
          args.getVersionedSubDir()
              .orElse(VersionMatchedCollection.<String>of())
              .getMatchingValues(selectedVersions.get());
      if (versionSubDirs.size() != 1) {
        throw new HumanReadableException(
            "%s: could not get a single version sub dir: %s, %s, %s",
            params.getBuildTarget(), args.getVersionedSubDir(), versionSubDirs, selectedVersions);
      }
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    final SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));
    final boolean headerOnly = args.getHeaderOnly().orElse(false);
    final boolean forceStatic = args.getForceStatic().orElse(false);
    return new PrebuiltCxxLibrary(params) {

      private final Map<Pair<Flavor, Linker.LinkableDepType>, NativeLinkableInput>
          nativeLinkableCache = new HashMap<>();

      private final LoadingCache<
              CxxPreprocessables.CxxPreprocessorInputCacheKey,
              ImmutableMap<BuildTarget, CxxPreprocessorInput>>
          transitiveCxxPreprocessorInputCache =
              CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

      private boolean hasHeaders(CxxPlatform cxxPlatform) {
        if (!args.getExportedHeaders().isEmpty()) {
          return true;
        }
        for (SourceList sourceList :
            args.getExportedPlatformHeaders()
                .getMatchingValues(cxxPlatform.getFlavor().toString())) {
          if (!sourceList.isEmpty()) {
            return true;
          }
        }
        return false;
      }

      private ImmutableListMultimap<CxxSource.Type, String> getExportedPreprocessorFlags(
          CxxPlatform cxxPlatform) {
        return CxxFlags.getLanguageFlags(
            args.getExportedPreprocessorFlags(),
            args.getExportedPlatformPreprocessorFlags(),
            args.getExportedLangPreprocessorFlags(),
            cxxPlatform);
      }

      @Override
      public ImmutableList<String> getExportedLinkerFlags(CxxPlatform cxxPlatform) {
        return CxxFlags.getFlagsWithPlatformMacroExpansion(
            args.getExportedLinkerFlags(), args.getExportedPlatformLinkerFlags(), cxxPlatform);
      }

      private String getSoname(CxxPlatform cxxPlatform) {
        return PrebuiltCxxLibraryDescription.getSoname(
            getBuildTarget(),
            cellRoots,
            ruleResolver,
            cxxPlatform,
            args.getSoname(),
            args.getLibName());
      }

      private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
        return !args.getSupportedPlatformsRegex().isPresent()
            || args.getSupportedPlatformsRegex()
                .get()
                .matcher(cxxPlatform.getFlavor().toString())
                .find();
      }

      /**
       * Makes sure all build rules needed to produce the shared library are added to the action
       * graph.
       *
       * @return the {@link SourcePath} representing the actual shared library.
       */
      private SourcePath requireSharedLibrary(CxxPlatform cxxPlatform, boolean link)
          throws NoSuchBuildTargetException {
        if (link
            && args.isSupportsSharedLibraryInterface()
            && cxxPlatform.getSharedLibraryInterfaceFactory().isPresent()) {
          BuildTarget target =
              params
                  .getBuildTarget()
                  .withAppendedFlavors(cxxPlatform.getFlavor(), Type.SHARED_INTERFACE.getFlavor());
          BuildRule rule = ruleResolver.requireRule(target);
          return Preconditions.checkNotNull(rule.getSourcePathToOutput());
        }
        return PrebuiltCxxLibraryDescription.this.requireSharedLibrary(
            params.getBuildTarget(),
            ruleResolver,
            pathResolver,
            cellRoots,
            params.getProjectFilesystem(),
            cxxPlatform,
            versionSubdir,
            args);
      }

      /**
       * @return the {@link Optional} containing a {@link SourcePath} representing the actual static
       *     PIC library.
       */
      private Optional<SourcePath> getStaticPicLibrary(CxxPlatform cxxPlatform) {
        SourcePath staticPicLibraryPath =
            PrebuiltCxxLibraryDescription.getStaticPicLibraryPath(
                getBuildTarget(),
                cellRoots,
                params.getProjectFilesystem(),
                ruleResolver,
                cxxPlatform,
                versionSubdir,
                args.getLibDir(),
                args.getLibName());
        if (params
            .getProjectFilesystem()
            .exists(pathResolver.getAbsolutePath(staticPicLibraryPath))) {
          return Optional.of(staticPicLibraryPath);
        }

        // If a specific static-pic variant isn't available, then just use the static variant.
        SourcePath staticLibraryPath =
            PrebuiltCxxLibraryDescription.getStaticLibraryPath(
                getBuildTarget(),
                cellRoots,
                getProjectFilesystem(),
                ruleResolver,
                cxxPlatform,
                versionSubdir,
                args.getLibDir(),
                args.getLibName());
        if (params.getProjectFilesystem().exists(pathResolver.getAbsolutePath(staticLibraryPath))) {
          return Optional.of(staticLibraryPath);
        }

        return Optional.empty();
      }

      @Override
      public Iterable<? extends CxxPreprocessorDep> getCxxPreprocessorDeps(
          CxxPlatform cxxPlatform) {
        if (!isPlatformSupported(cxxPlatform)) {
          return ImmutableList.of();
        }
        return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
      }

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput(
          final CxxPlatform cxxPlatform, HeaderVisibility headerVisibility)
          throws NoSuchBuildTargetException {
        CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();

        switch (headerVisibility) {
          case PUBLIC:
            if (hasHeaders(cxxPlatform)) {
              CxxPreprocessables.addHeaderSymlinkTree(
                  builder,
                  getBuildTarget(),
                  ruleResolver,
                  cxxPlatform,
                  headerVisibility,
                  CxxPreprocessables.IncludeType.SYSTEM);
            }
            builder.putAllPreprocessorFlags(
                Preconditions.checkNotNull(getExportedPreprocessorFlags(cxxPlatform)));
            builder.addAllFrameworks(args.getFrameworks());
            final Iterable<SourcePath> includePaths =
                args.getIncludeDirs()
                    .stream()
                    .map(
                        input ->
                            PrebuiltCxxLibraryDescription.getApplicableSourcePath(
                                params.getBuildTarget(),
                                cellRoots,
                                params.getProjectFilesystem(),
                                ruleResolver,
                                cxxPlatform,
                                versionSubdir,
                                input,
                                Optional.empty()))
                    .collect(MoreCollectors.toImmutableList());
            for (SourcePath includePath : includePaths) {
              builder.addIncludes(
                  CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, includePath));
            }
            return builder.build();
          case PRIVATE:
            return builder.build();
        }

        // We explicitly don't put this in a default statement because we
        // want the compiler to warn if someone modifies the HeaderVisibility enum.
        throw new RuntimeException("Invalid header visibility: " + headerVisibility);
      }

      @Override
      public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
          CxxPlatform cxxPlatform, HeaderVisibility headerVisibility)
          throws NoSuchBuildTargetException {
        return transitiveCxxPreprocessorInputCache.getUnchecked(
            ImmutableCxxPreprocessorInputCacheKey.of(cxxPlatform, headerVisibility));
      }

      @Override
      public Iterable<NativeLinkable> getNativeLinkableDeps() {
        return getDeclaredDeps()
            .stream()
            .filter(r -> r instanceof NativeLinkable)
            .map(r -> (NativeLinkable) r)
            .collect(MoreCollectors.toImmutableList());
      }

      @Override
      public Iterable<NativeLinkable> getNativeLinkableDepsForPlatform(CxxPlatform cxxPlatform) {
        if (!isPlatformSupported(cxxPlatform)) {
          return ImmutableList.of();
        }
        return getNativeLinkableDeps();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps() {
        return args.getExportedDeps()
            .stream()
            .map(ruleResolver::getRule)
            .filter(r -> r instanceof NativeLinkable)
            .map(r -> (NativeLinkable) r)
            .collect(MoreCollectors.toImmutableList());
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableExportedDepsForPlatform(
          CxxPlatform cxxPlatform) {
        if (!isPlatformSupported(cxxPlatform)) {
          return ImmutableList.of();
        }
        return getNativeLinkableExportedDeps();
      }

      private NativeLinkableInput getNativeLinkableInputUncached(
          CxxPlatform cxxPlatform, Linker.LinkableDepType type) throws NoSuchBuildTargetException {
        if (!isPlatformSupported(cxxPlatform)) {
          return NativeLinkableInput.of();
        }

        // Build the library path and linker arguments that we pass through the
        // {@link NativeLinkable} interface for linking.
        ImmutableList.Builder<com.facebook.buck.rules.args.Arg> linkerArgsBuilder =
            ImmutableList.builder();
        linkerArgsBuilder.addAll(
            StringArg.from(Preconditions.checkNotNull(getExportedLinkerFlags(cxxPlatform))));

        if (!headerOnly) {
          if (type == Linker.LinkableDepType.SHARED) {
            Preconditions.checkState(getPreferredLinkage(cxxPlatform) != Linkage.STATIC);
            final SourcePath sharedLibrary = requireSharedLibrary(cxxPlatform, true);
            if (args.getLinkWithoutSoname()) {
              if (!(sharedLibrary instanceof PathSourcePath)) {
                throw new HumanReadableException(
                    "%s: can only link prebuilt DSOs without sonames", getBuildTarget());
              }
              linkerArgsBuilder.add(new RelativeLinkArg((PathSourcePath) sharedLibrary));
            } else {
              linkerArgsBuilder.add(SourcePathArg.of(requireSharedLibrary(cxxPlatform, true)));
            }
          } else {
            Preconditions.checkState(getPreferredLinkage(cxxPlatform) != Linkage.SHARED);
            SourcePath staticLibraryPath =
                type == Linker.LinkableDepType.STATIC_PIC
                    ? getStaticPicLibrary(cxxPlatform).get()
                    : PrebuiltCxxLibraryDescription.getStaticLibraryPath(
                        getBuildTarget(),
                        cellRoots,
                        params.getProjectFilesystem(),
                        ruleResolver,
                        cxxPlatform,
                        versionSubdir,
                        args.getLibDir(),
                        args.getLibName());
            SourcePathArg staticLibrary = SourcePathArg.of(staticLibraryPath);
            if (args.isLinkWhole()) {
              Linker linker = cxxPlatform.getLd().resolve(ruleResolver);
              linkerArgsBuilder.addAll(linker.linkWhole(staticLibrary));
            } else {
              linkerArgsBuilder.add(FileListableLinkerInputArg.withSourcePathArg(staticLibrary));
            }
          }
        }
        final ImmutableList<com.facebook.buck.rules.args.Arg> linkerArgs =
            linkerArgsBuilder.build();

        return NativeLinkableInput.of(linkerArgs, args.getFrameworks(), args.getLibraries());
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform, Linker.LinkableDepType type) throws NoSuchBuildTargetException {
        Pair<Flavor, Linker.LinkableDepType> key = new Pair<>(cxxPlatform.getFlavor(), type);
        NativeLinkableInput input = nativeLinkableCache.get(key);
        if (input == null) {
          input = getNativeLinkableInputUncached(cxxPlatform, type);
          nativeLinkableCache.put(key, input);
        }
        return input;
      }

      @Override
      public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
        if (headerOnly) {
          return Linkage.ANY;
        } else if (forceStatic) {
          return Linkage.STATIC;
        } else if (args.getPreferredLinkage().orElse(Linkage.ANY) != Linkage.ANY) {
          return args.getPreferredLinkage().get();
        } else if (args.isProvided() || !getStaticPicLibrary(cxxPlatform).isPresent()) {
          return Linkage.SHARED;
        } else {
          return Linkage.ANY;
        }
      }

      @Override
      public Iterable<AndroidPackageable> getRequiredPackageables() {
        return AndroidPackageableCollector.getPackageableRules(params.getBuildDeps());
      }

      @Override
      public void addToCollector(AndroidPackageableCollector collector) {
        if (args.getCanBeAsset()) {
          collector.addNativeLinkableAsset(this);
        } else {
          collector.addNativeLinkable(this);
        }
      }

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform)
          throws NoSuchBuildTargetException {
        if (!isPlatformSupported(cxxPlatform)) {
          return ImmutableMap.of();
        }

        String resolvedSoname = getSoname(cxxPlatform);
        ImmutableMap.Builder<String, SourcePath> solibs = ImmutableMap.builder();
        if (!headerOnly && !args.isProvided()) {
          SourcePath sharedLibrary = requireSharedLibrary(cxxPlatform, false);
          solibs.put(resolvedSoname, sharedLibrary);
        }
        return solibs.build();
      }

      @Override
      public Optional<NativeLinkTarget> getNativeLinkTarget(CxxPlatform cxxPlatform) {
        if (getPreferredLinkage(cxxPlatform) == Linkage.SHARED) {
          return Optional.empty();
        }
        return Optional.of(
            new NativeLinkTarget() {
              @Override
              public BuildTarget getBuildTarget() {
                return params.getBuildTarget();
              }

              @Override
              public NativeLinkTargetMode getNativeLinkTargetMode(CxxPlatform cxxPlatform) {
                return NativeLinkTargetMode.library(getSoname(cxxPlatform));
              }

              @Override
              public Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(
                  CxxPlatform cxxPlatform) {
                return Iterables.concat(
                    getNativeLinkableDepsForPlatform(cxxPlatform),
                    getNativeLinkableExportedDepsForPlatform(cxxPlatform));
              }

              @Override
              public NativeLinkableInput getNativeLinkTargetInput(CxxPlatform cxxPlatform)
                  throws NoSuchBuildTargetException {
                return NativeLinkableInput.builder()
                    .addAllArgs(StringArg.from(getExportedLinkerFlags(cxxPlatform)))
                    .addAllArgs(
                        cxxPlatform
                            .getLd()
                            .resolve(ruleResolver)
                            .linkWhole(SourcePathArg.of(getStaticPicLibrary(cxxPlatform).get())))
                    .build();
              }

              @Override
              public Optional<Path> getNativeLinkTargetOutputPath(CxxPlatform cxxPlatform) {
                return Optional.empty();
              }
            });
      }
    };
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractPrebuiltCxxLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (constructorArg.getLibDir().isPresent()) {
      addDepsFromParam(
          buildTarget,
          cellRoots,
          constructorArg.getLibDir().get(),
          extraDepsBuilder,
          targetGraphOnlyDepsBuilder);
    }
    for (String include : constructorArg.getIncludeDirs()) {
      addDepsFromParam(
          buildTarget, cellRoots, include, extraDepsBuilder, targetGraphOnlyDepsBuilder);
    }
  }

  private ImmutableList<BuildRule> getBuildRules(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver ruleResolver,
      Iterable<String> paramValues) {
    ImmutableList.Builder<BuildRule> builder = ImmutableList.builder();
    MacroHandler macroHandler = getMacroHandler(Optional.empty());
    for (String p : paramValues) {
      try {

        builder.addAll(macroHandler.extractBuildTimeDeps(target, cellNames, ruleResolver, p));
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s : %s in \"%s\"", target, e.getMessage(), p);
      }
    }
    return builder.build();
  }

  private void addDepsFromParam(
      BuildTarget target,
      CellPathResolver cellNames,
      String paramValue,
      ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    try {
      // doesn't matter that the platform expander doesn't do anything.
      MacroHandler macroHandler = getMacroHandler(Optional.empty());
      // Then get the parse time deps.
      macroHandler.extractParseTimeDeps(
          target, cellNames, paramValue, buildDepsBuilder, targetGraphOnlyDepsBuilder);
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s : %s in \"%s\"", target, e.getMessage(), paramValue);
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPrebuiltCxxLibraryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    ImmutableList<String> getIncludeDirs();

    Optional<String> getLibName();

    Optional<String> getLibDir();

    Optional<Boolean> getHeaderOnly();

    @Value.Default
    default SourceList getExportedHeaders() {
      return SourceList.EMPTY;
    }

    @Value.Default
    default PatternMatchedCollection<SourceList> getExportedPlatformHeaders() {
      return PatternMatchedCollection.of();
    }

    Optional<String> getHeaderNamespace();

    @Value.Default
    default boolean isProvided() {
      return false;
    }

    @Value.Default
    default boolean isLinkWhole() {
      return false;
    }

    Optional<Boolean> getForceStatic();

    Optional<NativeLinkable.Linkage> getPreferredLinkage();

    ImmutableList<String> getExportedPreprocessorFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<String>> getExportedPlatformPreprocessorFlags() {
      return PatternMatchedCollection.of();
    }

    ImmutableMap<CxxSource.Type, ImmutableList<String>> getExportedLangPreprocessorFlags();

    ImmutableList<String> getExportedLinkerFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<String>> getExportedPlatformLinkerFlags() {
      return PatternMatchedCollection.of();
    }

    Optional<String> getSoname();

    @Value.Default
    default boolean getLinkWithoutSoname() {
      return false;
    }

    @Value.Default
    default boolean getCanBeAsset() {
      return false;
    }

    @Value.NaturalOrder
    ImmutableSortedSet<FrameworkPath> getFrameworks();

    @Value.NaturalOrder
    ImmutableSortedSet<FrameworkPath> getLibraries();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExportedDeps();

    Optional<Pattern> getSupportedPlatformsRegex();

    Optional<VersionMatchedCollection<String>> getVersionedSubDir();

    @Value.Default
    default boolean isSupportsSharedLibraryInterface() {
      return false;
    }
  }
}
