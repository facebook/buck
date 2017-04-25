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
import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
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
import com.google.common.io.Files;
import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

abstract class GoDescriptors {

  private static final Logger LOG = Logger.get(GoDescriptors.class);

  private static final String TEST_MAIN_GEN_PATH = "com/facebook/buck/go/testmaingen.go";
  public static final Flavor TRANSITIVE_LINKABLES_FLAVOR =
      InternalFlavor.of("transitive-linkables");

  @SuppressWarnings("unchecked")
  public static ImmutableSet<GoLinkable> requireTransitiveGoLinkables(
      final BuildTarget sourceTarget,
      final BuildRuleResolver resolver,
      final GoPlatform platform,
      Iterable<BuildTarget> targets,
      boolean includeSelf)
      throws NoSuchBuildTargetException {
    FluentIterable<GoLinkable> linkables =
        FluentIterable.from(targets)
            .transformAndConcat(
                new Function<BuildTarget, ImmutableSet<GoLinkable>>() {
                  @Override
                  public ImmutableSet<GoLinkable> apply(BuildTarget input) {
                    BuildTarget flavoredTarget =
                        BuildTarget.builder(input)
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
      linkables =
          linkables.append(
              requireGoLinkable(
                  sourceTarget,
                  resolver,
                  platform,
                  sourceTarget.withoutFlavors(TRANSITIVE_LINKABLES_FLAVOR)));
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
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    Preconditions.checkState(params.getBuildTarget().getFlavors().contains(platform.getFlavor()));

    ImmutableSet<GoLinkable> linkables =
        requireGoLinkables(params.getBuildTarget(), resolver, platform, deps);

    ImmutableList.Builder<BuildRule> linkableDepsBuilder = ImmutableList.builder();
    for (GoLinkable linkable : linkables) {
      linkableDepsBuilder.addAll(linkable.getDeps(ruleFinder));
    }
    ImmutableList<BuildRule> linkableDeps = linkableDepsBuilder.build();

    BuildTarget target = createSymlinkTreeTarget(params.getBuildTarget());
    SymlinkTree symlinkTree =
        makeSymlinkTree(params.withBuildTarget(target), pathResolver, ruleFinder, linkables);
    resolver.addToIndex(symlinkTree);

    LOG.verbose(
        "Symlink tree for compiling %s: %s", params.getBuildTarget(), symlinkTree.getLinks());

    return new GoCompile(
        params
            .copyAppendingExtraDeps(linkableDeps)
            .copyAppendingExtraDeps(ImmutableList.of(symlinkTree)),
        symlinkTree,
        packageName,
        getPackageImportMap(
            goBuckConfig.getVendorPaths(),
            params.getBuildTarget().getBasePath(),
            FluentIterable.from(linkables)
                .transformAndConcat(
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
      ImmutableList<Path> globalVendorPaths, Path basePackagePath, Iterable<Path> packageNameIter) {
    Map<Path, Path> importMapBuilder = new HashMap<>();
    ImmutableSortedSet<Path> packageNames = ImmutableSortedSet.copyOf(packageNameIter);

    ImmutableList.Builder<Path> vendorPathsBuilder = ImmutableList.builder();
    vendorPathsBuilder.addAll(globalVendorPaths);
    Path prefix = Paths.get("");
    for (Path component : FluentIterable.from(new Path[] {Paths.get("")}).append(basePackagePath)) {
      prefix = prefix.resolve(component);
      vendorPathsBuilder.add(prefix.resolve("vendor"));
    }

    for (Path vendorPrefix : vendorPathsBuilder.build()) {
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
      GoPlatform platform)
      throws NoSuchBuildTargetException {
    BuildTarget libraryTarget =
        params
            .getBuildTarget()
            .withAppendedFlavors(InternalFlavor.of("compile"), platform.getFlavor());
    GoCompile library =
        GoDescriptors.createGoCompileRule(
            params.withBuildTarget(libraryTarget),
            resolver,
            goBuckConfig,
            Paths.get("main"),
            srcs,
            compilerFlags,
            assemblerFlags,
            platform,
            FluentIterable.from(params.getDeclaredDeps().get())
                .transform(BuildRule::getBuildTarget));
    resolver.addToIndex(library);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    BuildTarget target = createTransitiveSymlinkTreeTarget(params.getBuildTarget());
    SymlinkTree symlinkTree =
        makeSymlinkTree(
            params.withBuildTarget(target),
            pathResolver,
            ruleFinder,
            requireTransitiveGoLinkables(
                params.getBuildTarget(),
                resolver,
                platform,
                FluentIterable.from(params.getDeclaredDeps().get())
                    .transform(BuildRule::getBuildTarget),
                /* includeSelf */ false));
    resolver.addToIndex(symlinkTree);

    LOG.verbose("Symlink tree for linking of %s: %s", params.getBuildTarget(), symlinkTree);

    return new GoBinary(
        params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(ruleFinder.filterBuildRuleInputs(symlinkTree.getLinks().values()))
                    .add(symlinkTree)
                    .add(library)
                    .build()),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        platform.getCxxPlatform().map(input -> input.getLd().resolve(resolver)),
        symlinkTree,
        library,
        goBuckConfig.getLinker(),
        ImmutableList.copyOf(linkerFlags),
        platform);
  }

  static Tool getTestMainGenerator(
      GoBuckConfig goBuckConfig, BuildRuleParams sourceParams, BuildRuleResolver resolver)
      throws NoSuchBuildTargetException {

    Optional<Tool> configTool = goBuckConfig.getGoTestMainGenerator(resolver);
    if (configTool.isPresent()) {
      return configTool.get();
    }

    // TODO(mikekap): Make a single test main gen, rather than one per test. The generator itself
    // doesn't vary per test.
    BuildTarget generatorTarget =
        sourceParams.getBuildTarget().withFlavors(InternalFlavor.of("make-test-main-gen"));
    Optional<BuildRule> generator = resolver.getRuleOptional(generatorTarget);
    if (generator.isPresent()) {
      return ((BinaryBuildRule) generator.get()).getExecutableCommand();
    }

    BuildTarget generatorSourceTarget =
        sourceParams
            .getBuildTarget()
            .withAppendedFlavors(InternalFlavor.of("test-main-gen-source"));
    WriteFile writeFile =
        resolver.addToIndex(
            new WriteFile(
                sourceParams
                    .withBuildTarget(generatorSourceTarget)
                    .copyReplacingDeclaredAndExtraDeps(
                        Suppliers.ofInstance(ImmutableSortedSet.of()),
                        Suppliers.ofInstance(ImmutableSortedSet.of())),
                extractTestMainGenerator(),
                BuildTargets.getGenPath(
                    sourceParams.getProjectFilesystem(), generatorSourceTarget, "%s/main.go"),
                /* executable */ false));

    GoBinary binary =
        resolver.addToIndex(
            createGoBinaryRule(
                sourceParams
                    .withBuildTarget(generatorTarget)
                    .copyReplacingDeclaredAndExtraDeps(
                        Suppliers.ofInstance(ImmutableSortedSet.of()),
                        Suppliers.ofInstance(ImmutableSortedSet.of(writeFile))),
                resolver,
                goBuckConfig,
                ImmutableSet.of(writeFile.getSourcePathToOutput()),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                goBuckConfig.getDefaultPlatform()));
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
    return BuildTarget.builder(source).addFlavors(InternalFlavor.of("symlink-tree")).build();
  }

  private static BuildTarget createTransitiveSymlinkTreeTarget(BuildTarget source) {
    return BuildTarget.builder(source)
        .addFlavors(InternalFlavor.of("transitive-symlink-tree"))
        .build();
  }

  private static GoLinkable requireGoLinkable(
      BuildTarget sourceRule, BuildRuleResolver resolver, GoPlatform platform, BuildTarget target)
      throws NoSuchBuildTargetException {
    Optional<GoLinkable> linkable =
        resolver.requireMetadata(
            BuildTarget.builder(target).addFlavors(platform.getFlavor()).build(), GoLinkable.class);
    if (!linkable.isPresent()) {
      throw new HumanReadableException(
          "%s (needed for %s) is not an instance of go_library!",
          target.getFullyQualifiedName(), sourceRule.getFullyQualifiedName());
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
      SourcePathRuleFinder ruleFinder,
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

    Path root =
        BuildTargets.getScratchPath(
            params.getProjectFilesystem(), params.getBuildTarget(), "__%s__tree");

    return new SymlinkTree(
        params.getBuildTarget(), params.getProjectFilesystem(), root, treeMap, ruleFinder);
  }

  /**
   * @return the path in the symlink tree as used by the compiler. This is usually the package name
   *     + '.a'.
   */
  private static Path getPathInSymlinkTree(
      SourcePathResolver resolver, Path goPackageName, SourcePath ruleOutput) {
    Path output = resolver.getRelativePath(ruleOutput);

    String extension = Files.getFileExtension(output.toString());
    return Paths.get(goPackageName.toString() + (extension.equals("") ? "" : "." + extension));
  }
}
