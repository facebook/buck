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

package com.facebook.buck.features.go;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.features.go.GoListStep.FileType;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

abstract class GoDescriptors {

  private static final Logger LOG = Logger.get(GoDescriptors.class);

  private static final String TEST_MAIN_GEN_PATH = "testmaingen.go.in";
  public static final Flavor TRANSITIVE_LINKABLES_FLAVOR =
      InternalFlavor.of("transitive-linkables");

  @SuppressWarnings("unchecked")
  public static ImmutableSet<GoLinkable> requireTransitiveGoLinkables(
      BuildTarget sourceTarget,
      ActionGraphBuilder graphBuilder,
      GoPlatform platform,
      Iterable<BuildTarget> targets,
      boolean includeSelf) {
    FluentIterable<GoLinkable> linkables =
        FluentIterable.from(targets)
            .transformAndConcat(
                input -> {
                  BuildTarget flavoredTarget =
                      input.withAppendedFlavors(platform.getFlavor(), TRANSITIVE_LINKABLES_FLAVOR);
                  return graphBuilder.requireMetadata(flavoredTarget, ImmutableSet.class).get();
                });
    if (includeSelf) {
      Preconditions.checkArgument(sourceTarget.getFlavors().contains(TRANSITIVE_LINKABLES_FLAVOR));
      linkables =
          linkables.append(
              requireGoLinkable(
                  sourceTarget,
                  graphBuilder,
                  platform,
                  sourceTarget.withoutFlavors(TRANSITIVE_LINKABLES_FLAVOR)));
    }
    return linkables.toSet();
  }

  static CGoLibrary getCGoLibrary(
      ActionGraphBuilder graphBuilder, GoPlatform platform, BuildTarget cgoBuildTarget) {
    BuildRule rule =
        graphBuilder.requireRule(cgoBuildTarget.withAppendedFlavors(platform.getFlavor()));
    if (!(rule instanceof CGoLibrary)) {
      throw new HumanReadableException(
          "%s is not an instance of cgo_library", cgoBuildTarget.getFullyQualifiedName());
    }
    return (CGoLibrary) rule;
  }

  static GoCompile createGoCompileRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      GoBuckConfig goBuckConfig,
      Path packageName,
      ImmutableSet<SourcePath> srcs,
      List<String> compilerFlags,
      List<String> assemblerFlags,
      GoPlatform platform,
      Iterable<BuildTarget> deps,
      Optional<BuildTarget> cgoBuildTarget,
      List<FileType> goFileTypes) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    Preconditions.checkState(buildTarget.getFlavors().contains(platform.getFlavor()));

    ImmutableSet<GoLinkable> linkables =
        requireGoLinkables(buildTarget, graphBuilder, platform, deps);

    ImmutableList.Builder<BuildRule> linkableDepsBuilder = ImmutableList.builder();
    for (GoLinkable linkable : linkables) {
      linkableDepsBuilder.addAll(linkable.getDeps(ruleFinder));
    }

    BuildTarget target = createSymlinkTreeTarget(buildTarget);
    SymlinkTree symlinkTree =
        makeSymlinkTree(target, projectFilesystem, ruleFinder, pathResolver, linkables);
    graphBuilder.addToIndex(symlinkTree);

    ImmutableList.Builder<SourcePath> extraAsmOutputsBuilder = ImmutableList.builder();

    ImmutableSet.Builder<SourcePath> generatedSrcBuilder = ImmutableSet.builder();
    if (cgoBuildTarget.isPresent()) {
      CGoLibrary lib = getCGoLibrary(graphBuilder, platform, cgoBuildTarget.get());
      generatedSrcBuilder.addAll(lib.getGeneratedGoSource());
      extraAsmOutputsBuilder.add(lib.getOutput());

      linkableDepsBuilder.add(lib);
    }

    LOG.verbose("Symlink tree for compiling %s: %s", buildTarget, symlinkTree.getLinks());
    return new GoCompile(
        buildTarget,
        projectFilesystem,
        params
            .copyAppendingExtraDeps(linkableDepsBuilder.build())
            .copyAppendingExtraDeps(ImmutableList.of(symlinkTree))
            .copyAppendingExtraDeps(getDependenciesFromSources(ruleFinder, srcs)),
        symlinkTree,
        packageName,
        getPackageImportMap(
            goBuckConfig.getVendorPaths(),
            buildTarget.getBasePath(),
            linkables
                .stream()
                .flatMap(input -> input.getGoLinkInput().keySet().stream())
                .collect(ImmutableList.toImmutableList())),
        srcs,
        generatedSrcBuilder.build(),
        ImmutableList.copyOf(compilerFlags),
        ImmutableList.copyOf(assemblerFlags),
        platform,
        extraAsmOutputsBuilder.build(),
        goFileTypes);
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

  static ImmutableList<Arg> getCxxLinkerArgs(
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      Iterable<BuildRule> deps,
      Linker.LinkableDepType linkStyle) {

    // find all the CGoLibraries being in direct or non direct dependency to
    // declared deps
    ImmutableSet.Builder<BuildRule> linkables = ImmutableSet.builder();
    new AbstractBreadthFirstTraversal<BuildRule>(deps) {
      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        if (rule instanceof CGoLibrary) {
          linkables.addAll(((CGoLibrary) rule).getLinkableDeps());
          return ImmutableList.of();
        }
        return rule.getBuildDeps();
      }
    }.start();

    // cgo library might have C/C++ dependencies which needs to be linked to the
    // go binary. This piece of code collects the linker args from all the
    // CGoLibrary cxx dependencies.
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();
    NativeLinkableInput linkableInput =
        NativeLinkables.getTransitiveNativeLinkableInput(
            cxxPlatform,
            graphBuilder,
            linkables.build(),
            Linker.LinkableDepType.STATIC_PIC,
            r -> Optional.empty());

    // skip setting any arg if no linkable inputs are present
    if (linkableInput.getArgs().size() == 0) {
      return argsBuilder.build();
    }

    // pass any platform specific or extra linker flags.
    argsBuilder.addAll(
        SanitizedArg.from(
            cxxPlatform.getCompilerDebugPathSanitizer().sanitize(Optional.empty()),
            cxxPlatform.getLdflags()));

    // add all arguments needed to link in the C/C++ platform runtime.
    argsBuilder.addAll(StringArg.from(cxxPlatform.getRuntimeLdflags().get(linkStyle)));
    argsBuilder.addAll(linkableInput.getArgs());

    return argsBuilder.build();
  }

  static GoBinary createGoBinaryRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      GoBuckConfig goBuckConfig,
      ImmutableSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources,
      List<String> compilerFlags,
      List<String> assemblerFlags,
      List<String> linkerFlags,
      GoPlatform platform,
      Optional<BuildTarget> cgoBuildTarget) {
    BuildTarget libraryTarget =
        buildTarget.withAppendedFlavors(InternalFlavor.of("compile"), platform.getFlavor());
    GoCompile library =
        GoDescriptors.createGoCompileRule(
            libraryTarget,
            projectFilesystem,
            params,
            graphBuilder,
            goBuckConfig,
            Paths.get("main"),
            srcs,
            compilerFlags,
            assemblerFlags,
            platform,
            params
                .getDeclaredDeps()
                .get()
                .stream()
                .map(BuildRule::getBuildTarget)
                .collect(ImmutableList.toImmutableList()),
            cgoBuildTarget,
            Arrays.asList(FileType.GoFiles));
    graphBuilder.addToIndex(library);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget target = createTransitiveSymlinkTreeTarget(buildTarget);
    SymlinkTree symlinkTree =
        makeSymlinkTree(
            target,
            projectFilesystem,
            ruleFinder,
            pathResolver,
            requireTransitiveGoLinkables(
                buildTarget,
                graphBuilder,
                platform,
                params
                    .getDeclaredDeps()
                    .get()
                    .stream()
                    .map(BuildRule::getBuildTarget)
                    .collect(ImmutableList.toImmutableList()),
                /* includeSelf */ false));
    graphBuilder.addToIndex(symlinkTree);

    LOG.verbose("Symlink tree for linking of %s: %s", buildTarget, symlinkTree);

    Linker cxxLinker = platform.getCxxPlatform().getLd().resolve(graphBuilder);
    return new GoBinary(
        buildTarget,
        projectFilesystem,
        params
            .withDeclaredDeps(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(ruleFinder.filterBuildRuleInputs(symlinkTree.getLinks().values()))
                    .add(symlinkTree)
                    .add(library)
                    .addAll(BuildableSupport.getDepsCollection(cxxLinker, ruleFinder))
                    .build())
            .withoutExtraDeps(),
        Optional.of(cxxLinker),
        getCxxLinkerArgs(
            graphBuilder,
            platform.getCxxPlatform(),
            library.getBuildDeps(),
            Linker.LinkableDepType.STATIC_PIC),
        resources,
        symlinkTree,
        library,
        platform.getLinker(),
        ImmutableList.copyOf(linkerFlags),
        platform);
  }

  static Tool getTestMainGenerator(
      GoBuckConfig goBuckConfig,
      GoPlatform platform,
      BuildTarget sourceBuildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams sourceParams,
      ActionGraphBuilder graphBuilder,
      Optional<BuildTarget> cgoBuildTarget) {

    Optional<Tool> configTool = goBuckConfig.getGoTestMainGenerator(graphBuilder);
    if (configTool.isPresent()) {
      return configTool.get();
    }

    // TODO(mikekap): Make a single test main gen, rather than one per test. The generator itself
    // doesn't vary per test.
    BuildRule generator =
        graphBuilder.computeIfAbsent(
            sourceBuildTarget.withFlavors(InternalFlavor.of("make-test-main-gen")),
            generatorTarget -> {
              WriteFile writeFile =
                  (WriteFile)
                      graphBuilder.computeIfAbsent(
                          sourceBuildTarget.withAppendedFlavors(
                              InternalFlavor.of("test-main-gen-source")),
                          generatorSourceTarget ->
                              new WriteFile(
                                  generatorSourceTarget,
                                  projectFilesystem,
                                  extractTestMainGenerator(),
                                  BuildTargetPaths.getGenPath(
                                      projectFilesystem, generatorSourceTarget, "%s/main.go"),
                                  /* executable */ false));

              return createGoBinaryRule(
                  generatorTarget,
                  projectFilesystem,
                  sourceParams
                      .withoutDeclaredDeps()
                      .withExtraDeps(ImmutableSortedSet.of(writeFile)),
                  graphBuilder,
                  goBuckConfig,
                  ImmutableSet.of(writeFile.getSourcePathToOutput()),
                  ImmutableSortedSet.of(),
                  ImmutableList.of(),
                  ImmutableList.of(),
                  ImmutableList.of(),
                  platform,
                  cgoBuildTarget);
            });

    return ((BinaryBuildRule) generator).getExecutableCommand();
  }

  private static String extractTestMainGenerator() {
    try {
      return Resources.toString(
          Resources.getResource(GoDescriptors.class, TEST_MAIN_GEN_PATH), Charsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static BuildTarget createSymlinkTreeTarget(BuildTarget source) {
    return source.withAppendedFlavors(InternalFlavor.of("symlink-tree"));
  }

  private static BuildTarget createTransitiveSymlinkTreeTarget(BuildTarget source) {
    return source.withAppendedFlavors(InternalFlavor.of("transitive-symlink-tree"));
  }

  private static GoLinkable requireGoLinkable(
      BuildTarget sourceRule,
      ActionGraphBuilder graphBuilder,
      GoPlatform platform,
      BuildTarget target) {
    Optional<GoLinkable> linkable =
        graphBuilder.requireMetadata(
            target.withAppendedFlavors(platform.getFlavor()), GoLinkable.class);
    if (!linkable.isPresent()) {
      throw new HumanReadableException(
          "%s (needed for %s) is not an instance of go_library!",
          target.getFullyQualifiedName(), sourceRule.getFullyQualifiedName());
    }
    return linkable.get();
  }

  private static ImmutableSet<GoLinkable> requireGoLinkables(
      BuildTarget sourceTarget,
      ActionGraphBuilder graphBuilder,
      GoPlatform platform,
      Iterable<BuildTarget> targets) {
    ImmutableSet.Builder<GoLinkable> linkables = ImmutableSet.builder();
    new AbstractBreadthFirstTraversal<BuildTarget>(targets) {
      @Override
      public Iterable<BuildTarget> visit(BuildTarget target) {
        GoLinkable linkable = requireGoLinkable(sourceTarget, graphBuilder, platform, target);
        linkables.add(linkable);
        return linkable.getExportedDeps();
      }
    }.start();
    return linkables.build();
  }

  /**
   * Make sure that if any srcs elements are a build rule, we add it as a dependency, so we wait for
   * it to finish running before using the source
   */
  private static ImmutableList<BuildRule> getDependenciesFromSources(
      SourcePathRuleFinder ruleFinder, ImmutableSet<SourcePath> srcs) {
    return srcs.stream()
        .map(ruleFinder::getRule)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(ImmutableList.toImmutableList());
  }

  private static SymlinkTree makeSymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
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
          buildTarget.getFullyQualifiedName());
    }

    Path root = BuildTargetPaths.getScratchPath(projectFilesystem, buildTarget, "__%s__tree");

    return new SymlinkTree(
        "go_linkable",
        buildTarget,
        projectFilesystem,
        root,
        treeMap,
        ImmutableMultimap.of(),
        ruleFinder);
  }

  /**
   * @return the path in the symlink tree as used by the compiler. This is usually the package name
   *     + '.a'.
   */
  private static Path getPathInSymlinkTree(
      SourcePathResolver resolver, Path goPackageName, SourcePath ruleOutput) {
    Path output = resolver.getRelativePath(ruleOutput);

    String extension = Files.getFileExtension(output.toString());
    return Paths.get(goPackageName + (extension.equals("") ? "" : "." + extension));
  }
}
