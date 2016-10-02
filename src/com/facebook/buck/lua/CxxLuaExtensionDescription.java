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
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public class CxxLuaExtensionDescription implements
    Description<CxxLuaExtensionDescription.Arg>,
    ImplicitDepsInferringDescription<CxxLuaExtensionDescription.Arg> {

  private final LuaConfig luaConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public CxxLuaExtensionDescription(
      LuaConfig luaConfig,
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.luaConfig = luaConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
  }

  @VisibleForTesting
  protected static String getExtensionName(BuildTarget target, CxxPlatform cxxPlatform) {
    return String.format("%s.%s", target.getShortName(), cxxPlatform.getSharedLibraryExtension());
  }

  @VisibleForTesting
  protected static BuildTarget getExtensionTarget(
      BuildTarget target,
      Flavor platform) {
    return BuildTarget.builder(target)
        .addFlavors(platform)
        .build();
  }

  @VisibleForTesting
  protected Path getExtensionPath(
      ProjectFilesystem filesystem,
      BuildTarget target,
      CxxPlatform cxxPlatform) {
    return BuildTargets
        .getGenPath(filesystem, getExtensionTarget(target, cxxPlatform.getFlavor()), "%s")
        .resolve(getExtensionName(target, cxxPlatform));
  }

  private ImmutableList<com.facebook.buck.rules.args.Arg> getExtensionArgs(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Arg args) throws NoSuchBuildTargetException {

    // Extract all C/C++ sources from the constructor arg.
    ImmutableMap<String, CxxSource> srcs =
        CxxDescriptionEnhancer.parseCxxSources(
            params.getBuildTarget(),
            pathResolver,
            cxxPlatform,
            args);
    ImmutableMap<Path, SourcePath> headers =
        CxxDescriptionEnhancer.parseHeaders(
            params.getBuildTarget(),
            pathResolver,
            Optional.of(cxxPlatform),
            args);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    HeaderSymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params,
            ruleResolver,
            new SourcePathResolver(ruleResolver),
            cxxPlatform,
            headers,
            HeaderVisibility.PRIVATE,
            true);
    Optional<SymlinkTree> sandboxTree = Optional.empty();
    if (cxxBuckConfig.sandboxSources()) {
      sandboxTree =
          CxxDescriptionEnhancer.createSandboxTree(
              params,
              ruleResolver,
              cxxPlatform);
    }
    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            params,
            cxxPlatform,
            CxxFlags.getLanguageFlags(
                args.preprocessorFlags,
                args.platformPreprocessorFlags,
                args.langPreprocessorFlags,
                cxxPlatform),
            ImmutableList.of(headerSymlinkTree),
            ImmutableSet.of(),
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                cxxPlatform,
                params.getDeps()),
            args.includeDirs,
            sandboxTree);

    // Generate rule to build the object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> picObjects =
        CxxSourceRuleFactory.requirePreprocessAndCompileRules(
            params,
            ruleResolver,
            pathResolver,
            cxxBuckConfig,
            cxxPlatform,
            cxxPreprocessorInput,
            CxxFlags.getLanguageFlags(
                args.compilerFlags,
                args.platformCompilerFlags,
                args.langCompilerFlags,
                cxxPlatform),
            args.prefixHeader,
            cxxBuckConfig.getPreprocessMode(),
            srcs,
            CxxSourceRuleFactory.PicType.PIC,
            sandboxTree);

    ImmutableList.Builder<com.facebook.buck.rules.args.Arg> argsBuilder = ImmutableList.builder();
    argsBuilder.addAll(
        StringArg.from(
            CxxFlags.getFlags(
                args.linkerFlags,
                args.platformLinkerFlags,
                cxxPlatform)));

    // Add object files into the args.
    argsBuilder.addAll(SourcePathArg.from(pathResolver, picObjects.values()));

    return argsBuilder.build();
  }

  private <A extends Arg> BuildRule createExtensionBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      A args) throws NoSuchBuildTargetException {
    if (params.getBuildTarget().getFlavors().contains(CxxDescriptionEnhancer.SANDBOX_TREE_FLAVOR)) {
      return CxxDescriptionEnhancer.createSandboxTreeBuildRule(
          ruleResolver,
          args,
          cxxPlatform,
          params);
    }
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    String extensionName = getExtensionName(params.getBuildTarget(), cxxPlatform);
    Path extensionPath =
        getExtensionPath(
            params.getProjectFilesystem(),
            params.getBuildTarget(),
            cxxPlatform);
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        params,
        ruleResolver,
        pathResolver,
        getExtensionTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor()),
        Linker.LinkType.SHARED,
        Optional.of(extensionName),
        extensionPath,
        Linker.LinkableDepType.SHARED,
        FluentIterable.from(params.getDeps())
            .filter(NativeLinkable.class)
            .append(luaConfig.getLuaCxxLibrary(ruleResolver)),
        args.cxxRuntimeType,
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(
                getExtensionArgs(
                    params.withoutFlavor(LinkerMapMode.NO_LINKER_MAP.getFlavor()),
                    ruleResolver,
                    pathResolver,
                    cxxPlatform,
                    args))
            .build());
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args)
      throws NoSuchBuildTargetException {

    // See if we're building a particular "type" of this library, and if so, extract
    // it as an enum.
    Optional<Map.Entry<Flavor, CxxPlatform>> platform =
        cxxPlatforms.getFlavorAndValue(params.getBuildTarget());

    // If a C/C++ platform is specified, then build an extension with it.
    if (platform.isPresent()) {
      return createExtensionBuildRule(params, resolver, platform.get().getValue(), args);
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    final SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return new CxxLuaExtension(params, pathResolver) {

      @Override
      public String getModule(CxxPlatform cxxPlatform) {
        String baseModule = LuaUtil.getBaseModule(params.getBuildTarget(), args.baseModule);
        String name = getExtensionName(params.getBuildTarget(), cxxPlatform);
        return baseModule.isEmpty() ? name : baseModule + File.separator + name;
      }

      @Override
      public SourcePath getExtension(
          CxxPlatform cxxPlatform)
          throws NoSuchBuildTargetException {
        BuildRule rule =
            resolver.requireRule(
                BuildTarget.builder(getBuildTarget())
                    .addFlavors(cxxPlatform.getFlavor())
                    .build());
        return new BuildTargetSourcePath(rule.getBuildTarget());
      }

      @Override
      public NativeLinkTargetMode getNativeLinkTargetMode(CxxPlatform cxxPlatform) {
        return NativeLinkTargetMode.library();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(
          CxxPlatform cxxPlatform) {
        return FluentIterable.from(params.getDeclaredDeps().get())
            .filter(NativeLinkable.class);
      }

      @Override
      public NativeLinkableInput getNativeLinkTargetInput(CxxPlatform cxxPlatform)
          throws NoSuchBuildTargetException {
        return NativeLinkableInput.builder()
            .addAllArgs(getExtensionArgs(params, resolver, pathResolver, cxxPlatform, args))
            .addAllFrameworks(args.frameworks)
            .build();
      }

      @Override
      public Optional<Path> getNativeLinkTargetOutputPath(CxxPlatform cxxPlatform) {
        return Optional.empty();
      }

    };
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    // Add deps from lua C/C++ library.
    deps.addAll(OptionalCompat.asSet(luaConfig.getLuaCxxLibraryTarget()));

    // Get any parse time deps from the C/C++ platforms.
    deps.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatforms.getValues()));

    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxConstructorArg {
    public Optional<String> baseModule;
  }

}
