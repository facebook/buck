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

import com.facebook.buck.cxx.AbstractCxxLibrary;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.Linkers;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.WriteStringTemplateRule;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.PackagedResource;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LuaBinaryDescription implements
    Description<LuaBinaryDescription.Arg>,
    ImplicitDepsInferringDescription<LuaBinaryDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("lua_binary");

  private static final String STARTER = "starter.lua.in";
  private static final String NATIVE_STARTER_CXX_SOURCE = "native-starter.cpp.in";

  private final LuaConfig luaConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final CxxPlatform defaultCxxPlatform;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public LuaBinaryDescription(
      LuaConfig luaConfig,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.luaConfig = luaConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.defaultCxxPlatform = defaultCxxPlatform;
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

  private Path getOutputPath(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(
        filesystem,
        target,
        "%s" + luaConfig.getExtension());
  }

  private CxxSource getNativeStarterCxxSource(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      final CxxPlatform cxxPlatform,
      final String mainModule,
      final Path relativeModulesDir) {
    BuildTarget target =
        BuildTarget.builder(baseParams.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("native-starter-cxx-source"))
            .build();
    Path output =
        BuildTargets.getGenPath(baseParams.getProjectFilesystem(), target, "%s/native-starter.cpp");
    ruleResolver.addToIndex(
        WriteStringTemplateRule.from(
            baseParams,
            pathResolver,
            target,
            output,
            new PathSourcePath(
                baseParams.getProjectFilesystem(),
                LuaBinaryDescription.class + "/" + NATIVE_STARTER_CXX_SOURCE,
                new PackagedResource(
                    baseParams.getProjectFilesystem(),
                    LuaBinaryDescription.class,
                    NATIVE_STARTER_CXX_SOURCE)),
            ImmutableMap.of(
                "MAIN_MODULE",
                Escaper.escapeAsPythonString(mainModule),
                "MODULES_DIR",
                Escaper.escapeAsPythonString(relativeModulesDir.toString()),
                "EXT_SUFFIX",
                Escaper.escapeAsPythonString(cxxPlatform.getSharedLibraryExtension()))));
    return CxxSource.of(
        CxxSource.Type.CXX,
        new BuildTargetSourcePath(target),
        ImmutableList.<String>of());
  }

  private ImmutableList<CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      Iterable<? extends CxxPreprocessorDep> deps)
      throws NoSuchBuildTargetException {
    ImmutableList.Builder<CxxPreprocessorInput> inputs = ImmutableList.builder();
    inputs.addAll(
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(
            cxxPlatform,
            FluentIterable.from(deps)
                .filter(BuildRule.class)));
    for (CxxPreprocessorDep dep :
         Iterables.filter(deps, Predicates.not(Predicates.instanceOf(BuildRule.class)))) {
      inputs.add(dep.getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PUBLIC));
    }
    return inputs.build();
  }

  private Iterable<BuildTarget> getNativeStarterDepTargets() {
    Optional<BuildTarget> nativeStarterLibrary = luaConfig.getNativeStarterLibrary();
    return ImmutableSet.copyOf(
        nativeStarterLibrary.isPresent() ?
            nativeStarterLibrary.asSet() :
            luaConfig.getLuaCxxLibraryTarget().asSet());
  }

  private Iterable<? extends AbstractCxxLibrary> getNativeStarterDeps(
      BuildRuleResolver ruleResolver,
      Optional<BuildTarget> nativeStarterLibrary) {
    return ImmutableList.of(
        nativeStarterLibrary.isPresent() ?
            ruleResolver.getRuleWithType(
                nativeStarterLibrary.get(),
                AbstractCxxLibrary.class) :
            luaConfig.getLuaCxxLibrary(ruleResolver));
  }

  private Tool getNativeStarter(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Optional<BuildTarget> nativeStarterLibrary,
      String mainModule,
      Path relativeModulesDir,
      Optional<Path> relativeNativeLibsDir)
      throws NoSuchBuildTargetException {
    BuildTarget target =
        BuildTarget.builder(baseParams.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("native-starter"))
            .build();
    Iterable<? extends AbstractCxxLibrary> nativeStarterDeps =
        getNativeStarterDeps(ruleResolver, nativeStarterLibrary);
    Path output = getOutputPath(baseParams.getBuildTarget(), baseParams.getProjectFilesystem());
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        CxxSourceRuleFactory.requirePreprocessAndCompileRules(
            baseParams,
            ruleResolver,
            pathResolver,
            cxxBuckConfig,
            cxxPlatform,
            ImmutableList.<CxxPreprocessorInput>builder()
                .add(
                    CxxPreprocessorInput.builder()
                        .putAllPreprocessorFlags(
                            CxxSource.Type.CXX,
                            nativeStarterLibrary.isPresent() ?
                                ImmutableList.<String>of() :
                                ImmutableList.of("-DBUILTIN_NATIVE_STARTER"))
                        .build())
                .addAll(getTransitiveCxxPreprocessorInput(cxxPlatform, nativeStarterDeps))
                .build(),
            ImmutableMultimap.<CxxSource.Type, String>of(),
            Optional.<SourcePath>absent(),
            cxxBuckConfig.getPreprocessMode(),
            ImmutableMap.of(
                "native-starter.cpp",
                getNativeStarterCxxSource(
                    baseParams,
                    ruleResolver,
                    pathResolver,
                    cxxPlatform,
                    mainModule,
                    relativeModulesDir)),
            CxxSourceRuleFactory.PicType.PDC);
    ruleResolver.addToIndex(
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            cxxBuckConfig,
            cxxPlatform,
            baseParams,
            ruleResolver,
            pathResolver,
            target,
            Linker.LinkType.EXECUTABLE,
            Optional.<String>absent(),
            output,
            Linker.LinkableDepType.SHARED,
            nativeStarterDeps,
            Optional.<Linker.CxxRuntimeType>absent(),
            Optional.<SourcePath>absent(),
            ImmutableSet.<BuildTarget>of(),
            NativeLinkableInput.builder()
                .addAllArgs(
                    relativeNativeLibsDir.isPresent() ?
                        StringArg.from(
                            Linkers.iXlinker(
                                "-rpath",
                                String.format(
                                    "%s/%s",
                                    cxxPlatform.getLd().resolve(ruleResolver).origin(),
                                    relativeNativeLibsDir.get().toString()))) :
                        ImmutableList.<com.facebook.buck.rules.args.Arg>of())
                .addAllArgs(SourcePathArg.from(pathResolver, objects.values()))
                .build()));
    return new CommandTool.Builder()
        .addArg(new SourcePathArg(pathResolver, new BuildTargetSourcePath(target)))
        .build();
  }

  private Tool getPureStarter(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      final SourcePathResolver pathResolver,
      final CxxPlatform cxxPlatform,
      final String mainModule,
      final Path relativeModulesDir) {
    BuildTarget target =
        BuildTarget.builder(baseParams.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("pure-starter"))
            .build();
    final Path output =
        getOutputPath(baseParams.getBuildTarget(), baseParams.getProjectFilesystem());
    final Tool lua = luaConfig.getLua(ruleResolver);
    ruleResolver.addToIndex(
        WriteStringTemplateRule.from(
            baseParams,
            pathResolver,
            target,
            output,
            new PathSourcePath(
                baseParams.getProjectFilesystem(),
                LuaBinaryDescription.class + "/" + STARTER,
                new PackagedResource(
                    baseParams.getProjectFilesystem(),
                    LuaBinaryDescription.class,
                    STARTER)),
            ImmutableMap.of(
                "SHEBANG",
                lua.getCommandPrefix(pathResolver).get(0),
                "MAIN_MODULE",
                Escaper.escapeAsPythonString(mainModule),
                "MODULES_DIR",
                Escaper.escapeAsPythonString(relativeModulesDir.toString()),
                "EXT_SUFFIX",
                Escaper.escapeAsPythonString(cxxPlatform.getSharedLibraryExtension()))));
    return new CommandTool.Builder(lua)
        .addArg(new SourcePathArg(pathResolver, new BuildTargetSourcePath(target)))
        .build();
  }

  private StarterType getStarterType(LuaPackageComponents components) {
    return luaConfig.getStarterType()
        .or(components.getNativeLibraries().isEmpty() ? StarterType.PURE : StarterType.NATIVE);
  }

  private Tool getStarter(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      StarterType starterType,
      Optional<BuildTarget> nativeStarterLibrary,
      String mainModule,
      Path relativeModulesDir,
      Optional<Path> relativeNativeLibsDir)
      throws NoSuchBuildTargetException {
    switch (starterType) {
      case PURE:
        if (relativeNativeLibsDir.isPresent()) {
          throw new HumanReadableException(
              "%s: cannot use pure starter with native libraries",
              baseParams.getBuildTarget());
        }
        return getPureStarter(
            baseParams,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            mainModule,
            relativeModulesDir);
      case NATIVE:
        return getNativeStarter(
            baseParams,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            nativeStarterLibrary,
            mainModule,
            relativeModulesDir,
            relativeNativeLibsDir);
    }
    throw new IllegalStateException(
        String.format(
            "%s: unexpected starter type %s",
            baseParams.getBuildTarget(),
            luaConfig.getStarterType()));
  }

  private LuaPackageComponents getPackageComponentsFromDeps(
      Iterable<BuildRule> deps,
      final CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {

    final LuaPackageComponents.Builder builder = LuaPackageComponents.builder();

    // Walk the deps to find all Lua packageables and native linkables.
    final Map<BuildTarget, NativeLinkable> nativeLinkables = new LinkedHashMap<>();
    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(deps) {
      private final ImmutableSet<BuildRule> empty = ImmutableSet.of();
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) throws NoSuchBuildTargetException {
        ImmutableSet<BuildRule> deps = empty;
        if (rule instanceof LuaPackageable) {
          LuaPackageable packageable = (LuaPackageable) rule;
          LuaPackageComponents.addComponents(builder, packageable.getLuaPackageComponents());
          deps = rule.getDeps();
        } else if (rule instanceof CxxLuaExtension) {
          CxxLuaExtension extension = (CxxLuaExtension) rule;
          builder.putModules(extension.getModule(cxxPlatform), extension.getExtension(cxxPlatform));
          nativeLinkables.putAll(
              Maps.uniqueIndex(
                  extension.getSharedNativeLinkTargetDeps(cxxPlatform),
                  HasBuildTarget.TO_TARGET));
        } else if (rule instanceof NativeLinkable) {
          NativeLinkable linkable = (NativeLinkable) rule;
          nativeLinkables.put(linkable.getBuildTarget(), linkable);
        }
        return deps;
      }
    }.start();

    // Add shared libraries from all native linkables.
    for (NativeLinkable nativeLinkable :
         NativeLinkables.getTransitiveNativeLinkables(
             cxxPlatform,
             nativeLinkables.values()).values()) {
      NativeLinkable.Linkage linkage = nativeLinkable.getPreferredLinkage(cxxPlatform);
      if (linkage != NativeLinkable.Linkage.STATIC) {
        builder.putAllNativeLibraries(nativeLinkable.getSharedLibraries(cxxPlatform));
      }
    }

    return builder.build();
  }

  private LuaPackageComponents addNativeDeps(
      LuaPackageComponents components,
      CxxPlatform cxxPlatform,
      Iterable<? extends NativeLinkable> nativeDeps)
      throws NoSuchBuildTargetException {

    Map<String, SourcePath> nativeLibs = Maps.newLinkedHashMap();
    nativeLibs.putAll(components.getNativeLibraries());

    // Add shared libraries from all native linkables.
    for (NativeLinkable nativeLinkable :
         NativeLinkables.getTransitiveNativeLinkables(
             cxxPlatform,
             nativeDeps).values()) {
      NativeLinkable.Linkage linkage = nativeLinkable.getPreferredLinkage(cxxPlatform);
      if (linkage != NativeLinkable.Linkage.STATIC) {
        nativeLibs.putAll(nativeLinkable.getSharedLibraries(cxxPlatform));
      }
    }

    return components.withNativeLibraries(nativeLibs);
  }

  private SymlinkTree createSymlinkTree(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      Flavor flavor,
      ImmutableMap<String, SourcePath> components) {
    BuildTarget linkTreeTarget = params.getBuildTarget().withAppendedFlavors(flavor);
    Path linkTreeRoot =
        params.getProjectFilesystem().resolve(
            BuildTargets.getGenPath(params.getProjectFilesystem(), linkTreeTarget, "%s"));
    return resolver.addToIndex(
        SymlinkTree.from(
            params.copyWithChanges(
                linkTreeTarget,
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
            pathResolver,
            linkTreeRoot,
            components));
  }

  private Tool getInPlaceBinary(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      final SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      StarterType starterType,
      Optional<BuildTarget> nativeStarterLibrary,
      String mainModule,
      final LuaPackageComponents components)
      throws NoSuchBuildTargetException {
    Path output = getOutputPath(params.getBuildTarget(), params.getProjectFilesystem());

    final SymlinkTree modulesLinkTree =
        resolver.addToIndex(
            createSymlinkTree(
                params,
                resolver,
                pathResolver,
                ImmutableFlavor.of("modules-link-tree"), components.getModules()));
    Path relativeModulesLinkTreeRoot =
        output.getParent().relativize(
            params.getProjectFilesystem().getRootPath().relativize(modulesLinkTree.getRoot()));

    final List<SymlinkTree> nativeLibsLinktree = new ArrayList<>();
    Optional<Path> relativeNativeLibsLinkTreeRoot = Optional.absent();
    if (!components.getNativeLibraries().isEmpty()) {
      SymlinkTree symlinkTree =
          resolver.addToIndex(
              createSymlinkTree(
                  params,
                  resolver,
                  pathResolver,
                  ImmutableFlavor.of("native-libs-link-tree"), components.getNativeLibraries()));
      nativeLibsLinktree.add(symlinkTree);
      relativeNativeLibsLinkTreeRoot =
          Optional.of(
              output.getParent().relativize(
                  params.getProjectFilesystem().getRootPath()
                      .relativize(symlinkTree.getRoot())));
    }

    final Tool starter =
        getStarter(
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            starterType,
            nativeStarterLibrary,
            mainModule,
            relativeModulesLinkTreeRoot,
            relativeNativeLibsLinkTreeRoot);
    return new Tool() {

      @Override
      public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
        return ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(starter.getDeps(pathResolver))
            .addAll(components.getDeps(pathResolver))
            .add(modulesLinkTree)
            .addAll(nativeLibsLinktree)
            .build();
      }

      @Override
      public ImmutableCollection<SourcePath> getInputs() {
        return ImmutableSortedSet.<SourcePath>naturalOrder()
            .addAll(starter.getInputs())
            .addAll(components.getInputs())
            .build();
      }

      @Override
      public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
        return starter.getCommandPrefix(resolver);
      }

      @Override
      public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
        return ImmutableMap.of();
      }

      @Override
      public void appendToRuleKey(RuleKeyObjectSink sink) {
        sink
            .setReflectively("starter", starter)
            .setReflectively("components", components);
      }

    };
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args)
      throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    CxxPlatform cxxPlatform = cxxPlatforms.getValue(params.getBuildTarget()).or(defaultCxxPlatform);
    LuaPackageComponents components = getPackageComponentsFromDeps(params.getDeps(), cxxPlatform);
    StarterType starterType = getStarterType(components);
    Optional<BuildTarget> nativeStarterLibrary =
        args.nativeStarterLibrary.or(luaConfig.getNativeStarterLibrary());
    if (starterType == StarterType.NATIVE) {
      components =
          addNativeDeps(
              components,
              cxxPlatform,
              getNativeStarterDeps(resolver, nativeStarterLibrary));
    }
    Tool binary =
        getInPlaceBinary(
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            starterType,
            nativeStarterLibrary,
            args.mainModule,
            components);
    return new LuaBinary(
        params.appendExtraDeps(binary.getDeps(pathResolver)),
        pathResolver,
        getOutputPath(params.getBuildTarget(), params.getProjectFilesystem()),
        binary,
        args.mainModule,
        components,
        luaConfig.getLua(resolver));
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    return getNativeStarterDepTargets();
  }

  public enum StarterType {
    PURE,
    NATIVE,
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public String mainModule;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<BuildTarget> nativeStarterLibrary;
  }

}
