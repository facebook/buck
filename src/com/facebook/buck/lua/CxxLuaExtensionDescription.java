/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.lua;

import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxConstructorArg;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxFlags;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.HeaderSymlinkTree;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.NativeLinkTargetMode;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class CxxLuaExtensionDescription
    implements Description<CxxLuaExtensionDescriptionArg>,
        ImplicitDepsInferringDescription<
            CxxLuaExtensionDescription.AbstractCxxLuaExtensionDescriptionArg>,
        VersionPropagator<CxxLuaExtensionDescriptionArg> {

  private final LuaConfig luaConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public CxxLuaExtensionDescription(
      LuaConfig luaConfig, CxxBuckConfig cxxBuckConfig, FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.luaConfig = luaConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
  }

  private String getExtensionName(BuildTarget target, CxxPlatform cxxPlatform) {
    return String.format("%s.%s", target.getShortName(), cxxPlatform.getSharedLibraryExtension());
  }

  private BuildTarget getExtensionTarget(BuildTarget target, Flavor platform) {
    return BuildTarget.builder(target).addFlavors(platform).build();
  }

  private Path getExtensionPath(
      ProjectFilesystem filesystem, BuildTarget target, CxxPlatform cxxPlatform) {
    return BuildTargets.getGenPath(
            filesystem, getExtensionTarget(target, cxxPlatform.getFlavor()), "%s")
        .resolve(getExtensionName(target, cxxPlatform));
  }

  private ImmutableList<com.facebook.buck.rules.args.Arg> getExtensionArgs(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      CxxLuaExtensionDescriptionArg args)
      throws NoSuchBuildTargetException {

    // Extract all C/C++ sources from the constructor arg.
    ImmutableMap<String, CxxSource> srcs =
        CxxDescriptionEnhancer.parseCxxSources(
            params.getBuildTarget(), ruleResolver, ruleFinder, pathResolver, cxxPlatform, args);
    ImmutableMap<Path, SourcePath> headers =
        CxxDescriptionEnhancer.parseHeaders(
            params.getBuildTarget(),
            ruleResolver,
            ruleFinder,
            pathResolver,
            Optional.of(cxxPlatform),
            args);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    HeaderSymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params, ruleResolver, cxxPlatform, headers, HeaderVisibility.PRIVATE, true);
    Optional<SymlinkTree> sandboxTree = Optional.empty();
    if (cxxBuckConfig.sandboxSources()) {
      sandboxTree = CxxDescriptionEnhancer.createSandboxTree(params, ruleResolver, cxxPlatform);
    }
    ImmutableSet<BuildRule> deps = args.getCxxDeps().get(ruleResolver, cxxPlatform);
    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput =
        ImmutableList.<CxxPreprocessorInput>builder()
            .add(
                luaConfig
                    .getLuaCxxLibrary(ruleResolver)
                    .getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PUBLIC))
            .addAll(
                CxxDescriptionEnhancer.collectCxxPreprocessorInput(
                    params,
                    cxxPlatform,
                    deps,
                    CxxFlags.getLanguageFlags(
                        args.getPreprocessorFlags(),
                        args.getPlatformPreprocessorFlags(),
                        args.getLangPreprocessorFlags(),
                        cxxPlatform),
                    ImmutableList.of(headerSymlinkTree),
                    ImmutableSet.of(),
                    CxxPreprocessables.getTransitiveCxxPreprocessorInput(cxxPlatform, deps),
                    args.getIncludeDirs(),
                    sandboxTree))
            .build();

    // Generate rule to build the object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> picObjects =
        CxxSourceRuleFactory.requirePreprocessAndCompileRules(
            params,
            ruleResolver,
            pathResolver,
            ruleFinder,
            cxxBuckConfig,
            cxxPlatform,
            cxxPreprocessorInput,
            CxxFlags.getLanguageFlags(
                args.getCompilerFlags(),
                args.getPlatformCompilerFlags(),
                args.getLangCompilerFlags(),
                cxxPlatform),
            args.getPrefixHeader(),
            args.getPrecompiledHeader(),
            srcs,
            CxxSourceRuleFactory.PicType.PIC,
            sandboxTree);

    ImmutableList.Builder<com.facebook.buck.rules.args.Arg> argsBuilder = ImmutableList.builder();
    argsBuilder.addAll(
        CxxDescriptionEnhancer.toStringWithMacrosArgs(
            params.getBuildTarget(),
            cellRoots,
            ruleResolver,
            cxxPlatform,
            CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                args.getLinkerFlags(), args.getPlatformLinkerFlags(), cxxPlatform)));

    // Add object files into the args.
    argsBuilder.addAll(SourcePathArg.from(picObjects.values()));

    return argsBuilder.build();
  }

  private BuildRule createExtensionBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      CxxLuaExtensionDescriptionArg args)
      throws NoSuchBuildTargetException {
    if (params.getBuildTarget().getFlavors().contains(CxxDescriptionEnhancer.SANDBOX_TREE_FLAVOR)) {
      return CxxDescriptionEnhancer.createSandboxTreeBuildRule(
          ruleResolver, args, cxxPlatform, params);
    }
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    String extensionName = getExtensionName(params.getBuildTarget(), cxxPlatform);
    Path extensionPath =
        getExtensionPath(params.getProjectFilesystem(), params.getBuildTarget(), cxxPlatform);
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        params,
        ruleResolver,
        pathResolver,
        ruleFinder,
        getExtensionTarget(params.getBuildTarget(), cxxPlatform.getFlavor()),
        Linker.LinkType.SHARED,
        Optional.of(extensionName),
        extensionPath,
        Linker.LinkableDepType.SHARED,
        /* thinLto */ false,
        RichStream.from(args.getCxxDeps().get(ruleResolver, cxxPlatform))
            .filter(NativeLinkable.class)
            .concat(Stream.of(luaConfig.getLuaCxxLibrary(ruleResolver)))
            .toImmutableList(),
        args.getCxxRuntimeType(),
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(
                getExtensionArgs(
                    params.withoutFlavor(LinkerMapMode.NO_LINKER_MAP.getFlavor()),
                    ruleResolver,
                    pathResolver,
                    ruleFinder,
                    cellRoots,
                    cxxPlatform,
                    args))
            .build(),
        Optional.empty());
  }

  @Override
  public Class<CxxLuaExtensionDescriptionArg> getConstructorArgType() {
    return CxxLuaExtensionDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      final CxxLuaExtensionDescriptionArg args)
      throws NoSuchBuildTargetException {

    // See if we're building a particular "type" of this library, and if so, extract
    // it as an enum.
    Optional<Map.Entry<Flavor, CxxPlatform>> platform =
        cxxPlatforms.getFlavorAndValue(params.getBuildTarget());

    // If a C/C++ platform is specified, then build an extension with it.
    if (platform.isPresent()) {
      return createExtensionBuildRule(params, resolver, cellRoots, platform.get().getValue(), args);
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    final SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    return new CxxLuaExtension(params) {

      @Override
      public String getModule(CxxPlatform cxxPlatform) {
        String baseModule = LuaUtil.getBaseModule(params.getBuildTarget(), args.getBaseModule());
        String name = getExtensionName(params.getBuildTarget(), cxxPlatform);
        return baseModule.isEmpty() ? name : baseModule + File.separator + name;
      }

      @Override
      public SourcePath getExtension(CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
        BuildRule rule =
            resolver.requireRule(
                BuildTarget.builder(getBuildTarget()).addFlavors(cxxPlatform.getFlavor()).build());
        return Preconditions.checkNotNull(rule.getSourcePathToOutput());
      }

      @Override
      public NativeLinkTargetMode getNativeLinkTargetMode(CxxPlatform cxxPlatform) {
        return NativeLinkTargetMode.library();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(CxxPlatform cxxPlatform) {
        return RichStream.from(args.getCxxDeps().get(resolver, cxxPlatform))
            .filter(NativeLinkable.class)
            .toImmutableList();
      }

      @Override
      public NativeLinkableInput getNativeLinkTargetInput(CxxPlatform cxxPlatform)
          throws NoSuchBuildTargetException {
        return NativeLinkableInput.builder()
            .addAllArgs(
                getExtensionArgs(
                    params, resolver, pathResolver, ruleFinder, cellRoots, cxxPlatform, args))
            .addAllFrameworks(args.getFrameworks())
            .build();
      }

      @Override
      public Optional<Path> getNativeLinkTargetOutputPath(CxxPlatform cxxPlatform) {
        return Optional.empty();
      }
    };
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractCxxLuaExtensionDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Add deps from lua C/C++ library.
    extraDepsBuilder.addAll(OptionalCompat.asSet(luaConfig.getLuaCxxLibraryTarget()));

    // Get any parse time deps from the C/C++ platforms.
    extraDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatforms.getValues()));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractCxxLuaExtensionDescriptionArg extends CxxConstructorArg {
    Optional<String> getBaseModule();
  }
}
