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

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.DefaultCxxPlatforms;
import com.facebook.buck.go.GoListStep.FileType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasContacts;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasSrcs;
import com.facebook.buck.rules.HasTestTimeout;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
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
    implements Description<GoTestDescriptionArg>,
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
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      GoTestDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    Optional<GoPlatform> platform =
        getGoToolchain().getPlatformFlavorDomain().getValue(buildTarget);

    if (metadataClass.isAssignableFrom(GoLinkable.class)
        && buildTarget.getFlavors().contains(TEST_LIBRARY_FLAVOR)) {
      Preconditions.checkState(platform.isPresent());

      Path packageName = getGoPackageName(resolver, buildTarget, args);

      SourcePath output = resolver.requireRule(buildTarget).getSourcePathToOutput();
      return Optional.of(
          metadataClass.cast(
              GoLinkable.builder().setGoLinkInput(ImmutableMap.of(packageName, output)).build()));
    } else if (buildTarget.getFlavors().contains(GoDescriptors.TRANSITIVE_LINKABLES_FLAVOR)
        && buildTarget.getFlavors().contains(TEST_LIBRARY_FLAVOR)) {
      Preconditions.checkState(platform.isPresent());

      ImmutableSet<BuildTarget> deps;
      if (args.getLibrary().isPresent()) {
        GoLibraryDescriptionArg libraryArg =
            resolver.requireMetadata(args.getLibrary().get(), GoLibraryDescriptionArg.class).get();
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
                  buildTarget, resolver, platform.get(), deps, /* includeSelf */ true)));
    } else {
      return Optional.empty();
    }
  }

  private GoTestMain requireTestMainGenRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      GoToolchain goToolchain,
      ImmutableSet<SourcePath> srcs,
      ImmutableMap<Path, ImmutableMap<String, Path>> coverVariables,
      GoTestCoverStep.Mode coverageMode,
      Path packageName,
      ImmutableSortedSet<BuildRule> extraDeps,
      ImmutableSortedSet<BuildTarget> cgoDeps) {
    Tool testMainGenerator =
        GoDescriptors.getTestMainGenerator(
            goBuckConfig,
            goToolchain,
            getCxxPlatform(!cgoDeps.isEmpty()),
            buildTarget,
            projectFilesystem,
            params,
            resolver,
            cgoDeps);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    GoTestMain generatedTestMain =
        new GoTestMain(
            buildTarget.withAppendedFlavors(InternalFlavor.of("test-main-src")),
            projectFilesystem,
            params
                .withDeclaredDeps(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(BuildableSupport.getDepsCollection(testMainGenerator, ruleFinder))
                        .addAll(extraDeps)
                        .build())
                .withoutExtraDeps(),
            testMainGenerator,
            srcs,
            packageName,
            coverVariables,
            coverageMode);
    resolver.addToIndex(generatedTestMain);
    return generatedTestMain;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      GoTestDescriptionArg args) {
    GoToolchain goToolchain = getGoToolchain();
    GoPlatform platform =
        goToolchain
            .getPlatformFlavorDomain()
            .getValue(buildTarget)
            .orElse(goToolchain.getDefaultPlatform());

    BuildRuleResolver resolver = context.getBuildRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    GoTestCoverStep.Mode coverageMode;
    ImmutableSortedSet.Builder<BuildRule> extraDeps = ImmutableSortedSet.naturalOrder();
    ImmutableSet.Builder<SourcePath> srcs = ImmutableSet.builder();
    ImmutableMap<String, Path> coverVariables;

    if (args.getCoverageMode().isPresent()) {
      coverageMode = args.getCoverageMode().get();
      GoTestCoverStep.Mode coverage = coverageMode;

      GoTestCoverSource coverSource =
          (GoTestCoverSource)
              resolver.computeIfAbsent(
                  buildTarget.withAppendedFlavors(InternalFlavor.of("gen-cover")),
                  target ->
                      new GoTestCoverSource(
                          target,
                          projectFilesystem,
                          ruleFinder,
                          pathResolver,
                          platform,
                          args.getSrcs(),
                          goToolchain.getCover(),
                          coverage));

      coverVariables = coverSource.getVariables();
      srcs.addAll(coverSource.getCoveredSources()).addAll(coverSource.getTestSources());
      extraDeps.add(coverSource);
    } else {
      srcs.addAll(args.getSrcs());
      coverVariables = ImmutableMap.of();
      coverageMode = GoTestCoverStep.Mode.NONE;
    }

    if (buildTarget.getFlavors().contains(TEST_LIBRARY_FLAVOR)) {
      return createTestLibrary(
          buildTarget,
          projectFilesystem,
          params.copyAppendingExtraDeps(extraDeps.build()),
          resolver,
          srcs.build(),
          args,
          platform);
    }

    GoBinary testMain =
        createTestMainRule(
            buildTarget,
            projectFilesystem,
            params.copyAppendingExtraDeps(extraDeps.build()),
            resolver,
            goToolchain,
            srcs.build(),
            coverVariables,
            coverageMode,
            args,
            extraDeps.build(),
            platform);
    resolver.addToIndex(testMain);

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
        args.getResources());
  }

  private GoBinary createTestMainRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      GoToolchain goToolchain,
      ImmutableSet<SourcePath> srcs,
      ImmutableMap<String, Path> coverVariables,
      GoTestCoverStep.Mode coverageMode,
      GoTestDescriptionArg args,
      ImmutableSortedSet<BuildRule> extraDeps,
      GoPlatform platform) {
    Path packageName = getGoPackageName(resolver, buildTarget, args);

    BuildRule testLibrary =
        new NoopBuildRuleWithDeclaredAndExtraDeps(
            buildTarget.withAppendedFlavors(TEST_LIBRARY_FLAVOR), projectFilesystem, params);
    resolver.addToIndex(testLibrary);

    BuildRule generatedTestMain =
        requireTestMainGenRule(
            buildTarget,
            projectFilesystem,
            params,
            resolver,
            goToolchain,
            srcs,
            ImmutableMap.of(packageName, coverVariables),
            coverageMode,
            packageName,
            extraDeps,
            args.getCgoDeps());
    GoBinary testMain =
        GoDescriptors.createGoBinaryRule(
            buildTarget.withAppendedFlavors(InternalFlavor.of("test-main")),
            projectFilesystem,
            params
                .withDeclaredDeps(ImmutableSortedSet.of(testLibrary))
                .withExtraDeps(ImmutableSortedSet.of(generatedTestMain)),
            resolver,
            goBuckConfig,
            goToolchain,
            getCxxPlatform(!args.getCgoDeps().isEmpty()),
            ImmutableSet.of(generatedTestMain.getSourcePathToOutput()),
            args.getCompilerFlags(),
            args.getAssemblerFlags(),
            args.getLinkerFlags(),
            platform,
            args.getCgoDeps());
    resolver.addToIndex(testMain);
    return testMain;
  }

  private Path getGoPackageName(
      BuildRuleResolver resolver, BuildTarget target, GoTestDescriptionArg args) {
    target = target.withFlavors(); // remove flavors.

    if (args.getLibrary().isPresent()) {
      Optional<GoLibraryDescriptionArg> libraryArg =
          resolver.requireMetadata(args.getLibrary().get(), GoLibraryDescriptionArg.class);
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
      BuildRuleResolver resolver,
      ImmutableSet<SourcePath> srcs,
      GoTestDescriptionArg args,
      GoPlatform platform) {
    Path packageName = getGoPackageName(resolver, buildTarget, args);
    GoCompile testLibrary;
    GoToolchain goToolchain = getGoToolchain();
    if (args.getLibrary().isPresent()) {
      // We should have already type-checked the arguments in the base rule.
      GoLibraryDescriptionArg libraryArg =
          resolver.requireMetadata(args.getLibrary().get(), GoLibraryDescriptionArg.class).get();

      BuildRuleParams testTargetParams =
          params
              .withDeclaredDeps(
                  () ->
                      ImmutableSortedSet.<BuildRule>naturalOrder()
                          .addAll(params.getDeclaredDeps().get())
                          .addAll(resolver.getAllRules(libraryArg.getDeps()))
                          .build())
              .withExtraDeps(
                  () -> {
                    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
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
              resolver,
              goBuckConfig,
              goToolchain,
              packageName,
              ImmutableSet.<SourcePath>builder().addAll(libraryArg.getSrcs()).addAll(srcs).build(),
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
              ImmutableSortedSet.<BuildTarget>naturalOrder()
                  .addAll(libraryArg.getCgoDeps())
                  .addAll(args.getCgoDeps())
                  .build(),
              Arrays.asList(FileType.GoFiles, FileType.TestGoFiles));
    } else {
      testLibrary =
          GoDescriptors.createGoCompileRule(
              buildTarget,
              projectFilesystem,
              params,
              resolver,
              goBuckConfig,
              goToolchain,
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
              args.getCgoDeps(),
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
    // Add the C/C++ linker parse time deps.
    CxxPlatform cxxPlatform = getCxxPlatform(!constructorArg.getCgoDeps().isEmpty());
    targetGraphOnlyDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatform));
  }

  private CxxPlatform getCxxPlatform(Boolean withCgo) {
    CxxPlatformsProvider cxxPlatformsProviderFactory =
        toolchainProvider.getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);

    if (withCgo) {
      return cxxPlatformsProviderFactory.getDefaultCxxPlatform();
    }
    return cxxPlatformsProviderFactory
        .getCxxPlatforms()
        .getValue(ImmutableSet.of(DefaultCxxPlatforms.FLAVOR))
        .get();
  }

  private GoToolchain getGoToolchain() {
    return toolchainProvider.getByName(GoToolchain.DEFAULT_NAME, GoToolchain.class);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGoTestDescriptionArg
      extends CommonDescriptionArg, HasContacts, HasDeclaredDeps, HasSrcs, HasTestTimeout, HasCgo {
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
