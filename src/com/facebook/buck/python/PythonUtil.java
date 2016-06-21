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

import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.NativeLinkTarget;
import com.facebook.buck.cxx.NativeLinkTargetMode;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.cxx.OmnibusLibraries;
import com.facebook.buck.cxx.OmnibusLibrary;
import com.facebook.buck.cxx.OmnibusRoot;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.Omnibus;
import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class PythonUtil {

  protected static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.<String, MacroExpander>of(
              "location", new LocationMacroExpander()));

  private PythonUtil() {}

  public static ImmutableMap<Path, SourcePath> toModuleMap(
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
            resolver.getSourcePathNames(
                target,
                parameter,
                input.getUnnamedSources().get());
      } else {
        namesAndSourcePaths = input.getNamedSources().get();
      }
      for (ImmutableMap.Entry<String, SourcePath> entry : namesAndSourcePaths.entrySet()) {
        moduleNamesAndSourcePaths.put(
            baseModule.resolve(entry.getKey()),
            entry.getValue());
      }
    }

    return moduleNamesAndSourcePaths.build();
  }

  /** Convert a path to a module to it's module name as referenced in import statements. */
  public static String toModuleName(BuildTarget target, String name) {
    int ext = name.lastIndexOf('.');
    if (ext == -1) {
      throw new HumanReadableException(
          "%s: missing extension for module path: %s",
          target,
          name);
    }
    name = name.substring(0, ext);
    return MorePaths.pathWithUnixSeparators(name).replace('/', '.');
  }

  public static ImmutableSortedSet<BuildRule> getDepsFromComponents(
      SourcePathResolver resolver,
      PythonPackageComponents components) {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(resolver.filterBuildRuleInputs(components.getModules().values()))
        .addAll(resolver.filterBuildRuleInputs(components.getResources().values()))
        .addAll(resolver.filterBuildRuleInputs(components.getNativeLibraries().values()))
        .addAll(resolver.filterBuildRuleInputs(components.getPrebuiltLibraries()))
        .build();
  }

  private static boolean hasNativeCode(
      CxxPlatform cxxPlatform,
      PythonPackageComponents components) {
    for (Path module : components.getModules().keySet()) {
      if (module.toString().endsWith(cxxPlatform.getSharedLibraryExtension())) {
        return true;
      }
    }
    return false;
  }

  public static PythonPackageComponents getAllComponents(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      final PythonPackageComponents packageComponents,
      final PythonPlatform pythonPlatform,
      CxxBuckConfig cxxBuckConfig,
      final CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      final NativeLinkStrategy nativeLinkStrategy,
      final ImmutableSet<BuildTarget> preloadDeps)
      throws NoSuchBuildTargetException {

    final PythonPackageComponents.Builder allComponents =
        new PythonPackageComponents.Builder(params.getBuildTarget());

    final Map<BuildTarget, CxxPythonExtension> extensions = new LinkedHashMap<>();
    final Map<BuildTarget, NativeLinkTarget> nativeLinkTargetRoots = new LinkedHashMap<>();
    final Map<BuildTarget, NativeLinkable> nativeLinkableRoots = new LinkedHashMap<>();
    final Set<BuildTarget> excludedNativeLinkableRoots = new LinkedHashSet<>();

    // Add the top-level components.
    allComponents.addComponent(packageComponents, params.getBuildTarget());

    // Walk all our transitive deps to build our complete package that we'll
    // turn into an executable.
    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(
        params.getDeps()) {
      private final ImmutableList<BuildRule> empty = ImmutableList.of();
      @Override
      public Iterable<BuildRule> visit(BuildRule rule) throws NoSuchBuildTargetException {
        Iterable<BuildRule> deps = empty;
        Optional<NativeLinkTarget> linkTarget =
            nativeLinkStrategy == NativeLinkStrategy.MERGED ?
                NativeLinkables.getNativeLinkTarget(rule, cxxPlatform) :
                Optional.<NativeLinkTarget>absent();
        if (rule instanceof CxxPythonExtension) {
          extensions.put(rule.getBuildTarget(), (CxxPythonExtension) rule);
        } else if (rule instanceof PythonPackagable) {
          PythonPackagable packagable = (PythonPackagable) rule;
          PythonPackageComponents comps =
              packagable.getPythonPackageComponents(pythonPlatform, cxxPlatform);
          allComponents.addComponent(comps, rule.getBuildTarget());
          if (hasNativeCode(cxxPlatform, comps)) {
            for (BuildRule dep : rule.getDeps()) {
              if (dep instanceof NativeLinkable) {
                NativeLinkable linkable = (NativeLinkable) dep;
                excludedNativeLinkableRoots.add(linkable.getBuildTarget());
                nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
              }
            }
          }
          deps = rule.getDeps();
        } else if (linkTarget.isPresent() && !preloadDeps.contains(rule.getBuildTarget())) {
          nativeLinkTargetRoots.put(linkTarget.get().getBuildTarget(), linkTarget.get());
        } else if (rule instanceof NativeLinkable) {
          nativeLinkableRoots.put(rule.getBuildTarget(), (NativeLinkable) rule);
        }
        return deps;
      }
    }.start();

    // For the merged strategy, build up the lists of included native linkable roots, and the
    // excluded native linkable roots.
    if (nativeLinkStrategy == NativeLinkStrategy.MERGED) {

      // Recursively expand the excluded nodes including any preloaded deps, as we'll need this full
      // list to know which roots to exclude from omnibus linking.
      final Set<BuildTarget> transitivelyExcludedNativeLinkables = new HashSet<>();
      new AbstractBreadthFirstTraversal<NativeLinkable>(
          Iterables.transform(excludedNativeLinkableRoots, Functions.forMap(nativeLinkableRoots))) {
        @Override
        public Iterable<NativeLinkable> visit(NativeLinkable linkable) {
          transitivelyExcludedNativeLinkables.add(linkable.getBuildTarget());
          return Iterables.concat(
              linkable.getNativeLinkableDeps(cxxPlatform),
              linkable.getNativeLinkableExportedDeps(cxxPlatform));
        }
      }.start();

      // Include all native link targets we found which aren't explicitly excluded.
      Map<BuildTarget, NativeLinkTarget> includedNativeLinkTargetRoots =
          new LinkedHashMap<>();
      for (Map.Entry<BuildTarget, NativeLinkTarget> ent : nativeLinkTargetRoots.entrySet()) {
        if (!transitivelyExcludedNativeLinkables.contains(ent.getKey())) {
          includedNativeLinkTargetRoots.put(ent.getKey(), ent.getValue());
        }
      }

      // All C/C++ python extensions are included native link targets.
      Map<BuildTarget, CxxPythonExtension> includedExtensions = new LinkedHashMap<>();
      for (CxxPythonExtension extension : extensions.values()) {
        NativeLinkTarget target = extension.getNativeLinkTarget(pythonPlatform);
        includedExtensions.put(target.getBuildTarget(), extension);
        includedNativeLinkTargetRoots.put(target.getBuildTarget(), target);
      }

      OmnibusLibraries libraries =
          Omnibus.getSharedLibraries(
              params,
              ruleResolver,
              pathResolver,
              cxxBuckConfig,
              cxxPlatform,
              extraLdflags,
              includedNativeLinkTargetRoots.values(),
              Maps.filterKeys(
                  nativeLinkableRoots,
                  Predicates.not(Predicates.in(includedNativeLinkTargetRoots.keySet()))).values());

      // Add all the roots from the omnibus link.  If it's an extension, add it as a module.
      // Otherwise, add it as a native library.
      for (Map.Entry<BuildTarget, OmnibusRoot> root : libraries.getRoots().entrySet()) {
        CxxPythonExtension extension = includedExtensions.get(root.getKey());
        if (extension != null) {
          allComponents.addModule(extension.getModule(), root.getValue().getPath(), root.getKey());
        } else {
          NativeLinkTarget target =
              Preconditions.checkNotNull(
                  nativeLinkTargetRoots.get(root.getKey()),
                  "%s: linked unexpected omnibus root: %s",
                  params.getBuildTarget(),
                  root.getKey());
          NativeLinkTargetMode mode = target.getNativeLinkTargetMode(cxxPlatform);
          String soname =
              Preconditions.checkNotNull(
                  mode.getLibraryName().orNull(),
                  "%s: omnibus library for %s was built without soname",
                  params.getBuildTarget(),
                  root.getKey());
          allComponents.addNativeLibraries(
              Paths.get(soname),
              root.getValue().getPath(),
              root.getKey());
        }
      }

      // Add all remaining libraries as native libraries.
      for (OmnibusLibrary library : libraries.getLibraries()) {
        allComponents.addNativeLibraries(
            Paths.get(library.getSoname()),
            library.getPath(),
            params.getBuildTarget());
      }
    } else {

      // For regular linking, add all extensions via the package components interface.
      Map<BuildTarget, NativeLinkable> extensionNativeDeps = new LinkedHashMap<>();
      for (Map.Entry<BuildTarget, CxxPythonExtension> entry : extensions.entrySet()) {
        allComponents.addComponent(
            entry.getValue().getPythonPackageComponents(pythonPlatform, cxxPlatform),
            entry.getKey());
        extensionNativeDeps.putAll(
            Maps.uniqueIndex(
                entry.getValue().getNativeLinkTarget(pythonPlatform)
                    .getNativeLinkTargetDeps(cxxPlatform),
                HasBuildTarget.TO_TARGET));
      }

      // Add all the native libraries.
      ImmutableMap<BuildTarget, NativeLinkable> nativeLinkables =
          NativeLinkables.getTransitiveNativeLinkables(
              cxxPlatform,
              Iterables.concat(nativeLinkableRoots.values(), extensionNativeDeps.values()));
      for (NativeLinkable nativeLinkable : nativeLinkables.values()) {
        NativeLinkable.Linkage linkage = nativeLinkable.getPreferredLinkage(cxxPlatform);
        if (nativeLinkableRoots.containsKey(nativeLinkable.getBuildTarget()) ||
            linkage != NativeLinkable.Linkage.STATIC) {
          ImmutableMap<String, SourcePath> libs = nativeLinkable.getSharedLibraries(cxxPlatform);
          for (Map.Entry<String, SourcePath> ent : libs.entrySet()) {
            allComponents.addNativeLibraries(
                Paths.get(ent.getKey()),
                ent.getValue(),
                nativeLinkable.getBuildTarget());
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

  public static ImmutableSet<String> getPreloadNames(
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      Iterable<BuildTarget> preloadDeps)
      throws NoSuchBuildTargetException {
    ImmutableSet.Builder<String> builder = ImmutableSortedSet.naturalOrder();
    for (NativeLinkable nativeLinkable :
         FluentIterable.from(preloadDeps)
             .transform(resolver.getRuleFunction())
             .filter(NativeLinkable.class)) {
      builder.addAll(nativeLinkable.getSharedLibraries(cxxPlatform).keySet());
    }
    return builder.build();
  }

}
