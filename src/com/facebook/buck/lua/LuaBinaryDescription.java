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
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.NativeLinkStrategy;
import com.facebook.buck.cxx.NativeLinkTarget;
import com.facebook.buck.cxx.NativeLinkTargetMode;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.cxx.Omnibus;
import com.facebook.buck.cxx.OmnibusLibraries;
import com.facebook.buck.cxx.OmnibusLibrary;
import com.facebook.buck.cxx.OmnibusRoot;
import com.facebook.buck.cxx.OmnibusRoots;
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
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreMaps;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

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
  private static final Flavor BINARY_FLAVOR = ImmutableFlavor.of("binary");

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

  private static Path getNativeLibsSymlinkTreeRoot(
      BuildTarget target,
      ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, getNativeLibsSymlinkTreeTarget(target), "%s");
  }

  private static BuildTarget getModulesSymlinkTreeTarget(BuildTarget target) {
    return target.withAppendedFlavors(ImmutableFlavor.of("modules-link-tree"));
  }

  private static Path getModulesSymlinkTreeRoot(
      BuildTarget target,
      ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, getModulesSymlinkTreeTarget(target), "%s");
  }

  private static BuildTarget getPythonModulesSymlinkTreeTarget(BuildTarget target) {
    return target.withAppendedFlavors(ImmutableFlavor.of("python-modules-link-tree"));
  }

  private static Path getPythonModulesSymlinkTreeRoot(
      BuildTarget target,
      ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, getPythonModulesSymlinkTreeTarget(target), "%s");
  }

  private Path getOutputPath(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(
        filesystem,
        target,
        "%s" + luaConfig.getExtension());
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

  private StarterType getStarterType(LuaPackageComponents components) {
    return luaConfig.getStarterType()
        .or(components.getNativeLibraries().isEmpty() ? StarterType.PURE : StarterType.NATIVE);
  }

  private Starter getStarter(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      BuildTarget target,
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
        return LuaScriptStarter.of(
            baseParams,
            ruleResolver,
            pathResolver,
            luaConfig,
            cxxPlatform,
            target,
            output,
            mainModule,
            relativeModulesDir,
            relativePythonModulesDir);
      case NATIVE:
        return NativeExecutableStarter.of(
            baseParams,
            ruleResolver,
            pathResolver,
            luaConfig,
            cxxBuckConfig,
            cxxPlatform,
            target,
            output,
            mainModule,
            nativeStarterLibrary,
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

  private LuaBinaryPackageComponents getPackageComponentsFromDeps(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      final CxxPlatform cxxPlatform,
      final PythonPlatform pythonPlatform,
      Optional<BuildTarget> nativeStarterLibrary,
      String mainModule,
      LuaConfig.PackageStyle packageStyle,
      Iterable<BuildRule> deps)
      throws NoSuchBuildTargetException {

    final LuaPackageComponents.Builder builder = LuaPackageComponents.builder();
    final OmnibusRoots.Builder omnibusRoots =
        OmnibusRoots.builder(
            cxxPlatform,
            // For now, we exclude the native starter from omnibus linking.
            FluentIterable.from(getNativeStarterDeps(ruleResolver, nativeStarterLibrary))
                .transform(HasBuildTarget.TO_TARGET)
                .toSet());

    final Map<BuildTarget, NativeLinkable> nativeLinkableRoots = new LinkedHashMap<>();
    final Map<BuildTarget, CxxLuaExtension> luaExtensions = new LinkedHashMap<>();
    final Map<BuildTarget, CxxPythonExtension> pythonExtensions = new LinkedHashMap<>();

    // Walk the deps to find all Lua packageables and native linkables.
    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(deps) {
      private final ImmutableSet<BuildRule> empty = ImmutableSet.of();
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) throws NoSuchBuildTargetException {
        ImmutableSet<BuildRule> deps = empty;
        if (rule instanceof LuaPackageable) {
          LuaPackageable packageable = (LuaPackageable) rule;
          LuaPackageComponents components = packageable.getLuaPackageComponents();
          LuaPackageComponents.addComponents(builder, components);
          if (components.hasNativeCode(cxxPlatform)) {
            for (BuildRule dep : rule.getDeps()) {
              if (dep instanceof NativeLinkable) {
                NativeLinkable linkable = (NativeLinkable) dep;
                nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
                omnibusRoots.addExcludedRoot(linkable);
              }
            }
          }
          deps = rule.getDeps();
        } else if (rule instanceof CxxPythonExtension) {
          CxxPythonExtension extension = (CxxPythonExtension) rule;
          NativeLinkTarget target = extension.getNativeLinkTarget(pythonPlatform);
          pythonExtensions.put(rule.getBuildTarget(), (CxxPythonExtension) rule);
          omnibusRoots.addIncludedRoot(target);
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
          if (components.hasNativeCode(cxxPlatform)) {
            for (BuildRule dep : rule.getDeps()) {
              if (dep instanceof NativeLinkable) {
                NativeLinkable linkable = (NativeLinkable) dep;
                nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
                omnibusRoots.addExcludedRoot(linkable);
              }
            }
          }
          deps = rule.getDeps();
        } else if (rule instanceof CxxLuaExtension) {
          CxxLuaExtension extension = (CxxLuaExtension) rule;
          luaExtensions.put(extension.getBuildTarget(), extension);
          omnibusRoots.addIncludedRoot(extension);
        } else if (rule instanceof NativeLinkable) {
          NativeLinkable linkable = (NativeLinkable) rule;
          nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
          omnibusRoots.addPotentialRoot(rule);
        }
        return deps;
      }
    }.start();

    if (luaConfig.getNativeLinkStrategy() == NativeLinkStrategy.MERGED) {
      OmnibusRoots roots = omnibusRoots.build();
      OmnibusLibraries libraries =
          Omnibus.getSharedLibraries(
              baseParams,
              ruleResolver,
              pathResolver,
              cxxBuckConfig,
              cxxPlatform,
              ImmutableList.<com.facebook.buck.rules.args.Arg>of(),
              roots.getIncludedRoots().values(),
              roots.getExcludedRoots().values());

      // Add all the roots from the omnibus link.  If it's an extension, add it as a module.
      for (Map.Entry<BuildTarget, OmnibusRoot> root : libraries.getRoots().entrySet()) {

        // If it's a Lua extension add it as a module.
        CxxLuaExtension luaExtension = luaExtensions.get(root.getKey());
        if (luaExtension != null) {
          builder.putModules(luaExtension.getModule(cxxPlatform), root.getValue().getPath());
          continue;
        }

        // If it's a Python extension, add it as a python module.
        CxxPythonExtension pythonExtension = pythonExtensions.get(root.getKey());
        if (pythonExtension != null) {
          builder.putPythonModules(
              pythonExtension.getModule().toString(),
              root.getValue().getPath());
          continue;
        }

        // Otherwise, add it as a native library.
        NativeLinkTarget target =
            Preconditions.checkNotNull(
                roots.getIncludedRoots().get(root.getKey()),
                "%s: linked unexpected omnibus root: %s",
                baseParams.getBuildTarget(),
                root.getKey());
        NativeLinkTargetMode mode = target.getNativeLinkTargetMode(cxxPlatform);
        String soname =
            Preconditions.checkNotNull(
                mode.getLibraryName().orNull(),
                "%s: omnibus library for %s was built without soname",
                baseParams.getBuildTarget(),
                root.getKey());
        builder.putNativeLibraries(soname, root.getValue().getPath());
      }

      // Add all remaining libraries as native libraries.
      for (OmnibusLibrary library : libraries.getLibraries()) {
        builder.putNativeLibraries(library.getSoname(), library.getPath());
      }

    } else {

      // For regular linking, add all Lua extensions as modules and their deps as native linkable
      // roots.
      for (Map.Entry<BuildTarget, CxxLuaExtension> entry : luaExtensions.entrySet()) {
        CxxLuaExtension extension = entry.getValue();
        builder.putModules(extension.getModule(cxxPlatform), extension.getExtension(cxxPlatform));
        nativeLinkableRoots.putAll(
            Maps.uniqueIndex(
                extension.getNativeLinkTargetDeps(cxxPlatform),
                HasBuildTarget.TO_TARGET));
      }

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
        nativeLinkableRoots.putAll(
            Maps.uniqueIndex(
                entry.getValue().getNativeLinkTarget(pythonPlatform)
                    .getNativeLinkTargetDeps(cxxPlatform),
                HasBuildTarget.TO_TARGET));
      }

      // Add shared libraries from all native linkables.
      for (NativeLinkable nativeLinkable :
          NativeLinkables.getTransitiveNativeLinkables(
              cxxPlatform,
              nativeLinkableRoots.values()).values()) {
        NativeLinkable.Linkage linkage = nativeLinkable.getPreferredLinkage(cxxPlatform);
        if (linkage != NativeLinkable.Linkage.STATIC) {
          builder.putAllNativeLibraries(nativeLinkable.getSharedLibraries(cxxPlatform));
        }
      }
    }

    Path output = getOutputPath(baseParams.getBuildTarget(), baseParams.getProjectFilesystem());
    LuaPackageComponents components = builder.build();
    StarterType starterType = getStarterType(components);

    // For native starter types, add in the native linkable deps for the native starter library.
    if (starterType == StarterType.NATIVE) {
      components =
          addNativeDeps(
              components,
              cxxPlatform,
              getNativeStarterDeps(ruleResolver, nativeStarterLibrary));
    }

    // The relative paths from the starter to the various components.
    Optional<Path> relativeModulesDir = Optional.absent();
    Optional<Path> relativePythonModulesDir = Optional.absent();
    Optional<Path> relativeNativeLibsDir = Optional.absent();

    // For in-place binaries, set the relative paths to the symlink trees holding the components.
    if (packageStyle == LuaConfig.PackageStyle.INPLACE) {
      if (!components.getModules().isEmpty()) {
        relativeModulesDir =
            Optional.of(
                output.getParent().relativize(
                    getModulesSymlinkTreeRoot(
                        baseParams.getBuildTarget(),
                        baseParams.getProjectFilesystem())));
      }
      if (!components.getNativeLibraries().isEmpty()) {
        relativeNativeLibsDir =
            Optional.of(
                output.getParent().relativize(
                    getNativeLibsSymlinkTreeRoot(
                        baseParams.getBuildTarget(),
                        baseParams.getProjectFilesystem())));
      }
      if (!components.getPythonModules().isEmpty()) {
        relativePythonModulesDir =
            Optional.of(
                output.getParent().relativize(
                    getPythonModulesSymlinkTreeRoot(
                        baseParams.getBuildTarget(),
                        baseParams.getProjectFilesystem())));
      }
    }

    // Build the starter.
    Starter starter =
        getStarter(
            baseParams,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            baseParams.getBuildTarget().withAppendedFlavors(
                packageStyle == LuaConfig.PackageStyle.STANDALONE ?
                    ImmutableFlavor.of("starter") :
                    BINARY_FLAVOR),
            packageStyle == LuaConfig.PackageStyle.STANDALONE ?
                output.resolveSibling(output.getFileName() + "-starter") :
                output,
            starterType,
            nativeStarterLibrary,
            mainModule,
            relativeModulesDir,
            relativePythonModulesDir,
            relativeNativeLibsDir);

    return LuaBinaryPackageComponents.of(starter.build(), components);
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
      Path root,
      ImmutableMap<String, SourcePath> components) {
    return resolver.addToIndex(
        SymlinkTree.from(
            params.copyWithChanges(
                linkTreeTarget,
                Suppliers.ofInstance(
                    ImmutableSortedSet.copyOf(
                        pathResolver.filterBuildRuleInputs(components.values()))),
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
            pathResolver,
            root,
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
      final SourcePath starter,
      final LuaPackageComponents components)
      throws NoSuchBuildTargetException {
    final List<SourcePath> extraInputs = new ArrayList<>();

    final SymlinkTree modulesLinkTree =
        resolver.addToIndex(
            createSymlinkTree(
                getModulesSymlinkTreeTarget(params.getBuildTarget()),
                params,
                resolver,
                pathResolver,
                params.getProjectFilesystem().resolve(
                    getModulesSymlinkTreeRoot(
                        params.getBuildTarget(),
                        params.getProjectFilesystem())),
                components.getModules()));

    final List<SymlinkTree> pythonModulesLinktree = new ArrayList<>();
    if (!components.getPythonModules().isEmpty()) {
      // Add in any missing init modules into the python components.
      SourcePath emptyInit =
          PythonBinaryDescription.createEmptyInitModule(params, resolver, pathResolver);
      extraInputs.add(emptyInit);
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
                  getPythonModulesSymlinkTreeTarget(params.getBuildTarget()),
                  params,
                  resolver,
                  pathResolver,
                  params.getProjectFilesystem().resolve(
                      getPythonModulesSymlinkTreeRoot(
                          params.getBuildTarget(),
                          params.getProjectFilesystem())),
                  pythonModules));
      pythonModulesLinktree.add(symlinkTree);
    }

    final List<SymlinkTree> nativeLibsLinktree = new ArrayList<>();
    if (!components.getNativeLibraries().isEmpty()) {
      SymlinkTree symlinkTree =
          resolver.addToIndex(
              createSymlinkTree(
                  getNativeLibsSymlinkTreeTarget(params.getBuildTarget()),
                  params,
                  resolver,
                  pathResolver,
                  params.getProjectFilesystem().resolve(
                      getNativeLibsSymlinkTreeRoot(
                          params.getBuildTarget(),
                          params.getProjectFilesystem())),
                  addVersionLessLibraries(cxxPlatform, components.getNativeLibraries())));
      nativeLibsLinktree.add(symlinkTree);
    }

    return new Tool() {

      @Override
      public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
        return ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(pathResolver.filterBuildRuleInputs(starter))
            .addAll(components.getDeps(resolver))
            .add(modulesLinkTree)
            .addAll(nativeLibsLinktree)
            .addAll(pythonModulesLinktree)
            .addAll(pathResolver.filterBuildRuleInputs(extraInputs))
            .build();
      }

      @Override
      public ImmutableCollection<SourcePath> getInputs() {
        return ImmutableSortedSet.<SourcePath>naturalOrder()
            .add(starter)
            .addAll(components.getInputs())
            .addAll(extraInputs)
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
      SourcePath starter,
      String mainModule,
      final LuaPackageComponents components)
      throws NoSuchBuildTargetException {
    Path output = getOutputPath(params.getBuildTarget(), params.getProjectFilesystem());

    Tool lua = luaConfig.getLua(resolver);
    Tool packager = luaConfig.getPackager().resolve(resolver);

    LuaStandaloneBinary binary =
        resolver.addToIndex(
            new LuaStandaloneBinary(
                params.copyWithChanges(
                    params.getBuildTarget().withAppendedFlavors(BINARY_FLAVOR),
                    Suppliers.ofInstance(
                        ImmutableSortedSet.<BuildRule>naturalOrder()
                            .addAll(pathResolver.filterBuildRuleInputs(starter))
                            .addAll(components.getDeps(pathResolver))
                            .addAll(lua.getDeps(pathResolver))
                            .addAll(packager.getDeps(pathResolver))
                            .build()),
                    Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
                pathResolver,
                packager,
                ImmutableList.<String>of(),
                output,
                Optional.of(starter),
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
      String mainModule,
      SourcePath starter,
      final LuaPackageComponents components,
      LuaConfig.PackageStyle packageStyle)
      throws NoSuchBuildTargetException {
    switch (packageStyle) {
      case STANDALONE:
        return getStandaloneBinary(
            params,
            resolver,
            pathResolver,
            starter,
            mainModule,
            components);
      case INPLACE:
        return getInPlaceBinary(
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            starter,
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
    LuaBinaryPackageComponents components =
        getPackageComponentsFromDeps(
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            pythonPlatform,
            args.nativeStarterLibrary.or(luaConfig.getNativeStarterLibrary()),
            args.mainModule,
            args.packageStyle.or(luaConfig.getPackageStyle()),
            params.getDeps());
    Tool binary =
        getBinary(
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            args.mainModule,
            components.getStarter(),
            components.getComponents(),
            args.packageStyle.or(luaConfig.getPackageStyle()));
    return new LuaBinary(
        params.appendExtraDeps(binary.getDeps(pathResolver)),
        pathResolver,
        getOutputPath(params.getBuildTarget(), params.getProjectFilesystem()),
        binary,
        args.mainModule,
        components.getComponents(),
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
