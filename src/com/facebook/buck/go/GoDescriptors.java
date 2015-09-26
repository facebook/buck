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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Resources;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

abstract class GoDescriptors {

  private static final String TEST_MAIN_GEN_PATH = "testmaingen.go";

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

  static GoSymlinkTree createDirectSymlinkTreeRule(
      BuildRuleParams sourceParams,
      BuildRuleResolver resolver) {
    BuildTarget target = createSymlinkTreeTarget(sourceParams.getBuildTarget());
    GoSymlinkTree tree = new GoSymlinkTree(
        sourceParams.copyWithBuildTarget(target),
        new SourcePathResolver(resolver)
    );
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

  static GoSymlinkTree createTransitiveSymlinkTreeRule(
      final BuildRuleParams sourceParams,
      BuildRuleResolver resolver) {
    BuildTarget target = createTransitiveSymlinkTreeTarget(sourceParams.getBuildTarget());

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
        new SourcePathResolver(resolver)
    );
    return tree;
  }

  static GoLibrary createGoLibraryRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      GoBuckConfig goBuckConfig,
      Path packageName,
      ImmutableSet<SourcePath> srcs,
      List<String> compilerFlags,
      ImmutableSortedSet<BuildTarget> tests) {
    GoSymlinkTree symlinkTree = createDirectSymlinkTreeRule(params, resolver);
    resolver.addToIndex(symlinkTree);
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
        goBuckConfig.getGoCompiler().get(),
        tests);

  }

  static GoBinary createGoBinaryRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      GoBuckConfig goBuckConfig,
      CxxPlatform cxxPlatform,
      ImmutableSet<SourcePath> srcs,
      List<String> compilerFlags,
      List<String> linkerFlags) {
    BuildTarget libraryTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("compile"))
            .build();
    GoLibrary library = GoDescriptors.createGoLibraryRule(
        params.copyWithBuildTarget(libraryTarget),
        resolver,
        goBuckConfig,
        Paths.get("main"),
        srcs,
        compilerFlags,
        ImmutableSortedSet.<BuildTarget>of()
    );
    resolver.addToIndex(library);

    BuildRuleParams binaryParams = params.copyWithDeps(
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(library)),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())
    );

    GoSymlinkTree symlinkTree = createTransitiveSymlinkTreeRule(
        binaryParams,
        resolver);
    resolver.addToIndex(symlinkTree);

    return new GoBinary(
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(symlinkTree, library)),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        new SourcePathResolver(resolver),
        cxxPlatform.getLd(),
        symlinkTree,
        library,
        goBuckConfig.getGoLinker().get(),
        ImmutableList.<String>builder()
            .addAll(goBuckConfig.getLinkerFlags())
            .addAll(linkerFlags)
            .build());
  }

  private static Path extractTestMainGenerator() {
    final URL resourceURL =
        GoDescriptors.class.getResource(TEST_MAIN_GEN_PATH);
    Preconditions.checkNotNull(resourceURL);
    if ("file".equals(resourceURL.getProtocol())) {
      // When Buck is running from the repo, the jar is actually already on disk, so no extraction
      // is necessary
      try {
        return Paths.get(resourceURL.toURI());
      } catch (URISyntaxException e) {
        throw Throwables.propagate(e);
      }
    } else {
      // Running from a .pex file, extraction is required
      try {
        File tempFile = File.createTempFile("testmaingen", ".go");
        tempFile.deleteOnExit();

        Resources.copy(resourceURL, new FileOutputStream(tempFile));
        return tempFile.toPath();
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }
  }

  static Tool getTestMainGenerator(
      GoBuckConfig goBuckConfig,
      BuildRuleParams sourceParams,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      ProjectFilesystem projectFilesystem) {
    Optional<Tool> configTool = goBuckConfig.getGoTestMainGenerator(resolver);
    if (configTool.isPresent()) {
      return configTool.get();
    }

    // TODO(mikekap): Make a single test main gen, rather than one per test. The generator itself
    // doesn't vary per test.

    BuildRuleParams params = sourceParams.copyWithChanges(
        BuildTarget.builder(sourceParams.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("make-test-main-gen"))
            .build(),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())
    );

    GoBinary binary = createGoBinaryRule(
        params,
        resolver,
        goBuckConfig,
        cxxPlatform,
        ImmutableSet.<SourcePath>of(new PathSourcePath(
                projectFilesystem,
                MorePaths.relativize(projectFilesystem.getRootPath(), extractTestMainGenerator()))),
        ImmutableList.<String>of(),
        ImmutableList.<String>of()
    );
    resolver.addToIndex(binary);
    return binary.getExecutableCommand();
  }
}
