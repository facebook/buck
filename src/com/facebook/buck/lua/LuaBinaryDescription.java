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
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.python.CxxPythonExtension;
import com.facebook.buck.python.PythonBinaryDescription;
import com.facebook.buck.python.PythonPackagable;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.python.PythonPlatform;
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
import com.facebook.buck.util.MoreMaps;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LuaBinaryDescription implements
    Description<LuaBinaryDescription.Arg>,
    ImplicitDepsInferringDescription<LuaBinaryDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("lua_binary");

  private static final String STARTER = "com/facebook/buck/lua/starter.lua.in";
  private static final String NATIVE_STARTER_CXX_SOURCE =
      "com/facebook/buck/lua/native-starter.cpp.in";

  private final LuaConfig luaConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final CxxPlatform defaultCxxPlatform;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final FlavorDomain<PythonPlatform> pythonPlatforms;

  public LuaBinaryDescription(
      LuaConfig luaConfig,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      FlavorDomain<PythonPlatform> pythonPlatforms) {
    this.luaConfig = luaConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.cxxPlatforms = cxxPlatforms;
    this.pythonPlatforms = pythonPlatforms;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @VisibleForTesting
  protected static BuildTarget getNativeLibsSymlinkTreeTarget(BuildTarget target) {
    return target.withAppendedFlavors(ImmutableFlavor.of("native-libs-link-tree"));
  }

  private Path getOutputPath(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(
        filesystem,
        target,
        "%s" + luaConfig.getExtension());
  }

  private String getNativeStarterCxxSourceTemplate() {
    try {
      return Resources.toString(Resources.getResource(NATIVE_STARTER_CXX_SOURCE), Charsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private CxxSource getNativeStarterCxxSource(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      final CxxPlatform cxxPlatform,
      final String mainModule,
      final Optional<Path> relativeModulesDir,
      final Optional<Path> relativePythonModulesDir) {

    BuildTarget templateTarget =
        BuildTarget.builder(baseParams.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("native-starter-cxx-source-template"))
            .build();
    ruleResolver.addToIndex(
        new WriteFile(
            baseParams.copyWithChanges(
                templateTarget,
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
            pathResolver,
            getNativeStarterCxxSourceTemplate(),
            BuildTargets.getGenPath(
                baseParams.getProjectFilesystem(),
                templateTarget,
                "%s/native-starter.cpp.in"),
            /* executable */ false));

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
            new BuildTargetSourcePath(templateTarget),
            ImmutableMap.of(
                "MAIN_MODULE",
                Escaper.escapeAsPythonString(mainModule),
                "MODULES_DIR",
                relativeModulesDir.isPresent() ?
                    Escaper.escapeAsPythonString(relativeModulesDir.get().toString()) :
                    "NULL",
                "PY_MODULES_DIR",
                relativePythonModulesDir.isPresent() ?
                    Escaper.escapeAsPythonString(relativePythonModulesDir.get().toString()) :
                    "NULL",
                "EXT_SUFFIX",
                Escaper.escapeAsPythonString(cxxPlatform.getSharedLibraryExtension())),
            /* executable */ false));

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

  private SourcePath getNativeStarter(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Path output,
      Optional<BuildTarget> nativeStarterLibrary,
      String mainModule,
      Optional<Path> relativeModulesDir,
      Optional<Path> relativePythonModulesDir,
      Optional<Path> relativeNativeLibsDir)
      throws NoSuchBuildTargetException {
    BuildTarget target =
        BuildTarget.builder(baseParams.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("native-starter"))
            .build();
    Iterable<? extends AbstractCxxLibrary> nativeStarterDeps =
        getNativeStarterDeps(ruleResolver, nativeStarterLibrary);
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
                    relativeModulesDir,
                    relativePythonModulesDir)),
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
    return new BuildTargetSourcePath(target);
  }

  private String getPureStarterTemplate() {
    try {
      return Resources.toString(Resources.getResource(STARTER), Charsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private SourcePath getPureStarter(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      final SourcePathResolver pathResolver,
      final CxxPlatform cxxPlatform,
      Path output,
      final String mainModule,
      final Optional<Path> relativeModulesDir,
      final Optional<Path> relativePythonModulesDir) {

    BuildTarget templateTarget =
        BuildTarget.builder(baseParams.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("starter-template"))
            .build();
    ruleResolver.addToIndex(
        new WriteFile(
            baseParams.copyWithChanges(
                templateTarget,
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
            pathResolver,
            getPureStarterTemplate(),
            BuildTargets.getGenPath(
                baseParams.getProjectFilesystem(),
                templateTarget,
                "%s/starter.lua.in"),
            /* executable */ false));

    BuildTarget target =
        BuildTarget.builder(baseParams.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("pure-starter"))
            .build();
    final Tool lua = luaConfig.getLua(ruleResolver);
    ruleResolver.addToIndex(
        WriteStringTemplateRule.from(
            baseParams,
            pathResolver,
            target,
            output,
            new BuildTargetSourcePath(templateTarget),
            ImmutableMap.of(
                "SHEBANG",
                lua.getCommandPrefix(pathResolver).get(0),
                "MAIN_MODULE",
                Escaper.escapeAsPythonString(mainModule),
                "MODULES_DIR",
                relativeModulesDir.isPresent() ?
                    Escaper.escapeAsPythonString(relativeModulesDir.get().toString()) :
                    "nil",
                "PY_MODULES_DIR",
                relativePythonModulesDir.isPresent() ?
                    Escaper.escapeAsPythonString(relativePythonModulesDir.get().toString()) :
                    "nil",
                "EXT_SUFFIX",
                Escaper.escapeAsPythonString(cxxPlatform.getSharedLibraryExtension())),
            /* executable */ true));

    return new BuildTargetSourcePath(target);
  }

  private StarterType getStarterType(LuaPackageComponents components) {
    return luaConfig.getStarterType()
        .or(components.getNativeLibraries().isEmpty() ? StarterType.PURE : StarterType.NATIVE);
  }

  private SourcePath getStarter(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Path output,
      StarterType starterType,
      Optional<BuildTarget> nativeStarterLibrary,
      String mainModule,
      Optional<Path> relativeModulesDir,
      Optional<Path> relativePythonModulesDir,
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
            output,
            mainModule,
            relativeModulesDir,
            relativePythonModulesDir);
      case NATIVE:
        return getNativeStarter(
            baseParams,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            output,
            nativeStarterLibrary,
            mainModule,
            relativeModulesDir,
            relativePythonModulesDir,
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
      final CxxPlatform cxxPlatform,
      final PythonPlatform pythonPlatform)
      throws NoSuchBuildTargetException {

    final LuaPackageComponents.Builder builder = LuaPackageComponents.builder();

    final Map<BuildTarget, NativeLinkable> nativeLinkables = new LinkedHashMap<>();
    final Map<BuildTarget, CxxPythonExtension> pythonExtensions = new LinkedHashMap<>();

    // Walk the deps to find all Lua packageables and native linkables.
    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(deps) {
      private final ImmutableSet<BuildRule> empty = ImmutableSet.of();
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) throws NoSuchBuildTargetException {
        ImmutableSet<BuildRule> deps = empty;
        if (rule instanceof LuaPackageable) {
          LuaPackageable packageable = (LuaPackageable) rule;
          LuaPackageComponents.addComponents(builder, packageable.getLuaPackageComponents());
          deps = rule.getDeps();
        } else if (rule instanceof CxxPythonExtension) {
          pythonExtensions.put(rule.getBuildTarget(), (CxxPythonExtension) rule);
        } else if (rule instanceof PythonPackagable) {
          PythonPackagable packageable = (PythonPackagable) rule;
          PythonPackageComponents components =
              packageable.getPythonPackageComponents(pythonPlatform, cxxPlatform);
          builder.putAllPythonModules(
              MoreMaps.transformKeys(
                  components.getModules(),
                  Functions.toStringFunction()));
          builder.putAllNativeLibraries(
              MoreMaps.transformKeys(
                  components.getNativeLibraries(),
                  Functions.toStringFunction()));
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

    // For regular linking, add all extensions via the package components interface and their
    // python-platform specific deps to the native linkables.
    for (Map.Entry<BuildTarget, CxxPythonExtension> entry : pythonExtensions.entrySet()) {
      PythonPackageComponents components =
          entry.getValue().getPythonPackageComponents(pythonPlatform, cxxPlatform);
      builder.putAllPythonModules(
          MoreMaps.transformKeys(
              components.getModules(),
              Functions.toStringFunction()));
      builder.putAllNativeLibraries(
          MoreMaps.transformKeys(
              components.getNativeLibraries(),
              Functions.toStringFunction()));
      nativeLinkables.putAll(
          Maps.uniqueIndex(
              entry.getValue().getNativeLinkTarget(pythonPlatform)
                  .getSharedNativeLinkTargetDeps(cxxPlatform),
              HasBuildTarget.TO_TARGET));
    }

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
      BuildTarget linkTreeTarget,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      ImmutableMap<String, SourcePath> components) {
    Path linkTreeRoot =
        params.getProjectFilesystem().resolve(
            BuildTargets.getGenPath(params.getProjectFilesystem(), linkTreeTarget, "%s"));
    return resolver.addToIndex(
        SymlinkTree.from(
            params.copyWithChanges(
                linkTreeTarget,
                Suppliers.ofInstance(
                    ImmutableSortedSet.copyOf(
                        pathResolver.filterBuildRuleInputs(components.values()))),
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
            pathResolver,
            linkTreeRoot,
            components));
  }

  /**
   * @return the native library map with additional entries for library names with the version
   *     suffix stripped (e.g. libfoo.so.1.0 -> libfoo.so) to appease LuaJIT, which wants to load
   *     libraries using the build-time name.
   */
  private ImmutableSortedMap<String, SourcePath> addVersionLessLibraries(
      CxxPlatform cxxPlatform,
      ImmutableSortedMap<String, SourcePath> libraries) {
    Pattern versionedExtension =
        Pattern.compile(
            Joiner.on("[.\\d]*").join(
                Iterables.transform(
                    Splitter.on("%s").split(cxxPlatform.getSharedLibraryVersionedExtensionFormat()),
                    new Function<String, String>() {
                      @Override
                      public String apply(String input) {
                        return input.isEmpty() ? input : Pattern.quote(input);
                      }
                    })));
    ImmutableSortedMap.Builder<String, SourcePath> builder = ImmutableSortedMap.naturalOrder();
    for (Map.Entry<String, SourcePath> ent : libraries.entrySet()) {
      String name = ent.getKey();
      builder.put(name, ent.getValue());
      Matcher matcher = versionedExtension.matcher(name);
      String versionLessName = matcher.replaceAll(cxxPlatform.getSharedLibraryExtension());
      if (!versionLessName.equals(ent.getKey()) && !libraries.containsKey(versionLessName)) {
        builder.put(versionLessName, ent.getValue());
      }
    }
    return builder.build();
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
                params.getBuildTarget()
                    .withAppendedFlavors(ImmutableFlavor.of("modules-link-tree")),
                params,
                resolver,
                pathResolver,
                components.getModules()));
    final Path relativeModulesLinkTreeRoot =
        output.getParent().relativize(
            params.getProjectFilesystem().getRootPath().relativize(modulesLinkTree.getRoot()));

    Optional<Path> relativePythonModulesLinkTreeRoot = Optional.absent();
    final List<SymlinkTree> pythonModulesLinktree = new ArrayList<>();
    if (!components.getPythonModules().isEmpty()) {
      // Add in any missing init modules into the python components.
      SourcePath emptyInit =
          PythonBinaryDescription.createEmptyInitModule(params, resolver, pathResolver);
      ImmutableMap<String, SourcePath> pythonModules =
          MoreMaps.transformKeys(
              PythonBinaryDescription.addMissingInitModules(
                  MoreMaps.transformKeys(
                      components.getPythonModules(),
                      MorePaths.toPathFn(
                          params.getProjectFilesystem().getRootPath().getFileSystem())),
                  emptyInit),
              Functions.toStringFunction());
      final SymlinkTree symlinkTree =
          resolver.addToIndex(
              createSymlinkTree(
                  params.getBuildTarget()
                      .withAppendedFlavors(ImmutableFlavor.of("python-modules-link-tree")),
                  params,
                  resolver,
                  pathResolver,
                  pythonModules));
      pythonModulesLinktree.add(symlinkTree);
      relativePythonModulesLinkTreeRoot =
          Optional.of(
              output.getParent().relativize(
                  params.getProjectFilesystem().getRootPath()
                      .relativize(symlinkTree.getRoot())));
    }

    final List<SymlinkTree> nativeLibsLinktree = new ArrayList<>();
    Optional<Path> relativeNativeLibsLinkTreeRoot = Optional.absent();
    if (!components.getNativeLibraries().isEmpty()) {
      SymlinkTree symlinkTree =
          resolver.addToIndex(
              createSymlinkTree(
                  getNativeLibsSymlinkTreeTarget(params.getBuildTarget()),
                  params,
                  resolver,
                  pathResolver,
                  addVersionLessLibraries(cxxPlatform, components.getNativeLibraries())));
      nativeLibsLinktree.add(symlinkTree);
      relativeNativeLibsLinkTreeRoot =
          Optional.of(
              output.getParent().relativize(
                  params.getProjectFilesystem().getRootPath()
                      .relativize(symlinkTree.getRoot())));
    }

    final SourcePath starter =
        getStarter(
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            output,
            starterType,
            nativeStarterLibrary,
            mainModule,
            Optional.of(relativeModulesLinkTreeRoot),
            relativePythonModulesLinkTreeRoot,
            relativeNativeLibsLinkTreeRoot);
    return new Tool() {

      @Override
      public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
        return ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(pathResolver.filterBuildRuleInputs(starter))
            .addAll(components.getDeps(resolver))
            .add(modulesLinkTree)
            .addAll(nativeLibsLinktree)
            .addAll(pythonModulesLinktree)
            .build();
      }

      @Override
      public ImmutableCollection<SourcePath> getInputs() {
        return ImmutableSortedSet.<SourcePath>naturalOrder()
            .add(starter)
            .addAll(components.getInputs())
            .build();
      }

      @Override
      public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
        return ImmutableList.of(resolver.getAbsolutePath(starter).toString());
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

  private Tool getStandaloneBinary(
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

    Optional<SourcePath> nativeStarter = Optional.absent();
    if (starterType == StarterType.NATIVE) {
      nativeStarter =
          Optional.of(
              getNativeStarter(
                  params,
                  resolver,
                  pathResolver,
                  cxxPlatform,
                  BuildTargets.getGenPath(
                      params.getProjectFilesystem(),
                      params.getBuildTarget(),
                      "%s-native-starter"),
                  nativeStarterLibrary,
                  mainModule,
                  Optional.<Path>absent(),
                  Optional.<Path>absent(),
                  Optional.<Path>absent()));
    }

    Tool lua = luaConfig.getLua(resolver);
    Tool packager = luaConfig.getPackager().resolve(resolver);

    LuaStandaloneBinary binary =
        resolver.addToIndex(
            new LuaStandaloneBinary(
                params.copyWithChanges(
                    params.getBuildTarget().withAppendedFlavors(ImmutableFlavor.of("binary")),
                    Suppliers.ofInstance(
                        ImmutableSortedSet.<BuildRule>naturalOrder()
                            .addAll(pathResolver.filterBuildRuleInputs(nativeStarter.asSet()))
                            .addAll(components.getDeps(pathResolver))
                            .addAll(lua.getDeps(pathResolver))
                            .addAll(packager.getDeps(pathResolver))
                            .build()),
                    Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
                pathResolver,
                packager,
                ImmutableList.<String>of(),
                output,
                nativeStarter,
                components,
                mainModule,
                lua,
                luaConfig.shouldCacheBinaries()));

    return new CommandTool.Builder()
        .addArg(new SourcePathArg(pathResolver, new BuildTargetSourcePath(binary.getBuildTarget())))
        .build();
  }

  private Tool getBinary(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      final SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      StarterType starterType,
      Optional<BuildTarget> nativeStarterLibrary,
      String mainModule,
      final LuaPackageComponents components,
      LuaConfig.PackageStyle packageStyle)
      throws NoSuchBuildTargetException {
    switch (packageStyle) {
      case STANDALONE:
        return getStandaloneBinary(
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            starterType,
            nativeStarterLibrary,
            mainModule,
            components);
      case INPLACE:
        return getInPlaceBinary(
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            starterType,
            nativeStarterLibrary,
            mainModule,
            components);
    }
    throw new IllegalStateException(
        String.format(
            "%s: unexpected package style %s",
            params.getBuildTarget(),
            packageStyle));
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
    PythonPlatform pythonPlatform =
        pythonPlatforms.getValue(params.getBuildTarget())
            .or(pythonPlatforms.getValue(
                args.pythonPlatform
                    .transform(Flavor.TO_FLAVOR)
                    .or(pythonPlatforms.getFlavors().iterator().next())));
    LuaPackageComponents components =
        getPackageComponentsFromDeps(params.getDeps(), cxxPlatform, pythonPlatform);
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
        getBinary(
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            starterType,
            nativeStarterLibrary,
            args.mainModule,
            components,
            args.packageStyle.or(luaConfig.getPackageStyle()));
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
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    if (luaConfig.getPackageStyle() == LuaConfig.PackageStyle.STANDALONE) {
      targets.addAll(luaConfig.getPackager().getParseTimeDeps());
    }
    targets.addAll(getNativeStarterDepTargets());
    return targets.build();
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
    public Optional<String> pythonPlatform;
    public Optional<LuaConfig.PackageStyle> packageStyle;
  }

}
