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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.model.MacroFinder;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.rules.macros.StringExpander;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public class PrebuiltCxxLibraryDescription implements
    Description<PrebuiltCxxLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<PrebuiltCxxLibraryDescription.Arg> {

  private static final Logger LOG = Logger.get(PrebuiltCxxLibraryDescription.class);

  private static final MacroFinder MACRO_FINDER = new MacroFinder();

  private enum Type implements FlavorConvertible {
    EXPORTED_HEADERS(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
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

  public static final BuildRuleType TYPE = BuildRuleType.of("prebuilt_cxx_library");

  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public PrebuiltCxxLibraryDescription(
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
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
          handler.getMacroReplacers(target, cellNames, ruleResolver),
          arg);
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


  public static SourcePath getApplicableSourcePath(
      final BuildTarget target,
      final CellPathResolver cellRoots,
      final ProjectFilesystem filesystem,
      final BuildRuleResolver ruleResolver,
      final CxxPlatform cxxPlatform,
      final String basePathString,
      final Optional<String> addedPathString) {
    ImmutableList<BuildRule> deps;
    MacroHandler handler = getMacroHandler(Optional.of(cxxPlatform));
    try {
      deps = handler.extractBuildTimeDeps(target, cellRoots, ruleResolver, basePathString);
    } catch (MacroException e) {
      deps = ImmutableList.of();
    }
    final Path libDirPath = Paths.get(expandMacros(
        handler,
        target,
        cellRoots,
        ruleResolver,
        basePathString));

    // If there are no deps then this is just referencing a path that should already be there
    // So just expand the macros and return a PathSourcePath
    if (deps.isEmpty()) {
      Path resultPath = libDirPath;
      if (addedPathString.isPresent()) {
        resultPath =
            libDirPath.resolve(expandMacros(
                handler,
                target,
                cellRoots,
                ruleResolver,
                addedPathString.get()));
      }
      resultPath = target.getBasePath().resolve(resultPath);
      return new PathSourcePath(filesystem, resultPath);
    }

    // If we get here then this is referencing the output from a build rule.
    // This always return a BuildTargetSourcePath
    Path p = filesystem.resolve(libDirPath);
    if (addedPathString.isPresent()) {
      p = p.resolve(addedPathString.get());
    }
    p = filesystem.getRelativizer().apply(p);
    return new BuildTargetSourcePath(deps.iterator().next().getBuildTarget(), p);
  }

  public static String getSoname(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      Optional<String> soname,
      Optional<String> libName) {

    String unexpanded = soname.orElse(String.format(
        "lib%s.%s",
        libName.orElse(target.getShortName()),
        cxxPlatform.getSharedLibraryExtension()));
    return expandMacros(
        getMacroHandler(Optional.of(cxxPlatform)),
        target,
        cellNames,
        ruleResolver,
        unexpanded);
  }

  private static SourcePath getLibraryPath(
      final BuildTarget target,
      final CellPathResolver cellRoots,
      final ProjectFilesystem filesystem,
      final BuildRuleResolver ruleResolver,
      final CxxPlatform cxxPlatform,
      final Optional<String> libDir,
      final Optional<String> libName,
      String suffix) {

    final String libDirString = libDir.orElse("lib");
    final String fileNameString = String.format(
        "lib%s%s",
        libName.orElse(target.getShortName()),
        suffix);

    return getApplicableSourcePath(
        target,
        cellRoots,
        filesystem,
        ruleResolver,
        cxxPlatform,
        libDirString,
        Optional.of(fileNameString)
    );
  }

  public static SourcePath getSharedLibraryPath(
      BuildTarget target,
      CellPathResolver cellNames,
      final ProjectFilesystem filesystem,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      Optional<String> libDir,
      Optional<String> libName) {
    return getLibraryPath(
        target,
        cellNames,
        filesystem,
        ruleResolver,
        cxxPlatform,
        libDir,
        libName,
        String.format(".%s", cxxPlatform.getSharedLibraryExtension()));
  }

  public static SourcePath getStaticLibraryPath(
      BuildTarget target,
      CellPathResolver cellNames,
      final ProjectFilesystem filesystem,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      Optional<String> libDir,
      Optional<String> libName) {
    return getLibraryPath(
        target,
        cellNames,
        filesystem,
        ruleResolver,
        cxxPlatform,
        libDir,
        libName,
        ".a");
  }

  public static SourcePath getStaticPicLibraryPath(
      BuildTarget target,
      CellPathResolver cellNames,
      final ProjectFilesystem filesystem,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      Optional<String> libDir,
      Optional<String> libName) {
    return getLibraryPath(
        target,
        cellNames,
        filesystem,
        ruleResolver,
        cxxPlatform,
        libDir,
        libName,
        "_pic.a");
  }

  /**
   * @return a {@link HeaderSymlinkTree} for the exported headers of this prebuilt C/C++ library.
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
        parseExportedHeaders(params, resolver, cxxPlatform, args),
        HeaderVisibility.PUBLIC);
  }

  private static <A extends Arg> ImmutableMap<Path, SourcePath> parseExportedHeaders(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args) {
    ImmutableMap.Builder<String, SourcePath> headers = ImmutableMap.builder();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    CxxDescriptionEnhancer.putAllHeaders(
        args.exportedHeaders,
        headers,
        pathResolver,
        "exported_headers",
        params.getBuildTarget());
    for (SourceList sourceList :
        args.exportedPlatformHeaders.getMatchingValues(cxxPlatform.getFlavor().toString())) {
      CxxDescriptionEnhancer.putAllHeaders(
          sourceList,
          headers,
          pathResolver,
          "exported_platform_headers",
          params.getBuildTarget());
    }
    return CxxPreprocessables.resolveHeaderMap(
        args.headerNamespace.map(Paths::get).orElse(params.getBuildTarget().getBasePath()),
        headers.build());
  }

  /**
   * @return a {@link CxxLink} rule for a shared library version of this prebuilt C/C++ library.
   */
  private <A extends Arg> BuildRule createSharedLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      A args) throws NoSuchBuildTargetException {

    final SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);

    BuildTarget target = params.getBuildTarget();
    String soname = getSoname(
        target,
        params.getCellRoots(),
        ruleResolver,
        cxxPlatform,
        args.soname,
        args.libName);


    // Use the static PIC variant, if available.
    SourcePath staticLibraryPath =
        getStaticPicLibraryPath(
            target,
            params.getCellRoots(),
            params.getProjectFilesystem(),
            ruleResolver,
            cxxPlatform,
            args.libDir,
            args.libName);
    if (!params.getProjectFilesystem().exists(pathResolver.getAbsolutePath(staticLibraryPath))) {
      staticLibraryPath = getStaticLibraryPath(
          target,
          params.getCellRoots(),
          params.getProjectFilesystem(),
          ruleResolver,
          cxxPlatform,
          args.libDir,
          args.libName);
    }

    // Otherwise, we need to build it from the static lib.
    BuildTarget sharedTarget = BuildTarget
        .builder(params.getBuildTarget())
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();

    // If not, setup a single link rule to link it from the static lib.
    Path builtSharedLibraryPath =
        BuildTargets.getGenPath(params.getProjectFilesystem(), sharedTarget, "%s").resolve(soname);
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        params.appendExtraDeps(
            getBuildRules(
                params.getBuildTarget(),
                params.getCellRoots(),
                ruleResolver,
                OptionalCompat.presentInstances(ImmutableList.of(args.libDir))))
            .appendExtraDeps(
                getBuildRules(
                    params.getBuildTarget(),
                    params.getCellRoots(),
                    ruleResolver,
                    args.includeDirs)),
        ruleResolver,
        pathResolver,
        sharedTarget,
        Linker.LinkType.SHARED,
        Optional.of(soname),
        builtSharedLibraryPath,
        Linker.LinkableDepType.SHARED,
        FluentIterable.from(params.getDeps())
            .filter(NativeLinkable.class),
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .addAllArgs(
                StringArg.from(
                    CxxFlags.getFlags(
                        args.exportedLinkerFlags,
                        args.exportedPlatformLinkerFlags,
                        cxxPlatform)))
            .addAllArgs(
                cxxPlatform.getLd().resolve(ruleResolver).linkWhole(
                    new SourcePathArg(
                        pathResolver,
                        staticLibraryPath)))
            .build());
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver ruleResolver,
      final A args) throws NoSuchBuildTargetException {
    if (args.includeDirs.size() > 0) {
      LOG.warn(
          "Build target %s uses `include_dirs` which is deprecated. Use `exported_headers` instead",
          params.getBuildTarget().toString());
    }

    // See if we're building a particular "type" of this library, and if so, extract
    // it as an enum.
    Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(
        params.getBuildTarget());
    Optional<Map.Entry<Flavor, CxxPlatform>> platform = cxxPlatforms.getFlavorAndValue(
        params.getBuildTarget());

    // If we *are* building a specific type of this lib, call into the type specific
    // rule builder methods.  Currently, we only support building a shared lib from the
    // pre-existing static lib, which we do here.
    if (type.isPresent()) {
      Preconditions.checkState(platform.isPresent());
      if (type.get().getValue() == Type.EXPORTED_HEADERS) {
        return createExportedHeaderSymlinkTreeBuildRule(
            params,
            ruleResolver,
            platform.get().getValue(),
            args);
      } else if (type.get().getValue() == Type.SHARED) {
        return createSharedLibraryBuildRule(
            params,
            ruleResolver,
            platform.get().getValue(),
            args);
      }
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    final SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    return new PrebuiltCxxLibrary(
        params.appendExtraDeps(
            getBuildRules(
                params.getBuildTarget(),
                params.getCellRoots(),
                ruleResolver,
                OptionalCompat.presentInstances(ImmutableList.of(args.libDir))))
        .appendExtraDeps(
            getBuildRules(
                params.getBuildTarget(),
                params.getCellRoots(),
                ruleResolver,
                args.includeDirs)),
        ruleResolver,
        pathResolver,
        FluentIterable.from(args.exportedDeps)
            .transform(ruleResolver.getRuleFunction())
            .filter(NativeLinkable.class),
        args.includeDirs,
        args.libDir,
        args.libName,
        input -> CxxFlags.getLanguageFlags(
            args.exportedPreprocessorFlags,
            args.exportedPlatformPreprocessorFlags,
            args.exportedLangPreprocessorFlags,
            input),
        input -> CxxFlags.getFlags(
            args.exportedLinkerFlags,
            args.exportedPlatformLinkerFlags,
            input),
        args.soname,
        args.linkWithoutSoname.orElse(false),
        args.frameworks,
        args.libraries,
        args.forceStatic.orElse(false),
        args.headerOnly.orElse(false),
        args.linkWhole.orElse(false),
        args.provided.orElse(false),
        cxxPlatform -> {
          if (!args.exportedHeaders.isEmpty()) {
            return true;
          }
          for (SourceList sourceList :
               args.exportedPlatformHeaders.getMatchingValues(cxxPlatform.getFlavor().toString())) {
            if (!sourceList.isEmpty()) {
              return true;
            }
          }
          return false;
        },
        args.supportedPlatformsRegex,
        args.canBeAsset.orElse(false));
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      PrebuiltCxxLibraryDescription.Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();

    if (constructorArg.libDir.isPresent()) {
      addDepsFromParam(buildTarget, cellRoots, constructorArg.libDir.get(), targets);
    }
    for (String include : constructorArg.includeDirs) {
      addDepsFromParam(buildTarget, cellRoots, include, targets);
    }
    return targets.build();
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
      ImmutableSet.Builder<BuildTarget> targets) {
    try {
      // doesn't matter that the platform expander doesn't do anything.
      MacroHandler macroHandler = getMacroHandler(Optional.empty());
      // Then get the parse time deps.
      targets.addAll(macroHandler.extractParseTimeDeps(target, cellNames, paramValue));
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s : %s in \"%s\"", target, e.getMessage(), paramValue);
    }
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableList<String> includeDirs = ImmutableList.of();
    public Optional<String> libName;
    public Optional<String> libDir;
    public Optional<Boolean> headerOnly;
    public SourceList exportedHeaders = SourceList.EMPTY;
    public PatternMatchedCollection<SourceList> exportedPlatformHeaders =
        PatternMatchedCollection.of();
    public Optional<String> headerNamespace;
    public Optional<Boolean> provided;
    public Optional<Boolean> linkWhole;
    public Optional<Boolean> forceStatic;
    public ImmutableList<String> exportedPreprocessorFlags = ImmutableList.of();
    public PatternMatchedCollection<ImmutableList<String>>
        exportedPlatformPreprocessorFlags = PatternMatchedCollection.of();
    public ImmutableMap<CxxSource.Type, ImmutableList<String>>
        exportedLangPreprocessorFlags = ImmutableMap.of();
    public ImmutableList<String> exportedLinkerFlags = ImmutableList.of();
    public PatternMatchedCollection<ImmutableList<String>> exportedPlatformLinkerFlags =
        PatternMatchedCollection.of();
    public Optional<String> soname;
    public Optional<Boolean> linkWithoutSoname;
    public Optional<Boolean> canBeAsset;
    public ImmutableSortedSet<FrameworkPath> frameworks = ImmutableSortedSet.of();
    public ImmutableSortedSet<FrameworkPath> libraries = ImmutableSortedSet.of();
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public ImmutableSortedSet<BuildTarget> exportedDeps = ImmutableSortedSet.of();
    public Optional<Pattern> supportedPlatformsRegex;
  }

}
