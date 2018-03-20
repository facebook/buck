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

package com.facebook.buck.python;

import com.facebook.buck.cxx.CxxGenruleDescription;
import com.facebook.buck.cxx.Omnibus;
import com.facebook.buck.cxx.OmnibusLibraries;
import com.facebook.buck.cxx.OmnibusLibrary;
import com.facebook.buck.cxx.OmnibusRoot;
import com.facebook.buck.cxx.OmnibusRoots;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.python.toolchain.PythonPlatform;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.rules.macros.AbstractMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.Version;
import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PythonUtil {

  static final ImmutableList<AbstractMacroExpander<? extends Macro, ?>> MACRO_EXPANDERS =
      ImmutableList.of(new LocationMacroExpander());

  private PythonUtil() {}

  public static ImmutableList<BuildTarget> getDeps(
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<BuildTarget> deps,
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> platformDeps) {
    return RichStream.<BuildTarget>empty()
        .concat(deps.stream())
        .concat(
            platformDeps
                .getMatchingValues(pythonPlatform.getFlavor().toString())
                .stream()
                .flatMap(Collection::stream))
        .concat(
            platformDeps
                .getMatchingValues(cxxPlatform.getFlavor().toString())
                .stream()
                .flatMap(Collection::stream))
        .toImmutableList();
  }

  public static ImmutableMap<Path, SourcePath> getModules(
      BuildTarget target,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      String parameter,
      Path baseModule,
      SourceList items,
      PatternMatchedCollection<SourceList> platformItems,
      Optional<VersionMatchedCollection<SourceList>> versionItems,
      Optional<ImmutableMap<BuildTarget, Version>> versions) {
    return CxxGenruleDescription.fixupSourcePaths(
        resolver,
        ruleFinder,
        cxxPlatform,
        ImmutableMap.<Path, SourcePath>builder()
            .putAll(
                PythonUtil.toModuleMap(
                    target, pathResolver, parameter, baseModule, ImmutableList.of(items)))
            .putAll(
                PythonUtil.toModuleMap(
                    target,
                    pathResolver,
                    "platform" + CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_CAMEL, parameter),
                    baseModule,
                    platformItems.getMatchingValues(pythonPlatform.getFlavor().toString())))
            .putAll(
                PythonUtil.toModuleMap(
                    target,
                    pathResolver,
                    "versioned" + CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_CAMEL, parameter),
                    baseModule,
                    versions.isPresent() && versionItems.isPresent()
                        ? versionItems.get().getMatchingValues(versions.get())
                        : ImmutableList.of()))
            .build());
  }

  static ImmutableMap<Path, SourcePath> toModuleMap(
      BuildTarget target,
      SourcePathResolver resolver,
      String parameter,
      Path baseModule,
      Iterable<SourceList> inputs) {

    ImmutableMap.Builder<Path, SourcePath> moduleNamesAndSourcePaths = ImmutableMap.builder();

    for (SourceList input : inputs) {
      ImmutableMap<String, SourcePath> namesAndSourcePaths;
      if (input.getUnnamedSources().isPresent()) {
        namesAndSourcePaths =
            resolver.getSourcePathNames(target, parameter, input.getUnnamedSources().get());
      } else {
        namesAndSourcePaths = input.getNamedSources().get();
      }
      for (ImmutableMap.Entry<String, SourcePath> entry : namesAndSourcePaths.entrySet()) {
        moduleNamesAndSourcePaths.put(baseModule.resolve(entry.getKey()), entry.getValue());
      }
    }

    return moduleNamesAndSourcePaths.build();
  }

  /** Convert a path to a module to it's module name as referenced in import statements. */
  static String toModuleName(BuildTarget target, String name) {
    int ext = name.lastIndexOf('.');
    if (ext == -1) {
      throw new HumanReadableException("%s: missing extension for module path: %s", target, name);
    }
    name = name.substring(0, ext);
    return MorePaths.pathWithUnixSeparators(name).replace('/', '.');
  }

  static PythonPackageComponents getAllComponents(
      CellPathResolver cellPathResolver,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      Iterable<BuildRule> deps,
      PythonPackageComponents packageComponents,
      PythonPlatform pythonPlatform,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      NativeLinkStrategy nativeLinkStrategy,
      ImmutableSet<BuildTarget> preloadDeps) {

    PythonPackageComponents.Builder allComponents =
        new PythonPackageComponents.Builder(buildTarget);

    Map<BuildTarget, CxxPythonExtension> extensions = new LinkedHashMap<>();
    Map<BuildTarget, NativeLinkable> nativeLinkableRoots = new LinkedHashMap<>();

    OmnibusRoots.Builder omnibusRoots =
        OmnibusRoots.builder(cxxPlatform, preloadDeps, ruleResolver);

    // Add the top-level components.
    allComponents.addComponent(packageComponents, buildTarget);

    // Walk all our transitive deps to build our complete package that we'll
    // turn into an executable.
    new AbstractBreadthFirstTraversal<BuildRule>(
        Iterables.concat(deps, ruleResolver.getAllRules(preloadDeps))) {
      private final ImmutableList<BuildRule> empty = ImmutableList.of();

      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        Iterable<BuildRule> deps = empty;
        if (rule instanceof CxxPythonExtension) {
          CxxPythonExtension extension = (CxxPythonExtension) rule;
          NativeLinkTarget target = ((CxxPythonExtension) rule).getNativeLinkTarget(pythonPlatform);
          extensions.put(target.getBuildTarget(), extension);
          omnibusRoots.addIncludedRoot(target);
          List<BuildRule> cxxpydeps = new ArrayList<>();
          for (BuildRule dep :
              extension.getPythonPackageDeps(pythonPlatform, cxxPlatform, ruleResolver)) {
            if (dep instanceof PythonPackagable) {
              cxxpydeps.add(dep);
            }
          }
          deps = cxxpydeps;
        } else if (rule instanceof PythonPackagable) {
          PythonPackagable packagable = (PythonPackagable) rule;
          PythonPackageComponents comps =
              packagable.getPythonPackageComponents(pythonPlatform, cxxPlatform, ruleResolver);
          allComponents.addComponent(comps, rule.getBuildTarget());
          if (comps.hasNativeCode(cxxPlatform)) {
            for (BuildRule dep :
                packagable.getPythonPackageDeps(pythonPlatform, cxxPlatform, ruleResolver)) {
              if (dep instanceof NativeLinkable) {
                NativeLinkable linkable = (NativeLinkable) dep;
                nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
                omnibusRoots.addExcludedRoot(linkable);
              }
            }
          }
          deps = packagable.getPythonPackageDeps(pythonPlatform, cxxPlatform, ruleResolver);
        } else if (rule instanceof NativeLinkable) {
          NativeLinkable linkable = (NativeLinkable) rule;
          nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
          omnibusRoots.addPotentialRoot(linkable);
        }
        return deps;
      }
    }.start();

    // For the merged strategy, build up the lists of included native linkable roots, and the
    // excluded native linkable roots.
    if (nativeLinkStrategy == NativeLinkStrategy.MERGED) {
      OmnibusRoots roots = omnibusRoots.build();
      OmnibusLibraries libraries =
          Omnibus.getSharedLibraries(
              buildTarget,
              projectFilesystem,
              params,
              cellPathResolver,
              ruleResolver,
              ruleFinder,
              cxxBuckConfig,
              cxxPlatform,
              extraLdflags,
              roots.getIncludedRoots().values(),
              roots.getExcludedRoots().values());

      // Add all the roots from the omnibus link.  If it's an extension, add it as a module.
      // Otherwise, add it as a native library.
      for (Map.Entry<BuildTarget, OmnibusRoot> root : libraries.getRoots().entrySet()) {
        CxxPythonExtension extension = extensions.get(root.getKey());
        if (extension != null) {
          allComponents.addModule(extension.getModule(), root.getValue().getPath(), root.getKey());
        } else {
          NativeLinkTarget target =
              Preconditions.checkNotNull(
                  roots.getIncludedRoots().get(root.getKey()),
                  "%s: linked unexpected omnibus root: %s",
                  buildTarget,
                  root.getKey());
          NativeLinkTargetMode mode = target.getNativeLinkTargetMode(cxxPlatform);
          String soname =
              Preconditions.checkNotNull(
                  mode.getLibraryName().orElse(null),
                  "%s: omnibus library for %s was built without soname",
                  buildTarget,
                  root.getKey());
          allComponents.addNativeLibraries(
              Paths.get(soname), root.getValue().getPath(), root.getKey());
        }
      }

      // Add all remaining libraries as native libraries.
      for (OmnibusLibrary library : libraries.getLibraries()) {
        allComponents.addNativeLibraries(
            Paths.get(library.getSoname()), library.getPath(), buildTarget);
      }
    } else {

      // For regular linking, add all extensions via the package components interface.
      Map<BuildTarget, NativeLinkable> extensionNativeDeps = new LinkedHashMap<>();
      for (Map.Entry<BuildTarget, CxxPythonExtension> entry : extensions.entrySet()) {
        allComponents.addComponent(
            entry.getValue().getPythonPackageComponents(pythonPlatform, cxxPlatform, ruleResolver),
            entry.getValue().getBuildTarget());
        extensionNativeDeps.putAll(
            Maps.uniqueIndex(
                entry
                    .getValue()
                    .getNativeLinkTarget(pythonPlatform)
                    .getNativeLinkTargetDeps(cxxPlatform, ruleResolver),
                NativeLinkable::getBuildTarget));
      }

      // Add all the native libraries.
      ImmutableMap<BuildTarget, NativeLinkable> nativeLinkables =
          NativeLinkables.getTransitiveNativeLinkables(
              cxxPlatform,
              ruleResolver,
              Iterables.concat(nativeLinkableRoots.values(), extensionNativeDeps.values()));
      for (NativeLinkable nativeLinkable : nativeLinkables.values()) {
        NativeLinkable.Linkage linkage =
            nativeLinkable.getPreferredLinkage(cxxPlatform, ruleResolver);
        if (nativeLinkableRoots.containsKey(nativeLinkable.getBuildTarget())
            || linkage != NativeLinkable.Linkage.STATIC) {
          ImmutableMap<String, SourcePath> libs =
              nativeLinkable.getSharedLibraries(cxxPlatform, ruleResolver);
          for (Map.Entry<String, SourcePath> ent : libs.entrySet()) {
            allComponents.addNativeLibraries(
                Paths.get(ent.getKey()), ent.getValue(), nativeLinkable.getBuildTarget());
          }
        }
      }
    }

    return allComponents.build();
  }

  public static Path getBasePath(BuildTarget target, Optional<String> override) {
    return override.isPresent()
        ? Paths.get(override.get().replace('.', '/'))
        : target.getBasePath();
  }

  static ImmutableSet<String> getPreloadNames(
      BuildRuleResolver resolver, CxxPlatform cxxPlatform, Iterable<BuildTarget> preloadDeps) {
    ImmutableSet.Builder<String> builder = ImmutableSortedSet.naturalOrder();
    for (NativeLinkable nativeLinkable :
        FluentIterable.from(preloadDeps)
            .transform(resolver::getRule)
            .filter(NativeLinkable.class)) {
      builder.addAll(nativeLinkable.getSharedLibraries(cxxPlatform, resolver).keySet());
    }
    return builder.build();
  }
}
