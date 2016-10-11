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
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class GoTestDescription implements
    Description<GoTestDescription.Arg>,
    Flavored,
    MetadataProvidingDescription<GoTestDescription.Arg>,
    ImplicitDepsInferringDescription<GoTestDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("go_test");
  private static final Flavor TEST_LIBRARY_FLAVOR = ImmutableFlavor.of("test-library");

  private final GoBuckConfig goBuckConfig;
  private final Optional<Long> defaultTestRuleTimeoutMs;

  public GoTestDescription(
      GoBuckConfig goBuckConfig,
      Optional<Long> defaultTestRuleTimeoutMs) {
    this.goBuckConfig = goBuckConfig;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return goBuckConfig.getPlatformFlavorDomain().containsAnyOf(flavors) ||
        flavors.contains(TEST_LIBRARY_FLAVOR);
  }

  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      final BuildRuleResolver resolver,
      A args,
      Class<U> metadataClass) throws NoSuchBuildTargetException {
    Optional<GoPlatform> platform =
        goBuckConfig.getPlatformFlavorDomain().getValue(buildTarget);

    if (metadataClass.isAssignableFrom(GoLinkable.class) &&
        buildTarget.getFlavors().contains(TEST_LIBRARY_FLAVOR)) {
      Preconditions.checkState(platform.isPresent());

      Path packageName = getGoPackageName(resolver, buildTarget, args);

      SourcePath output = new BuildTargetSourcePath(
          resolver.requireRule(buildTarget).getBuildTarget());
      return Optional.of(metadataClass.cast(GoLinkable.builder()
          .setGoLinkInput(ImmutableMap.of(packageName, output))
          .build()));
    } else if (
        buildTarget.getFlavors().contains(GoDescriptors.TRANSITIVE_LINKABLES_FLAVOR) &&
        buildTarget.getFlavors().contains(TEST_LIBRARY_FLAVOR)) {
      Preconditions.checkState(platform.isPresent());

      ImmutableSet<BuildTarget> deps;
      if (args.library.isPresent()) {
        GoLibraryDescription.Arg libraryArg = resolver.requireMetadata(
            args.library.get(), GoLibraryDescription.Arg.class).get();
        deps = ImmutableSortedSet.<BuildTarget>naturalOrder()
            .addAll(args.deps.get())
            .addAll(libraryArg.deps.get())
            .build();
      } else {
        deps = args.deps.get();
      }

      return Optional.of(metadataClass.cast(GoDescriptors.requireTransitiveGoLinkables(
          buildTarget,
          resolver,
          platform.get(),
          deps,
          /* includeSelf */ true)));
    } else {
      return Optional.absent();
    }
  }

  private GoTestMain requireTestMainGenRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableSet<SourcePath> srcs,
      Path packageName) throws NoSuchBuildTargetException {
    Tool testMainGenerator = GoDescriptors.getTestMainGenerator(goBuckConfig, params, resolver);

    SourcePathResolver sourceResolver = new SourcePathResolver(resolver);
    GoTestMain generatedTestMain = new GoTestMain(
        params.copyWithChanges(
            params.getBuildTarget().withAppendedFlavors(ImmutableFlavor.of("test-main-src")),
            Suppliers.ofInstance(ImmutableSortedSet.copyOf(
                testMainGenerator.getDeps(sourceResolver))),
            Suppliers.ofInstance(ImmutableSortedSet.of())
        ),
        sourceResolver,
        testMainGenerator,
        srcs,
        packageName
    );
    resolver.addToIndex(generatedTestMain);
    return generatedTestMain;
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    GoPlatform platform = goBuckConfig.getPlatformFlavorDomain().getValue(params.getBuildTarget())
        .or(goBuckConfig.getDefaultPlatform());

    if (params.getBuildTarget().getFlavors().contains(TEST_LIBRARY_FLAVOR)) {
      return createTestLibrary(
          params,
          resolver,
          args,
          platform);
    }

    GoBinary testMain = createTestMainRule(
        params,
        resolver,
        args,
        platform);
    resolver.addToIndex(testMain);

    return new GoTest(
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of(testMain)),
            Suppliers.ofInstance(ImmutableSortedSet.of())
        ),
        new SourcePathResolver(resolver),
        testMain,
        args.labels.get(),
        args.contacts.get(),
        args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
        args.runTestSeparately.or(false),
        args.resources.get());
  }

  private GoBinary createTestMainRule(
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      Arg args,
      GoPlatform platform) throws NoSuchBuildTargetException {
    Path packageName = getGoPackageName(resolver, params.getBuildTarget(), args);

    BuildRuleParams testTargetParams = params.copyWithBuildTarget(
        params.getBuildTarget().withAppendedFlavors(TEST_LIBRARY_FLAVOR));
    BuildRule testLibrary = new NoopBuildRule(testTargetParams, new SourcePathResolver(resolver));
    resolver.addToIndex(testLibrary);

    BuildRule generatedTestMain = requireTestMainGenRule(
        params, resolver, args.srcs, packageName);
    GoBinary testMain = GoDescriptors.createGoBinaryRule(
        params.copyWithChanges(
            params.getBuildTarget().withAppendedFlavors(ImmutableFlavor.of("test-main")),
            Suppliers.ofInstance(ImmutableSortedSet.of(testLibrary)),
            Suppliers.ofInstance(ImmutableSortedSet.of(generatedTestMain))),
        resolver,
        goBuckConfig,
        ImmutableSet.of(new BuildTargetSourcePath(generatedTestMain.getBuildTarget())),
        args.compilerFlags.get(),
        args.assemblerFlags.get(),
        args.linkerFlags.get(),
        platform);
    resolver.addToIndex(testMain);
    return testMain;
  }

  private Path getGoPackageName(BuildRuleResolver resolver, BuildTarget target, Arg args)
      throws NoSuchBuildTargetException {
    target = target.withFlavors();  // remove flavors.

    if (args.library.isPresent()) {
      final Optional<GoLibraryDescription.Arg> libraryArg = resolver.requireMetadata(
          args.library.get(), GoLibraryDescription.Arg.class);
      if (!libraryArg.isPresent()) {
        throw new HumanReadableException(
            "Library specified in %s (%s) is not a go_library rule.",
            target, args.library.get());
      }

      if (args.packageName.isPresent()) {
        throw new HumanReadableException(
            "Test target %s specifies both library and package_name - only one should be specified",
            target);
      }

      if (!libraryArg.get().tests.get().contains(target)) {
        throw new HumanReadableException(
            "go internal test target %s is not listed in `tests` of library %s",
            target, args.library.get());
      }

      return libraryArg.get().packageName.transform(MorePaths.TO_PATH).or(
          goBuckConfig.getDefaultPackageName(args.library.get()));
    } else if (args.packageName.isPresent()) {
      return Paths.get(args.packageName.get());
    } else {
      Path packageName = goBuckConfig.getDefaultPackageName(target);
      return packageName.resolveSibling(packageName.getFileName() + "_test");
    }

  }

  private GoCompile createTestLibrary(
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      Arg args,
      GoPlatform platform) throws NoSuchBuildTargetException {
    Path packageName = getGoPackageName(resolver, params.getBuildTarget(), args);
    GoCompile testLibrary;
    if (args.library.isPresent()) {
      // We should have already type-checked the arguments in the base rule.
      final GoLibraryDescription.Arg libraryArg = resolver.requireMetadata(
          args.library.get(), GoLibraryDescription.Arg.class).get();

      final BuildRuleParams originalParams = params;
      BuildRuleParams testTargetParams = params.copyWithDeps(
          () -> ImmutableSortedSet.<BuildRule>naturalOrder()
              .addAll(originalParams.getDeclaredDeps().get())
              .addAll(resolver.getAllRules(libraryArg.deps.get()))
              .build(),
          () -> {
            final SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
            return ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(originalParams.getExtraDeps().get())
                // Make sure to include dynamically generated sources as deps.
                .addAll(
                    sourcePathResolver.filterBuildRuleInputs(
                        libraryArg.srcs.or(ImmutableSortedSet.of())))
                .build();
          });

      testLibrary = GoDescriptors.createGoCompileRule(
          testTargetParams,
          resolver,
          goBuckConfig,
          packageName,
          ImmutableSet.<SourcePath>builder()
              .addAll(libraryArg.srcs.or(ImmutableSortedSet.of()))
              .addAll(args.srcs)
              .build(),
          ImmutableList.<String>builder()
              .addAll(libraryArg.compilerFlags.get())
              .addAll(args.compilerFlags.get())
              .build(),
          ImmutableList.<String>builder()
              .addAll(libraryArg.assemblerFlags.get())
              .addAll(args.assemblerFlags.get())
              .build(),
          platform,
          FluentIterable.from(params.getDeclaredDeps().get())
              .transform(HasBuildTarget.TO_TARGET));
    } else {
      testLibrary = GoDescriptors.createGoCompileRule(
          params,
          resolver,
          goBuckConfig,
          packageName,
          args.srcs,
          args.compilerFlags.get(),
          args.assemblerFlags.get(),
          platform,
          FluentIterable.from(params.getDeclaredDeps().get())
              .transform(HasBuildTarget.TO_TARGET));
    }

    return testLibrary;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {

    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();

    // Add the C/C++ linker parse time deps.
    GoPlatform goPlatform =
        goBuckConfig.getPlatformFlavorDomain().getValue(buildTarget)
            .or(goBuckConfig.getDefaultPlatform());
    Optional<CxxPlatform> cxxPlatform = goPlatform.getCxxPlatform();
    if (cxxPlatform.isPresent()) {
      targets.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatform.get()));
    }

    return targets.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableSet<SourcePath> srcs;
    public Optional<BuildTarget> library;
    public Optional<String> packageName;
    public Optional<List<String>> compilerFlags;
    public Optional<List<String>> assemblerFlags;
    public Optional<List<String>> linkerFlags;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<ImmutableSet<String>> contacts;
    public Optional<ImmutableSet<Label>> labels;
    public Optional<Long> testRuleTimeoutMs;
    public Optional<Boolean> runTestSeparately;
    public Optional<ImmutableSortedSet<SourcePath>> resources;
  }
}
