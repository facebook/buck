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
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.python.CxxPythonExtension;
import com.facebook.buck.python.PythonBinaryDescription;
import com.facebook.buck.python.PythonPackagable;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.python.PythonPlatform;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class LuaBinaryDescription
    implements Description<LuaBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<LuaBinaryDescription.AbstractLuaBinaryDescriptionArg>,
        VersionRoot<LuaBinaryDescriptionArg> {

  private static final Flavor BINARY_FLAVOR = InternalFlavor.of("binary");

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
  public Class<LuaBinaryDescriptionArg> getConstructorArgType() {
    return LuaBinaryDescriptionArg.class;
  }

  @VisibleForTesting
  protected static BuildTarget getNativeLibsSymlinkTreeTarget(BuildTarget target) {
    return target.withAppendedFlavors(InternalFlavor.of("native-libs-link-tree"));
  }

  private static Path getNativeLibsSymlinkTreeRoot(
      BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, getNativeLibsSymlinkTreeTarget(target), "%s");
  }

  private static BuildTarget getModulesSymlinkTreeTarget(BuildTarget target) {
    return target.withAppendedFlavors(InternalFlavor.of("modules-link-tree"));
  }

  private static Path getModulesSymlinkTreeRoot(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, getModulesSymlinkTreeTarget(target), "%s");
  }

  private static BuildTarget getPythonModulesSymlinkTreeTarget(BuildTarget target) {
    return target.withAppendedFlavors(InternalFlavor.of("python-modules-link-tree"));
  }

  private static Path getPythonModulesSymlinkTreeRoot(
      BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, getPythonModulesSymlinkTreeTarget(target), "%s");
  }

  private Path getOutputPath(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, target, "%s" + luaConfig.getExtension());
  }

  private Iterable<BuildTarget> getNativeStarterDepTargets() {
    Optional<BuildTarget> nativeStarterLibrary = luaConfig.getNativeStarterLibrary();
    return ImmutableSet.copyOf(
        nativeStarterLibrary.isPresent()
            ? OptionalCompat.asSet(nativeStarterLibrary)
            : OptionalCompat.asSet(luaConfig.getLuaCxxLibraryTarget()));
  }

  private Starter getStarter(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      BuildTarget target,
      Path output,
      StarterType starterType,
      Optional<BuildTarget> nativeStarterLibrary,
      String mainModule,
      Optional<Path> relativeModulesDir,
      Optional<Path> relativePythonModulesDir,
      Optional<Path> relativeNativeLibsDir) {
    switch (starterType) {
      case PURE:
        if (relativeNativeLibsDir.isPresent()) {
          throw new HumanReadableException(
              "%s: cannot use pure starter with native libraries", baseParams.getBuildTarget());
        }
        return LuaScriptStarter.of(
            baseParams,
            ruleResolver,
            pathResolver,
            ruleFinder,
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
            ruleFinder,
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
            baseParams.getBuildTarget(), luaConfig.getStarterType()));
  }

  private StarterType getStarterType(boolean mayHaveNativeCode) {
    return luaConfig
        .getStarterType()
        .orElse(mayHaveNativeCode ? StarterType.NATIVE : StarterType.PURE);
  }

  /** @return the {@link Starter} used to build the Lua binary entry point. */
  private Starter createStarter(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      final CxxPlatform cxxPlatform,
      Optional<BuildTarget> nativeStarterLibrary,
      String mainModule,
      LuaConfig.PackageStyle packageStyle,
      boolean mayHaveNativeCode) {

    Path output = getOutputPath(baseParams.getBuildTarget(), baseParams.getProjectFilesystem());
    StarterType starterType = getStarterType(mayHaveNativeCode);

    // The relative paths from the starter to the various components.
    Optional<Path> relativeModulesDir = Optional.empty();
    Optional<Path> relativePythonModulesDir = Optional.empty();
    Optional<Path> relativeNativeLibsDir = Optional.empty();

    // For in-place binaries, set the relative paths to the symlink trees holding the components.
    if (packageStyle == LuaConfig.PackageStyle.INPLACE) {
      relativeModulesDir =
          Optional.of(
              output
                  .getParent()
                  .relativize(
                      getModulesSymlinkTreeRoot(
                          baseParams.getBuildTarget(), baseParams.getProjectFilesystem())));
      relativePythonModulesDir =
          Optional.of(
              output
                  .getParent()
                  .relativize(
                      getPythonModulesSymlinkTreeRoot(
                          baseParams.getBuildTarget(), baseParams.getProjectFilesystem())));

      // We only need to setup a native lib link tree if we're using a native starter.
      if (starterType == StarterType.NATIVE) {
        relativeNativeLibsDir =
            Optional.of(
                output
                    .getParent()
                    .relativize(
                        getNativeLibsSymlinkTreeRoot(
                            baseParams.getBuildTarget(), baseParams.getProjectFilesystem())));
      }
    }

    // Build the starter.
    return getStarter(
        baseParams,
        ruleResolver,
        pathResolver,
        ruleFinder,
        cxxPlatform,
        baseParams
            .getBuildTarget()
            .withAppendedFlavors(
                packageStyle == LuaConfig.PackageStyle.STANDALONE
                    ? InternalFlavor.of("starter")
                    : BINARY_FLAVOR),
        packageStyle == LuaConfig.PackageStyle.STANDALONE
            ? output.resolveSibling(output.getFileName() + "-starter")
            : output,
        starterType,
        nativeStarterLibrary,
        mainModule,
        relativeModulesDir,
        relativePythonModulesDir,
        relativeNativeLibsDir);
  }

  private LuaBinaryPackageComponents getPackageComponentsFromDeps(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      final CxxPlatform cxxPlatform,
      final PythonPlatform pythonPlatform,
      Optional<BuildTarget> nativeStarterLibrary,
      String mainModule,
      LuaConfig.PackageStyle packageStyle,
      Iterable<BuildRule> deps)
      throws NoSuchBuildTargetException {

    final LuaPackageComponents.Builder builder = LuaPackageComponents.builder();
    final OmnibusRoots.Builder omnibusRoots = OmnibusRoots.builder(cxxPlatform, ImmutableSet.of());

    final Map<BuildTarget, NativeLinkable> nativeLinkableRoots = new LinkedHashMap<>();
    final Map<BuildTarget, CxxLuaExtension> luaExtensions = new LinkedHashMap<>();
    final Map<BuildTarget, CxxPythonExtension> pythonExtensions = new LinkedHashMap<>();

    // Walk the deps to find all Lua packageables and native linkables.
    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(deps) {
      private final ImmutableSet<BuildRule> empty = ImmutableSet.of();

      @Override
      public Iterable<BuildRule> visit(BuildRule rule) throws NoSuchBuildTargetException {
        Iterable<BuildRule> deps = empty;
        if (rule instanceof LuaPackageable) {
          LuaPackageable packageable = (LuaPackageable) rule;
          LuaPackageComponents components = packageable.getLuaPackageComponents();
          LuaPackageComponents.addComponents(builder, components);
          if (components.hasNativeCode(cxxPlatform)) {
            for (BuildRule dep : rule.getBuildDeps()) {
              if (dep instanceof NativeLinkable) {
                NativeLinkable linkable = (NativeLinkable) dep;
                nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
                omnibusRoots.addExcludedRoot(linkable);
              }
            }
          }
          deps = rule.getBuildDeps();
        } else if (rule instanceof CxxPythonExtension) {
          CxxPythonExtension extension = (CxxPythonExtension) rule;
          NativeLinkTarget target = extension.getNativeLinkTarget(pythonPlatform);
          pythonExtensions.put(target.getBuildTarget(), (CxxPythonExtension) rule);
          omnibusRoots.addIncludedRoot(target);
        } else if (rule instanceof PythonPackagable) {
          PythonPackagable packageable = (PythonPackagable) rule;
          PythonPackageComponents components =
              packageable.getPythonPackageComponents(pythonPlatform, cxxPlatform);
          builder.putAllPythonModules(
              MoreMaps.transformKeys(components.getModules(), Object::toString));
          builder.putAllNativeLibraries(
              MoreMaps.transformKeys(components.getNativeLibraries(), Object::toString));
          deps = packageable.getPythonPackageDeps(pythonPlatform, cxxPlatform);
          if (components.hasNativeCode(cxxPlatform)) {
            for (BuildRule dep : deps) {
              if (dep instanceof NativeLinkable) {
                NativeLinkable linkable = (NativeLinkable) dep;
                nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
                omnibusRoots.addExcludedRoot(linkable);
              }
            }
          }
        } else if (rule instanceof CxxLuaExtension) {
          CxxLuaExtension extension = (CxxLuaExtension) rule;
          luaExtensions.put(extension.getBuildTarget(), extension);
          omnibusRoots.addIncludedRoot(extension);
        } else if (rule instanceof NativeLinkable) {
          NativeLinkable linkable = (NativeLinkable) rule;
          nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
          omnibusRoots.addPotentialRoot(linkable);
        }
        return deps;
      }
    }.start();

    // Build the starter.
    Starter starter =
        createStarter(
            baseParams,
            ruleResolver,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            nativeStarterLibrary,
            mainModule,
            packageStyle,
            !nativeLinkableRoots.isEmpty() || !omnibusRoots.isEmpty());
    SourcePath starterPath = null;

    if (luaConfig.getNativeLinkStrategy() == NativeLinkStrategy.MERGED) {

      // If we're using a native starter, include it in omnibus linking.
      if (starter instanceof NativeExecutableStarter) {
        NativeExecutableStarter nativeStarter = (NativeExecutableStarter) starter;
        omnibusRoots.addIncludedRoot(nativeStarter);
      }

      // Build the omnibus libraries.
      OmnibusRoots roots = omnibusRoots.build();
      OmnibusLibraries libraries =
          Omnibus.getSharedLibraries(
              baseParams,
              ruleResolver,
              ruleFinder,
              cxxBuckConfig,
              cxxPlatform,
              ImmutableList.of(),
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
              pythonExtension.getModule().toString(), root.getValue().getPath());
          continue;
        }

        // A root named after the top-level target is our native starter.
        if (root.getKey().equals(baseParams.getBuildTarget())) {
          starterPath = root.getValue().getPath();
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
                mode.getLibraryName().orElse(null),
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
                extension.getNativeLinkTargetDeps(cxxPlatform), NativeLinkable::getBuildTarget));
      }

      // Add in native executable deps.
      if (starter instanceof NativeExecutableStarter) {
        NativeExecutableStarter executableStarter = (NativeExecutableStarter) starter;
        nativeLinkableRoots.putAll(
            Maps.uniqueIndex(
                executableStarter.getNativeStarterDeps(), NativeLinkable::getBuildTarget));
      }

      // For regular linking, add all extensions via the package components interface and their
      // python-platform specific deps to the native linkables.
      for (Map.Entry<BuildTarget, CxxPythonExtension> entry : pythonExtensions.entrySet()) {
        PythonPackageComponents components =
            entry.getValue().getPythonPackageComponents(pythonPlatform, cxxPlatform);
        builder.putAllPythonModules(
            MoreMaps.transformKeys(components.getModules(), Object::toString));
        builder.putAllNativeLibraries(
            MoreMaps.transformKeys(components.getNativeLibraries(), Object::toString));
        nativeLinkableRoots.putAll(
            Maps.uniqueIndex(
                entry
                    .getValue()
                    .getNativeLinkTarget(pythonPlatform)
                    .getNativeLinkTargetDeps(cxxPlatform),
                NativeLinkable::getBuildTarget));
      }

      // Add shared libraries from all native linkables.
      for (NativeLinkable nativeLinkable :
          NativeLinkables.getTransitiveNativeLinkables(cxxPlatform, nativeLinkableRoots.values())
              .values()) {
        NativeLinkable.Linkage linkage = nativeLinkable.getPreferredLinkage(cxxPlatform);
        if (linkage != NativeLinkable.Linkage.STATIC) {
          builder.putAllNativeLibraries(nativeLinkable.getSharedLibraries(cxxPlatform));
        }
      }
    }

    // If an explicit starter path override hasn't been set (e.g. from omnibus linking), default to
    // building one directly from the starter.
    if (starterPath == null) {
      starterPath = starter.build();
    }

    return LuaBinaryPackageComponents.of(starterPath, builder.build());
  }

  private SymlinkTree createSymlinkTree(
      BuildTarget linkTreeTarget,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      Path root,
      ImmutableMap<String, SourcePath> components) {
    return resolver.addToIndex(
        new SymlinkTree(
            linkTreeTarget,
            filesystem,
            root,
            MoreMaps.transformKeys(components, MorePaths.toPathFn(root.getFileSystem())),
            ruleFinder));
  }

  /**
   * @return the native library map with additional entries for library names with the version
   *     suffix stripped (e.g. libfoo.so.1.0 -> libfoo.so) to appease LuaJIT, which wants to load
   *     libraries using the build-time name.
   */
  private ImmutableSortedMap<String, SourcePath> addVersionLessLibraries(
      CxxPlatform cxxPlatform, ImmutableSortedMap<String, SourcePath> libraries) {
    Pattern versionedExtension =
        Pattern.compile(
            Joiner.on("[.\\d]*")
                .join(
                    Iterables.transform(
                        Splitter.on("%s")
                            .split(cxxPlatform.getSharedLibraryVersionedExtensionFormat()),
                        input -> input.isEmpty() ? input : Pattern.quote(input))));
    Map<String, SourcePath> librariesPaths = new HashMap<>();
    for (Map.Entry<String, SourcePath> ent : libraries.entrySet()) {
      String name = ent.getKey();

      if (librariesPaths.containsKey(name) && librariesPaths.get(name) != ent.getValue()) {
        throw new HumanReadableException(
            "Library %s has multiple possible paths: %s and %s",
            name, ent.getValue(), librariesPaths.get(name));
      }

      librariesPaths.put(name, ent.getValue());
      Matcher matcher = versionedExtension.matcher(name);
      String versionLessName = matcher.replaceAll(cxxPlatform.getSharedLibraryExtension());
      if (!versionLessName.equals(ent.getKey()) && !libraries.containsKey(versionLessName)) {
        librariesPaths.put(versionLessName, ent.getValue());
      }
    }
    return ImmutableSortedMap.copyOf(librariesPaths);
  }

  private Tool getInPlaceBinary(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      final SourcePath starter,
      final LuaPackageComponents components) {
    final List<SourcePath> extraInputs = new ArrayList<>();

    final SymlinkTree modulesLinkTree =
        resolver.addToIndex(
            createSymlinkTree(
                getModulesSymlinkTreeTarget(params.getBuildTarget()),
                params.getProjectFilesystem(),
                resolver,
                ruleFinder,
                getModulesSymlinkTreeRoot(params.getBuildTarget(), params.getProjectFilesystem()),
                components.getModules()));

    final List<SymlinkTree> pythonModulesLinktree = new ArrayList<>();
    if (!components.getPythonModules().isEmpty()) {
      // Add in any missing init modules into the python components.
      SourcePath emptyInit = PythonBinaryDescription.createEmptyInitModule(params, resolver);
      extraInputs.add(emptyInit);
      ImmutableMap<String, SourcePath> pythonModules =
          MoreMaps.transformKeys(
              PythonBinaryDescription.addMissingInitModules(
                  MoreMaps.transformKeys(
                      components.getPythonModules(),
                      MorePaths.toPathFn(
                          params.getProjectFilesystem().getRootPath().getFileSystem())),
                  emptyInit),
              Object::toString);
      final SymlinkTree symlinkTree =
          resolver.addToIndex(
              createSymlinkTree(
                  getPythonModulesSymlinkTreeTarget(params.getBuildTarget()),
                  params.getProjectFilesystem(),
                  resolver,
                  ruleFinder,
                  getPythonModulesSymlinkTreeRoot(
                      params.getBuildTarget(), params.getProjectFilesystem()),
                  pythonModules));
      pythonModulesLinktree.add(symlinkTree);
    }

    final List<SymlinkTree> nativeLibsLinktree = new ArrayList<>();
    if (!components.getNativeLibraries().isEmpty()) {
      SymlinkTree symlinkTree =
          resolver.addToIndex(
              createSymlinkTree(
                  getNativeLibsSymlinkTreeTarget(params.getBuildTarget()),
                  params.getProjectFilesystem(),
                  resolver,
                  ruleFinder,
                  getNativeLibsSymlinkTreeRoot(
                      params.getBuildTarget(), params.getProjectFilesystem()),
                  addVersionLessLibraries(cxxPlatform, components.getNativeLibraries())));
      nativeLibsLinktree.add(symlinkTree);
    }

    return new Tool() {

      @Override
      public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
        return ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(ruleFinder.filterBuildRuleInputs(starter))
            .addAll(components.getDeps(ruleFinder))
            .add(modulesLinkTree)
            .addAll(nativeLibsLinktree)
            .addAll(pythonModulesLinktree)
            .addAll(ruleFinder.filterBuildRuleInputs(extraInputs))
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
        sink.setReflectively("starter", starter).setReflectively("components", components);
      }
    };
  }

  private Tool getStandaloneBinary(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      SourcePath starter,
      String mainModule,
      final LuaPackageComponents components) {
    Path output = getOutputPath(params.getBuildTarget(), params.getProjectFilesystem());

    Tool lua = luaConfig.getLua(resolver);
    Tool packager = luaConfig.getPackager().resolve(resolver);

    LuaStandaloneBinary binary =
        resolver.addToIndex(
            new LuaStandaloneBinary(
                params
                    .withAppendedFlavor(BINARY_FLAVOR)
                    .copyReplacingDeclaredAndExtraDeps(
                        Suppliers.ofInstance(
                            ImmutableSortedSet.<BuildRule>naturalOrder()
                                .addAll(ruleFinder.filterBuildRuleInputs(starter))
                                .addAll(components.getDeps(ruleFinder))
                                .addAll(lua.getDeps(ruleFinder))
                                .addAll(packager.getDeps(ruleFinder))
                                .build()),
                        Suppliers.ofInstance(ImmutableSortedSet.of())),
                packager,
                ImmutableList.of(),
                output,
                Optional.of(starter),
                components,
                mainModule,
                lua,
                luaConfig.shouldCacheBinaries()));

    return new CommandTool.Builder()
        .addArg(SourcePathArg.of(binary.getSourcePathToOutput()))
        .build();
  }

  private Tool getBinary(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      String mainModule,
      SourcePath starter,
      final LuaPackageComponents components,
      LuaConfig.PackageStyle packageStyle) {
    switch (packageStyle) {
      case STANDALONE:
        return getStandaloneBinary(params, resolver, ruleFinder, starter, mainModule, components);
      case INPLACE:
        return getInPlaceBinary(params, resolver, ruleFinder, cxxPlatform, starter, components);
    }
    throw new IllegalStateException(
        String.format("%s: unexpected package style %s", params.getBuildTarget(), packageStyle));
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      LuaBinaryDescriptionArg args)
      throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    CxxPlatform cxxPlatform =
        cxxPlatforms.getValue(params.getBuildTarget()).orElse(defaultCxxPlatform);
    PythonPlatform pythonPlatform =
        pythonPlatforms
            .getValue(params.getBuildTarget())
            .orElse(
                pythonPlatforms.getValue(
                    args.getPythonPlatform()
                        .<Flavor>map(InternalFlavor::of)
                        .orElse(pythonPlatforms.getFlavors().iterator().next())));
    LuaBinaryPackageComponents components =
        getPackageComponentsFromDeps(
            params,
            resolver,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            pythonPlatform,
            args.getNativeStarterLibrary()
                .map(Optional::of)
                .orElse(luaConfig.getNativeStarterLibrary()),
            args.getMainModule(),
            args.getPackageStyle().orElse(luaConfig.getPackageStyle()),
            params.getDeclaredDeps().get());
    LuaConfig.PackageStyle packageStyle =
        args.getPackageStyle().orElse(luaConfig.getPackageStyle());
    Tool binary =
        getBinary(
            params,
            resolver,
            ruleFinder,
            cxxPlatform,
            args.getMainModule(),
            components.getStarter(),
            components.getComponents(),
            packageStyle);
    return new LuaBinary(
        params.copyAppendingExtraDeps(binary.getDeps(ruleFinder)),
        ruleFinder,
        getOutputPath(params.getBuildTarget(), params.getProjectFilesystem()),
        binary,
        args.getMainModule(),
        components.getComponents(),
        luaConfig.getLua(resolver),
        packageStyle);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractLuaBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (luaConfig.getPackageStyle() == LuaConfig.PackageStyle.STANDALONE) {
      extraDepsBuilder.addAll(luaConfig.getPackager().getParseTimeDeps());
    }
    extraDepsBuilder.addAll(getNativeStarterDepTargets());
  }

  @Override
  public boolean isVersionRoot(ImmutableSet<Flavor> flavors) {
    return true;
  }

  public enum StarterType {
    PURE,
    NATIVE,
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractLuaBinaryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    String getMainModule();

    Optional<BuildTarget> getNativeStarterLibrary();

    Optional<String> getPythonPlatform();

    Optional<LuaConfig.PackageStyle> getPackageStyle();
  }
}
