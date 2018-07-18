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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.MetadataProvidingDescription;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasContacts;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.description.arg.HasTestTimeout;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.features.go.GoListStep.FileType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import org.immutables.value.Value;

public class GoTestDescription
    implements DescriptionWithTargetGraph<GoTestDescriptionArg>,
        Flavored,
        MetadataProvidingDescription<GoTestDescriptionArg>,
        ImplicitDepsInferringDescription<GoTestDescription.AbstractGoTestDescriptionArg>,
        VersionRoot<GoTestDescriptionArg> {

  private static final Flavor TEST_LIBRARY_FLAVOR = InternalFlavor.of("test-library");

  private final GoBuckConfig goBuckConfig;
  private final ToolchainProvider toolchainProvider;

  public GoTestDescription(GoBuckConfig goBuckConfig, ToolchainProvider toolchainProvider) {
    this.goBuckConfig = goBuckConfig;
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<GoTestDescriptionArg> getConstructorArgType() {
    return GoTestDescriptionArg.class;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return getGoToolchain().getPlatformFlavorDomain().containsAnyOf(flavors)
        || flavors.contains(TEST_LIBRARY_FLAVOR);
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      GoTestDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    Optional<GoPlatform> platform =
        getGoToolchain().getPlatformFlavorDomain().getValue(buildTarget);

    if (metadataClass.isAssignableFrom(GoLinkable.class)
        && buildTarget.getFlavors().contains(TEST_LIBRARY_FLAVOR)) {
      Preconditions.checkState(platform.isPresent());

      Path packageName = getGoPackageName(graphBuilder, buildTarget, args);

      SourcePath output = graphBuilder.requireRule(buildTarget).getSourcePathToOutput();
      return Optional.of(
          metadataClass.cast(
              GoLinkable.builder().setGoLinkInput(ImmutableMap.of(packageName, output)).build()));
    } else if (buildTarget.getFlavors().contains(GoDescriptors.TRANSITIVE_LINKABLES_FLAVOR)
        && buildTarget.getFlavors().contains(TEST_LIBRARY_FLAVOR)) {
      Preconditions.checkState(platform.isPresent());

      ImmutableSet<BuildTarget> deps;
      if (args.getLibrary().isPresent()) {
        GoLibraryDescriptionArg libraryArg =
            graphBuilder
                .requireMetadata(args.getLibrary().get(), GoLibraryDescriptionArg.class)
                .get();
        deps =
            ImmutableSortedSet.<BuildTarget>naturalOrder()
                .addAll(args.getDeps())
                .addAll(libraryArg.getDeps())
                .build();
      } else {
        deps = args.getDeps();
      }

      return Optional.of(
          metadataClass.cast(
              GoDescriptors.requireTransitiveGoLinkables(
                  buildTarget, graphBuilder, platform.get(), deps, /* includeSelf */ true)));
    } else {
      return Optional.empty();
    }
  }

  private GoTestMain requireTestMainGenRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      GoPlatform platform,
      ImmutableSet<SourcePath> srcs,
      ImmutableMap<Path, ImmutableMap<String, Path>> coverVariables,
      GoTestCoverStep.Mode coverageMode,
      Path packageName,
      Optional<BuildTarget> cgoBuildTarget) {
    Tool testMainGenerator =
        GoDescriptors.getTestMainGenerator(
            goBuckConfig,
            platform,
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            cgoBuildTarget);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);

    GoTestMain generatedTestMain =
        new GoTestMain(
            buildTarget.withAppendedFlavors(InternalFlavor.of("test-main-src")),
            projectFilesystem,
            params.withDeclaredDeps(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(BuildableSupport.getDepsCollection(testMainGenerator, ruleFinder))
                    .build()),
            testMainGenerator,
            srcs,
            packageName,
            platform,
            coverVariables,
            coverageMode);
    graphBuilder.addToIndex(generatedTestMain);
    return generatedTestMain;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      GoTestDescriptionArg args) {
    GoPlatform platform = getGoPlatform(buildTarget, args);

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    GoTestCoverStep.Mode coverageMode;
    ImmutableSortedSet.Builder<BuildRule> extraDeps = ImmutableSortedSet.naturalOrder();
    ImmutableSet.Builder<SourcePath> srcs;
    ImmutableMap<String, Path> coverVariables;

    ImmutableSet.Builder<SourcePath> rawSrcs = ImmutableSet.builder();
    rawSrcs.addAll(args.getSrcs());
    if (args.getLibrary().isPresent()) {
      GoLibraryDescriptionArg libraryArg =
          graphBuilder
              .requireMetadata(args.getLibrary().get(), GoLibraryDescriptionArg.class)
              .get();

      rawSrcs.addAll(libraryArg.getSrcs());
    }
    if (args.getCoverageMode().isPresent()) {
      coverageMode = args.getCoverageMode().get();
      GoTestCoverStep.Mode coverage = coverageMode;

      GoTestCoverSource coverSource =
          (GoTestCoverSource)
              graphBuilder.computeIfAbsent(
                  buildTarget.withAppendedFlavors(InternalFlavor.of("gen-cover")),
                  target ->
                      new GoTestCoverSource(
                          target,
                          projectFilesystem,
                          ruleFinder,
                          pathResolver,
                          platform,
                          rawSrcs.build(),
                          platform.getCover(),
                          coverage));

      coverVariables = coverSource.getVariables();
      srcs = ImmutableSet.builder();
      srcs.addAll(coverSource.getCoveredSources()).addAll(coverSource.getTestSources());
      extraDeps.add(coverSource);
    } else {
      srcs = rawSrcs;
      coverVariables = ImmutableMap.of();
      coverageMode = GoTestCoverStep.Mode.NONE;
    }

    if (buildTarget.getFlavors().contains(TEST_LIBRARY_FLAVOR)) {
      return createTestLibrary(
          buildTarget,
          projectFilesystem,
          params.copyAppendingExtraDeps(extraDeps.build()),
          graphBuilder,
          srcs.build(),
          args,
          platform);
    }

    GoBinary testMain =
        createTestMainRule(
            buildTarget,
            projectFilesystem,
            params.copyAppendingExtraDeps(extraDeps.build()),
            graphBuilder,
            srcs.build(),
            coverVariables,
            coverageMode,
            args,
            platform);
    graphBuilder.addToIndex(testMain);

    return new GoTest(
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(testMain)).withoutExtraDeps(),
        testMain,
        args.getLabels(),
        args.getContacts(),
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(goBuckConfig.getDelegate().getDefaultTestRuleTimeoutMs()),
        args.getRunTestSeparately(),
        args.getResources(),
        coverageMode);
  }

  private GoBinary createTestMainRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      ImmutableSet<SourcePath> srcs,
      ImmutableMap<String, Path> coverVariables,
      GoTestCoverStep.Mode coverageMode,
      GoTestDescriptionArg args,
      GoPlatform platform) {
    Path packageName = getGoPackageName(graphBuilder, buildTarget, args);
    boolean createResourcesSymlinkTree =
        goBuckConfig.getDelegate().getExternalTestRunner().isPresent();

    BuildRule testLibrary =
        new NoopBuildRuleWithDeclaredAndExtraDeps(
            buildTarget.withAppendedFlavors(TEST_LIBRARY_FLAVOR), projectFilesystem, params);
    graphBuilder.addToIndex(testLibrary);

    BuildRule generatedTestMain =
        requireTestMainGenRule(
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            platform,
            srcs,
            ImmutableMap.of(packageName, coverVariables),
            coverageMode,
            packageName,
            args.getCgo());

    GoBinary testMain =
        GoDescriptors.createGoBinaryRule(
            buildTarget.withAppendedFlavors(InternalFlavor.of("test-main")),
            projectFilesystem,
            params
                .withDeclaredDeps(ImmutableSortedSet.of(testLibrary))
                .withExtraDeps(ImmutableSortedSet.of(generatedTestMain)),
            graphBuilder,
            goBuckConfig,
            ImmutableSet.of(generatedTestMain.getSourcePathToOutput()),
            createResourcesSymlinkTree ? args.getResources() : ImmutableSortedSet.of(),
            args.getCompilerFlags(),
            args.getAssemblerFlags(),
            args.getLinkerFlags(),
            platform,
            args.getCgo());
    graphBuilder.addToIndex(testMain);
    return testMain;
  }

  private Path getGoPackageName(
      ActionGraphBuilder graphBuilder, BuildTarget target, GoTestDescriptionArg args) {
    target = target.withFlavors(); // remove flavors.

    if (args.getLibrary().isPresent()) {
      Optional<GoLibraryDescriptionArg> libraryArg =
          graphBuilder.requireMetadata(args.getLibrary().get(), GoLibraryDescriptionArg.class);
      if (!libraryArg.isPresent()) {
        throw new HumanReadableException(
            "Library specified in %s (%s) is not a go_library rule.",
            target, args.getLibrary().get());
      }

      if (args.getPackageName().isPresent()) {
        throw new HumanReadableException(
            "Test target %s specifies both library and package_name - only one should be specified",
            target);
      }

      if (!libraryArg.get().getTests().contains(target)) {
        throw new HumanReadableException(
            "go internal test target %s is not listed in `tests` of library %s",
            target, args.getLibrary().get());
      }

      return libraryArg
          .get()
          .getPackageName()
          .map(Paths::get)
          .orElse(goBuckConfig.getDefaultPackageName(args.getLibrary().get()));
    } else if (args.getPackageName().isPresent()) {
      return Paths.get(args.getPackageName().get());
    } else {
      Path packageName = goBuckConfig.getDefaultPackageName(target);
      return packageName.resolveSibling(packageName.getFileName() + "_test");
    }
  }

  private GoCompile createTestLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      ImmutableSet<SourcePath> srcs,
      GoTestDescriptionArg args,
      GoPlatform platform) {
    Path packageName = getGoPackageName(graphBuilder, buildTarget, args);
    GoCompile testLibrary;
    if (args.getLibrary().isPresent()) {
      // We should have already type-checked the arguments in the base rule.
      GoLibraryDescriptionArg libraryArg =
          graphBuilder
              .requireMetadata(args.getLibrary().get(), GoLibraryDescriptionArg.class)
              .get();

      BuildRuleParams testTargetParams =
          params
              .withDeclaredDeps(
                  () ->
                      ImmutableSortedSet.<BuildRule>naturalOrder()
                          .addAll(params.getDeclaredDeps().get())
                          .addAll(graphBuilder.getAllRules(libraryArg.getDeps()))
                          .build())
              .withExtraDeps(
                  () -> {
                    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
                    return ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(params.getExtraDeps().get())
                        // Make sure to include dynamically generated sources as deps.
                        .addAll(ruleFinder.filterBuildRuleInputs(libraryArg.getSrcs()))
                        .build();
                  });
      testLibrary =
          GoDescriptors.createGoCompileRule(
              buildTarget,
              projectFilesystem,
              testTargetParams,
              graphBuilder,
              goBuckConfig,
              packageName,
              ImmutableSet.<SourcePath>builder().addAll(srcs).build(),
              ImmutableList.<String>builder()
                  .addAll(libraryArg.getCompilerFlags())
                  .addAll(args.getCompilerFlags())
                  .build(),
              ImmutableList.<String>builder()
                  .addAll(libraryArg.getAssemblerFlags())
                  .addAll(args.getAssemblerFlags())
                  .build(),
              platform,
              testTargetParams
                  .getDeclaredDeps()
                  .get()
                  .stream()
                  .map(BuildRule::getBuildTarget)
                  .collect(ImmutableList.toImmutableList()),
              libraryArg.getCgo(),
              Arrays.asList(FileType.GoFiles, FileType.TestGoFiles));
    } else {
      testLibrary =
          GoDescriptors.createGoCompileRule(
              buildTarget,
              projectFilesystem,
              params,
              graphBuilder,
              goBuckConfig,
              packageName,
              srcs,
              args.getCompilerFlags(),
              args.getAssemblerFlags(),
              platform,
              params
                  .getDeclaredDeps()
                  .get()
                  .stream()
                  .map(BuildRule::getBuildTarget)
                  .collect(ImmutableList.toImmutableList()),
              args.getCgo(),
              Arrays.asList(FileType.GoFiles, FileType.TestGoFiles, FileType.XTestGoFiles));
    }

    return testLibrary;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractGoTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Add the C/C++ platform parse time deps.
    targetGraphOnlyDepsBuilder.addAll(
        CxxPlatforms.getParseTimeDeps(getGoPlatform(buildTarget, constructorArg).getCxxPlatform()));
  }

  private GoToolchain getGoToolchain() {
    return toolchainProvider.getByName(GoToolchain.DEFAULT_NAME, GoToolchain.class);
  }

  private GoPlatform getGoPlatform(BuildTarget target, AbstractGoTestDescriptionArg arg) {
    GoToolchain toolchain = getGoToolchain();
    FlavorDomain<GoPlatform> platforms = toolchain.getPlatformFlavorDomain();
    return platforms
        .getValue(target)
        .orElseGet(
            () ->
                arg.getPlatform()
                    .map(platforms::getValue)
                    .orElseGet(toolchain::getDefaultPlatform));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGoTestDescriptionArg
      extends CommonDescriptionArg, HasContacts, HasDeclaredDeps, HasSrcs, HasTestTimeout, HasCgo {
    Optional<Flavor> getPlatform();

    Optional<BuildTarget> getLibrary();

    Optional<String> getPackageName();

    Optional<GoTestCoverStep.Mode> getCoverageMode();

    ImmutableList<String> getCompilerFlags();

    ImmutableList<String> getAssemblerFlags();

    ImmutableList<String> getLinkerFlags();

    @Value.Default
    default boolean getRunTestSeparately() {
      return false;
    }

    @Value.NaturalOrder
    ImmutableSortedSet<SourcePath> getResources();
  }
}
