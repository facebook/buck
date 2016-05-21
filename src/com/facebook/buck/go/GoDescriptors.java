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
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.common.io.Resources;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

abstract class GoDescriptors {

  private static final Logger LOG = Logger.get(GoDescriptors.class);

  private static final String TEST_MAIN_GEN_PATH = "testmaingen.go";
  public static final Flavor TRANSITIVE_LINKABLES_FLAVOR =
      ImmutableFlavor.of("transitive-linkables");

  @SuppressWarnings("unchecked")
  public static ImmutableSet<GoLinkable> requireTransitiveGoLinkables(
      final BuildTarget sourceTarget, final BuildRuleResolver resolver, final GoPlatform platform,
      Iterable<BuildTarget> targets, boolean includeSelf) throws NoSuchBuildTargetException {
    FluentIterable<GoLinkable> linkables = FluentIterable.from(targets)
        .transformAndConcat(new Function<BuildTarget, ImmutableSet<GoLinkable>>() {
          @Override
          public ImmutableSet<GoLinkable> apply(BuildTarget input) {
            BuildTarget flavoredTarget = BuildTarget.builder(input)
                .addFlavors(platform.getFlavor(), TRANSITIVE_LINKABLES_FLAVOR)
                .build();
            try {
              return resolver.requireMetadata(flavoredTarget, ImmutableSet.class).get();
            } catch (NoSuchBuildTargetException ex) {
              throw new RuntimeException(ex);
            }
          }
        });
    if (includeSelf) {
      Preconditions.checkArgument(sourceTarget.getFlavors().contains(TRANSITIVE_LINKABLES_FLAVOR));
      linkables = linkables.append(
          requireGoLinkable(
              sourceTarget,
              resolver,
              platform,
              sourceTarget.withoutFlavors(ImmutableSet.of(TRANSITIVE_LINKABLES_FLAVOR))));
    }
    return linkables.toSet();
  }

  static GoCompile createGoCompileRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      GoBuckConfig goBuckConfig,
      Path packageName,
      ImmutableSet<SourcePath> srcs,
      List<String> compilerFlags,
      List<String> assemblerFlags,
      GoPlatform platform,
      Iterable<BuildTarget> deps)
      throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    Preconditions.checkState(
        params.getBuildTarget().getFlavors().contains(platform.getFlavor()));

    ImmutableSet<GoLinkable> linkables = requireGoLinkables(
        params.getBuildTarget(),
        resolver,
        platform,
        deps);

    BuildTarget target = createSymlinkTreeTarget(params.getBuildTarget());
    SymlinkTree symlinkTree = makeSymlinkTree(
        params.copyWithBuildTarget(target),
        pathResolver,
        linkables);
    resolver.addToIndex(symlinkTree);

    LOG.verbose(
        "Symlink tree for compiling %s: %s",
        params.getBuildTarget(), symlinkTree.getLinks());

    return new GoCompile(
        params.appendExtraDeps(ImmutableList.of(symlinkTree)),
        pathResolver,
        symlinkTree,
        packageName,
        getPackageImportMap(goBuckConfig.getVendorPaths(),
            params.getBuildTarget().getBasePath(),
            FluentIterable.from(linkables).transformAndConcat(
              new Function<GoLinkable, ImmutableSet<Path>>() {
                @Override
                public ImmutableSet<Path> apply(GoLinkable input) {
                  return input.getGoLinkInput().keySet();
                }
              })),
        ImmutableSet.copyOf(srcs),
        ImmutableList.copyOf(compilerFlags),
        goBuckConfig.getCompiler(),
        ImmutableList.copyOf(assemblerFlags),
        goBuckConfig.getAssemblerIncludeDirs(),
        goBuckConfig.getAssembler(),
        goBuckConfig.getPacker(),
        platform);
  }

  @VisibleForTesting
  static ImmutableMap<Path, Path> getPackageImportMap(
      ImmutableList<Path> globalVendorPaths,
      Path basePackagePath, Iterable<Path> packageNameIter) {
    Map<Path, Path> importMapBuilder = Maps.newHashMap();
    ImmutableSortedSet<Path> packageNames = ImmutableSortedSet.copyOf(packageNameIter);

    ImmutableList.Builder<Path> vendorPathsBuilder = ImmutableList.builder();
    vendorPathsBuilder.addAll(globalVendorPaths);
    Path prefix = Paths.get("");
    for (Path component : FluentIterable.of(new Path[]{Paths.get("")}).append(basePackagePath)) {
      prefix = prefix.resolve(component);
      vendorPathsBuilder.add(prefix.resolve("vendor"));
    }

    for (Path vendorPrefix: vendorPathsBuilder.build()) {
      for (Path vendoredPackage : packageNames.tailSet(vendorPrefix)) {
        if (!vendoredPackage.startsWith(vendorPrefix)) {
          break;
        }

        importMapBuilder.put(MorePaths.relativize(vendorPrefix, vendoredPackage), vendoredPackage);
      }
    }

    return ImmutableMap.copyOf(importMapBuilder);
  }

  static GoBinary createGoBinaryRule(
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      GoBuckConfig goBuckConfig,
      ImmutableSet<SourcePath> srcs,
      List<String> compilerFlags,
      List<String> assemblerFlags,
      List<String> linkerFlags,
      GoPlatform platform) throws NoSuchBuildTargetException {
    BuildTarget libraryTarget =
        params.getBuildTarget().withAppendedFlavors(
            ImmutableFlavor.of("compile"), platform.getFlavor());
    GoCompile library = GoDescriptors.createGoCompileRule(
        params.copyWithBuildTarget(libraryTarget),
        resolver,
        goBuckConfig,
        Paths.get("main"),
        srcs,
        compilerFlags,
        assemblerFlags,
        platform,
        FluentIterable.from(params.getDeclaredDeps().get())
            .transform(HasBuildTarget.TO_TARGET));
    resolver.addToIndex(library);

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildTarget target = createTransitiveSymlinkTreeTarget(params.getBuildTarget());
    SymlinkTree symlinkTree = makeSymlinkTree(
        params.copyWithBuildTarget(target),
        pathResolver,
        requireTransitiveGoLinkables(
            params.getBuildTarget(),
            resolver,
            platform,
            FluentIterable.from(params.getDeclaredDeps().get()).transform(HasBuildTarget.TO_TARGET),
            /* includeSelf */ false));
    resolver.addToIndex(symlinkTree);

    LOG.verbose("Symlink tree for linking of %s: %s", params.getBuildTarget(), symlinkTree);

    return new GoBinary(
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(symlinkTree, library)),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        platform.getCxxPlatform().transform(
            new Function<CxxPlatform, Linker>() {
              @Override
              public Linker apply(CxxPlatform input) {
                return input.getLd().resolve(resolver);
              }
            }),
        symlinkTree,
        library,
        goBuckConfig.getLinker(),
        ImmutableList.copyOf(linkerFlags),
        platform);
  }

  static Tool getTestMainGenerator(
      GoBuckConfig goBuckConfig,
      BuildRuleParams sourceParams,
      BuildRuleResolver resolver,
      ProjectFilesystem projectFilesystem) throws NoSuchBuildTargetException {
    Optional<Tool> configTool = goBuckConfig.getGoTestMainGenerator(resolver);
    if (configTool.isPresent()) {
      return configTool.get();
    }

    // TODO(mikekap): Make a single test main gen, rather than one per test. The generator itself
    // doesn't vary per test.
    BuildTarget generatorTarget = sourceParams.getBuildTarget()
        .withFlavors(ImmutableFlavor.of("make-test-main-gen"));
    Optional<BuildRule> generator = resolver.getRuleOptional(generatorTarget);
    if (generator.isPresent()) {
      return ((BinaryBuildRule) generator.get()).getExecutableCommand();
    }

    BuildRuleParams params = sourceParams.copyWithChanges(
        generatorTarget,
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())
    );

    GoBinary binary = createGoBinaryRule(
        params,
        resolver,
        goBuckConfig,
        ImmutableSet.<SourcePath>of(new PathSourcePath(
                projectFilesystem,
                MorePaths.relativize(projectFilesystem.getRootPath(), extractTestMainGenerator()))),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        goBuckConfig.getDefaultPlatform());
    resolver.addToIndex(binary);
    return binary.getExecutableCommand();
  }

  private static Path extractTestMainGenerator() {
    final URL resourceURL =
        GoDescriptors.class.getResource(TEST_MAIN_GEN_PATH);
    Preconditions.checkNotNull(resourceURL);
    if ("file".equals(resourceURL.getProtocol())) {
      // When Buck is running from the repo, the file is actually already on disk, so no extraction
      // is necessary
      try {
        return Paths.get(resourceURL.toURI());
      } catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
    } else {
      // Running from a .pex file, extraction is required
      try {
        File tempFile = File.createTempFile("testmaingen", ".go");
        tempFile.deleteOnExit();

        Resources.copy(resourceURL, new FileOutputStream(tempFile));
        return tempFile.toPath();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static BuildTarget createSymlinkTreeTarget(BuildTarget source) {
    return BuildTarget.builder(source)
        .addFlavors(ImmutableFlavor.of("symlink-tree"))
        .build();
  }

  private static BuildTarget createTransitiveSymlinkTreeTarget(BuildTarget source) {
    return BuildTarget.builder(source)
        .addFlavors(ImmutableFlavor.of("transitive-symlink-tree"))
        .build();
  }

  private static GoLinkable requireGoLinkable(
      BuildTarget sourceRule, BuildRuleResolver resolver, GoPlatform platform, BuildTarget target)
      throws NoSuchBuildTargetException {
    Optional<GoLinkable> linkable = resolver.requireMetadata(
        BuildTarget.builder(target)
            .addFlavors(platform.getFlavor())
            .build(), GoLinkable.class);
    if (!linkable.isPresent()) {
      throw new HumanReadableException(
          "%s (needed for %s) is not an instance of go_library!",
          target.getFullyQualifiedName(),
          sourceRule.getFullyQualifiedName());
    }
    return linkable.get();
  }

  private static ImmutableSet<GoLinkable> requireGoLinkables(
      final BuildTarget sourceTarget,
      final BuildRuleResolver resolver,
      final GoPlatform platform,
      Iterable<BuildTarget> targets)
      throws NoSuchBuildTargetException {
    final ImmutableSet.Builder<GoLinkable> linkables = ImmutableSet.builder();
    new AbstractBreadthFirstThrowingTraversal<BuildTarget, NoSuchBuildTargetException>(targets) {
      @Override
      public Iterable<BuildTarget> visit(BuildTarget target) throws NoSuchBuildTargetException {
        GoLinkable linkable = requireGoLinkable(sourceTarget, resolver, platform, target);
        linkables.add(linkable);
        return linkable.getExportedDeps();
      }
    }.start();
    return linkables.build();
  }

  private static SymlinkTree makeSymlinkTree(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      ImmutableSet<GoLinkable> linkables) throws NoSuchBuildTargetException {
    ImmutableMap.Builder<Path, SourcePath> treeMapBuilder = ImmutableMap.builder();
    for (GoLinkable linkable : linkables) {
      for (Map.Entry<Path, SourcePath> linkInput : linkable.getGoLinkInput().entrySet()) {
        treeMapBuilder.put(
            getPathInSymlinkTree(pathResolver, linkInput.getKey(), linkInput.getValue()),
            linkInput.getValue());
      }
    }

    ImmutableMap<Path, SourcePath> treeMap;
    try {
      treeMap = treeMapBuilder.build();
    } catch (IllegalArgumentException ex) {
      throw new HumanReadableException(
          ex,
          "Multiple go targets have the same package name when compiling %s",
          params.getBuildTarget().getFullyQualifiedName());
    }

    params = params.appendExtraDeps(
        pathResolver.filterBuildRuleInputs(treeMap.values()));

    Path root = params.getBuildTarget().getCellPath().resolve(BuildTargets.getScratchPath(
        params.getProjectFilesystem(),
        params.getBuildTarget(),
        "__%s__tree"));

    return new SymlinkTree(params, pathResolver, root, treeMap);
  }

  /**
   * @return the path in the symlink tree as used by the compiler. This is usually the package
   *         name + '.a'.
   */
  private static Path getPathInSymlinkTree(
      SourcePathResolver resolver, Path goPackageName, SourcePath ruleOutput) {
    Path output = resolver.getRelativePath(ruleOutput);

    String extension = Files.getFileExtension(output.toString());
    return Paths.get(goPackageName.toString() + (extension.equals(
        "") ? "" : "." + extension));
  }
}
