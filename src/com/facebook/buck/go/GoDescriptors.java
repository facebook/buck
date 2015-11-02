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

package com.facebook.buck.go;

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.List;

abstract class GoDescriptors {
  static BuildTarget createSymlinkTreeTarget(BuildTarget source) {
    return BuildTarget.builder(source)
        .addFlavors(ImmutableFlavor.of("symlink-tree"))
        .build();
  }

  static BuildTarget createTransitiveSymlinkTreeTarget(BuildTarget source) {
    return BuildTarget.builder(source)
        .addFlavors(ImmutableFlavor.of("transitive-symlink-tree"))
        .build();
  }

  static GoSymlinkTree requireDirectSymlinkTreeRule(BuildRuleParams sourceParams,
      BuildRuleResolver resolver) {
    BuildTarget target = createSymlinkTreeTarget(sourceParams.getBuildTarget());
    Path root = BuildTargets.getScratchPath(target, "__%s__tree");
    GoSymlinkTree tree = new GoSymlinkTree(
        sourceParams.copyWithBuildTarget(target),
        new SourcePathResolver(resolver),
        root
    );
    resolver.addToIndex(tree);
    return tree;
  }

  private static ImmutableSortedSet<BuildRule> getDepTransitiveClosure(
      final BuildRuleParams params) {
    final ImmutableSortedSet.Builder<BuildRule> transitiveClosure =
      ImmutableSortedSet.naturalOrder();
    new AbstractBreadthFirstTraversal<BuildRule>(params.getDeps()) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (!(rule instanceof GoLinkable)) {
          throw new HumanReadableException(
              "%s (transitive dep of %s) is not an instance of go_library!",
              rule.getBuildTarget().getFullyQualifiedName(),
              params.getBuildTarget().getFullyQualifiedName());
        }

        ImmutableSet<BuildRule> deps = ((GoLinkable) rule).getDeclaredDeps();
        transitiveClosure.addAll(deps);
        return deps;
      }
    }.start();
    return transitiveClosure.build();
  }

  static GoSymlinkTree requireTransitiveSymlinkTreeRule(final BuildRuleParams sourceParams,
      BuildRuleResolver resolver) {
    BuildTarget target = createTransitiveSymlinkTreeTarget(sourceParams.getBuildTarget());
    Path root = BuildTargets.getScratchPath(target, "__%s__tree");

    GoSymlinkTree tree = new GoSymlinkTree(
        sourceParams.copyWithChanges(
            target,
            Suppliers.memoize(
                new Supplier<ImmutableSortedSet<BuildRule>>() {
                  @Override
                  public ImmutableSortedSet<BuildRule> get() {
                    return getDepTransitiveClosure(sourceParams);
                  }
                }),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())
        ),
        new SourcePathResolver(resolver),
        root
    );
    resolver.addToIndex(tree);
    return tree;
  }

  static GoLibrary createGoLibraryRule(BuildRuleParams params, BuildRuleResolver resolver,
      GoBuckConfig goBuckConfig, Path packageName, ImmutableSet<SourcePath> srcs,
      List<String> compilerFlags) {
    GoSymlinkTree symlinkTree = requireDirectSymlinkTreeRule(params, resolver);
    return new GoLibrary(
        params.copyWithDeps(
            params.getDeclaredDeps(),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(symlinkTree))
        ),
        new SourcePathResolver(resolver),
        symlinkTree,
        packageName,
        ImmutableSet.copyOf(srcs),
        ImmutableList.<String>builder()
            .addAll(goBuckConfig.getCompilerFlags())
            .addAll(compilerFlags)
            .build(),
        goBuckConfig.getGoCompiler().get());

  }
}
