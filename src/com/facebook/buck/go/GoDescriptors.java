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

import com.facebook.buck.file.WriteFile;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

abstract class GoDescriptors {

  private static final Logger LOG = Logger.get(GoDescriptors.class);

  private static final String TEST_MAIN_GEN_PATH = "com/facebook/buck/go/testmaingen.go";

  public static ImmutableSet<GoLinkable> requireTransitiveGoLinkables(
      BuildRuleResolver resolver,
      Optional<GoLinkableTargetNode<?>> sourceTarget,
      final GoPlatform platform,
      Iterable<GoLinkableTargetNode<?>> targets) {
    FluentIterable<GoLinkable> linkables = FluentIterable.from(targets)
        .transformAndConcat(input -> input.getTransitiveLinkables(resolver, platform));
    if (sourceTarget.isPresent()) {
      linkables = linkables.append(sourceTarget.get().getLinkable(resolver, platform));
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
      Iterable<GoLinkableTargetNode<?>> deps) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    Preconditions.checkState(
        params.getBuildTarget().getFlavors().contains(platform.getFlavor()));

    ImmutableSet<GoLinkable> linkables = requireGoLinkables(
        resolver,
        platform,
        deps);

    ImmutableList.Builder<BuildRule> linkableDepsBuilder = ImmutableList.builder();
    for (GoLinkable linkable : linkables) {
      linkableDepsBuilder.addAll(linkable.getDeps(pathResolver));
    }
    ImmutableList<BuildRule> linkableDeps = linkableDepsBuilder.build();

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
        params
            .appendExtraDeps(linkableDeps)
            .appendExtraDeps(ImmutableList.of(symlinkTree)),
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
      BuildRuleResolver resolver,
      GoBuckConfig goBuckConfig,
      ImmutableSet<SourcePath> srcs,
      List<String> compilerFlags,
      List<String> assemblerFlags,
      List<String> linkerFlags,
      GoPlatform platform,
      Iterable<GoLinkableTargetNode<?>> deps) {
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
        deps);
    resolver.addToIndex(library);

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildTarget target = createTransitiveSymlinkTreeTarget(params.getBuildTarget());
    SymlinkTree symlinkTree = makeSymlinkTree(
        params.copyWithBuildTarget(target),
        pathResolver,
        requireTransitiveGoLinkables(
            resolver,
            Optional.empty(),
            platform,
            deps));
    resolver.addToIndex(symlinkTree);

    LOG.verbose("Symlink tree for linking of %s: %s", params.getBuildTarget(), symlinkTree);

    return new GoBinary(
        params.copyWithDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(pathResolver.filterBuildRuleInputs(symlinkTree.getLinks().values()))
                    .add(symlinkTree)
                    .add(library)
                    .build()),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        pathResolver,
        platform.getCxxPlatform().map(input -> input.getLd().resolve(resolver)),
        symlinkTree,
        library,
        goBuckConfig.getLinker(),
        ImmutableList.copyOf(linkerFlags),
        platform);
  }

  static Tool getTestMainGenerator(
      GoBuckConfig goBuckConfig,
      BuildRuleParams sourceParams,
      BuildRuleResolver resolver) {

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

    BuildTarget generatorSourceTarget =
        sourceParams.getBuildTarget()
            .withAppendedFlavors(ImmutableFlavor.of("test-main-gen-source"));
    WriteFile writeFile =
        resolver.addToIndex(
            new WriteFile(
                sourceParams.copyWithChanges(
                    generatorSourceTarget,
                    Suppliers.ofInstance(ImmutableSortedSet.of()),
                    Suppliers.ofInstance(ImmutableSortedSet.of())),
                new SourcePathResolver(resolver),
                extractTestMainGenerator(),
                BuildTargets.getGenPath(
                    sourceParams.getProjectFilesystem(),
                    generatorSourceTarget,
                    "%s/main.go"),
                /* executable */ false));

    GoBinary binary =
        resolver.addToIndex(
            createGoBinaryRule(
                sourceParams.copyWithChanges(
                    generatorTarget,
                    Suppliers.ofInstance(ImmutableSortedSet.of()),
                    Suppliers.ofInstance(ImmutableSortedSet.of(writeFile))),
                resolver,
                goBuckConfig,
                ImmutableSet.of(new BuildTargetSourcePath(generatorSourceTarget)),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                goBuckConfig.getDefaultPlatform(),
                ImmutableSet.of()));
    return binary.getExecutableCommand();
  }

  private static String extractTestMainGenerator() {
    try {
      return Resources.toString(Resources.getResource(TEST_MAIN_GEN_PATH), Charsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
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

  private static ImmutableSet<GoLinkable> requireGoLinkables(
      BuildRuleResolver resolver,
      GoPlatform platform,
      Iterable<GoLinkableTargetNode<?>> targets) {
    final ImmutableSet.Builder<GoLinkable> linkables = ImmutableSet.builder();
    new AbstractBreadthFirstTraversal<GoLinkableTargetNode<?>>(targets) {
      @Override
      public Iterable<GoLinkableTargetNode<?>> visit(GoLinkableTargetNode<?> target) {
        GoLinkable linkable = target.getLinkable(resolver, platform);
        linkables.add(linkable);
        return linkable.getExportedDeps();
      }
    }.start();
    return linkables.build();
  }

  private static SymlinkTree makeSymlinkTree(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      ImmutableSet<GoLinkable> linkables) {
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
