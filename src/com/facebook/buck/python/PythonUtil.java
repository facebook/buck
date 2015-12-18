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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class PythonUtil {

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

  public static PythonPackageComponents getAllComponents(
      BuildRuleParams params,
      PythonPackageComponents packageComponents,
      final PythonPlatform pythonPlatform,
      final CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {

    final PythonPackageComponents.Builder components =
        new PythonPackageComponents.Builder(params.getBuildTarget());

    // Add components from our self.
    components.addComponent(packageComponents, params.getBuildTarget());

    // Walk all our transitive deps to build our complete package that we'll
    // turn into an executable.
    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(
        params.getDeps()) {
      @Override
      public ImmutableSortedSet<BuildRule> visit(BuildRule rule) throws NoSuchBuildTargetException {
        // We only process and recurse on instances of PythonPackagable.
        if (rule instanceof PythonPackagable) {
          PythonPackagable lib = (PythonPackagable) rule;

          // Add all components from the python packable into our top-level
          // package.
          components.addComponent(
              lib.getPythonPackageComponents(pythonPlatform, cxxPlatform),
              rule.getBuildTarget());

          // Return all our deps to recurse on them.
          return FluentIterable.from(rule.getDeps())
              .filter(Predicates.instanceOf(PythonPackagable.class))
              .toSortedSet(Ordering.natural());
        }

        // Don't recurse on anything from other rules.
        return ImmutableSortedSet.of();
      }
    }.start();

    ImmutableMap<String, SourcePath> sharedLibs =
        NativeLinkables.getTransitiveSharedLibraries(
            cxxPlatform,
            params.getDeps(),
            Predicates.instanceOf(PythonPackagable.class));
    for (Map.Entry<String, SourcePath> ent : sharedLibs.entrySet()) {
      components.addNativeLibraries(
          Paths.get(ent.getKey()),
          ent.getValue(),
          params.getBuildTarget());
    }

    return components.build();
  }

  public static Path getBasePath(BuildTarget target, Optional<String> override) {
    return override.isPresent()
        ? Paths.get(override.get().replace('.', '/'))
        : target.getBasePath();
  }

}
