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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

public class CxxTestDescription
    implements Description<CxxTestDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<CxxTestDescription.AbstractCxxTestDescriptionArg>,
        MetadataProvidingDescription<CxxTestDescriptionArg>,
        VersionRoot<CxxTestDescriptionArg> {

  private static final CxxTestType DEFAULT_TEST_TYPE = CxxTestType.GTEST;

  private final CxxBuckConfig cxxBuckConfig;
  private final CxxPlatform defaultCxxPlatform;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final Optional<Long> defaultTestRuleTimeoutMs;

  public CxxTestDescription(
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      Optional<Long> defaultTestRuleTimeoutMs) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.cxxPlatforms = cxxPlatforms;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
  }

  private ImmutableSet<BuildTarget> getImplicitFrameworkDeps(
      AbstractCxxTestDescriptionArg constructorArg) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    CxxTestType type = constructorArg.getFramework().orElse(getDefaultTestType());
    switch (type) {
      case GTEST:
        {
          cxxBuckConfig.getGtestDep().ifPresent(deps::add);
          if (constructorArg.getUseDefaultTestMain().orElse(true)) {
            cxxBuckConfig.getGtestDefaultTestMainDep().ifPresent(deps::add);
          }
          break;
        }
      case BOOST:
        {
          cxxBuckConfig.getBoostTestDep().ifPresent(deps::add);
          break;
        }
      default:
        {
          break;
        }
    }

    return deps.build();
  }

  private CxxPlatform getCxxPlatform(
      BuildTarget target, CxxBinaryDescription.CommonArg constructorArg) {

    // First check if the build target is setting a particular target.
    Optional<CxxPlatform> targetPlatform = cxxPlatforms.getValue(target.getFlavors());
    if (targetPlatform.isPresent()) {
      return targetPlatform.get();
    }

    // Next, check for a constructor arg level default platform.
    if (constructorArg.getDefaultPlatform().isPresent()) {
      return cxxPlatforms.getValue(constructorArg.getDefaultPlatform().get());
    }

    // Otherwise, fallback to the description-level default platform.
    return defaultCxxPlatform;
  }

  @Override
  public Class<CxxTestDescriptionArg> getConstructorArgType() {
    return CxxTestDescriptionArg.class;
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams inputParams,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      final CxxTestDescriptionArg args)
      throws NoSuchBuildTargetException {
    Optional<StripStyle> flavoredStripStyle =
        StripStyle.FLAVOR_DOMAIN.getValue(inputParams.getBuildTarget());
    Optional<LinkerMapMode> flavoredLinkerMapMode =
        LinkerMapMode.FLAVOR_DOMAIN.getValue(inputParams.getBuildTarget());
    inputParams = CxxStrip.removeStripStyleFlavorInParams(inputParams, flavoredStripStyle);
    inputParams =
        LinkerMapMode.removeLinkerMapModeFlavorInParams(inputParams, flavoredLinkerMapMode);
    final BuildRuleParams params = inputParams;

    CxxPlatform cxxPlatform = getCxxPlatform(params.getBuildTarget(), args);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    if (params
        .getBuildTarget()
        .getFlavors()
        .contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      BuildRuleParams paramsWithoutFlavor =
          params.withoutFlavor(CxxCompilationDatabase.COMPILATION_DATABASE);
      CxxLinkAndCompileRules cxxLinkAndCompileRules =
          CxxDescriptionEnhancer.createBuildRulesForCxxBinaryDescriptionArg(
              targetGraph,
              paramsWithoutFlavor,
              resolver,
              cellRoots,
              cxxBuckConfig,
              cxxPlatform,
              args,
              getImplicitFrameworkDeps(args),
              flavoredStripStyle,
              flavoredLinkerMapMode);
      return CxxCompilationDatabase.createCompilationDatabase(
          params, cxxLinkAndCompileRules.compileRules);
    }

    if (params
        .getBuildTarget()
        .getFlavors()
        .contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)) {
      return CxxDescriptionEnhancer.createUberCompilationDatabase(
          cxxPlatforms.getValue(params.getBuildTarget()).isPresent()
              ? params
              : params.withAppendedFlavor(cxxPlatform.getFlavor()),
          resolver);
    }

    if (params.getBuildTarget().getFlavors().contains(CxxDescriptionEnhancer.SANDBOX_TREE_FLAVOR)) {
      return CxxDescriptionEnhancer.createSandboxTreeBuildRule(resolver, args, cxxPlatform, params);
    }

    // Generate the link rule that builds the test binary.
    final CxxLinkAndCompileRules cxxLinkAndCompileRules =
        CxxDescriptionEnhancer.createBuildRulesForCxxBinaryDescriptionArg(
            targetGraph,
            params,
            resolver,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            args,
            getImplicitFrameworkDeps(args),
            flavoredStripStyle,
            flavoredLinkerMapMode);

    // Construct the actual build params we'll use, notably with an added dependency on the
    // CxxLink rule above which builds the test binary.
    BuildRuleParams testParams =
        params
            .copyReplacingDeclaredAndExtraDeps(
                () -> cxxLinkAndCompileRules.deps, params.getExtraDeps())
            .copyAppendingExtraDeps(cxxLinkAndCompileRules.executable.getDeps(ruleFinder));
    testParams = CxxStrip.restoreStripStyleFlavorInParams(testParams, flavoredStripStyle);
    testParams =
        LinkerMapMode.restoreLinkerMapModeFlavorInParams(testParams, flavoredLinkerMapMode);

    // Supplier which expands macros in the passed in test environment.
    ImmutableMap<String, String> testEnv =
        ImmutableMap.copyOf(
            Maps.transformValues(
                args.getEnv(),
                CxxDescriptionEnhancer.MACRO_HANDLER.getExpander(
                    params.getBuildTarget(), cellRoots, resolver)));

    // Supplier which expands macros in the passed in test arguments.
    Supplier<ImmutableList<String>> testArgs =
        () ->
            args.getArgs()
                .stream()
                .map(
                    CxxDescriptionEnhancer.MACRO_HANDLER.getExpander(
                            params.getBuildTarget(), cellRoots, resolver)
                        ::apply)
                .collect(MoreCollectors.toImmutableList());

    Supplier<ImmutableSortedSet<BuildRule>> additionalDeps =
        () -> {
          ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();

          // It's not uncommon for users to add dependencies onto other binaries that they run
          // during the test, so make sure to add them as runtime deps.
          deps.addAll(
              Sets.difference(
                  params.getBuildDeps(), cxxLinkAndCompileRules.getBinaryRule().getBuildDeps()));

          // Add any build-time from any macros embedded in the `env` or `args` parameter.
          for (String part : Iterables.concat(args.getArgs(), args.getEnv().values())) {
            try {
              deps.addAll(
                  CxxDescriptionEnhancer.MACRO_HANDLER.extractBuildTimeDeps(
                      params.getBuildTarget(), cellRoots, resolver, part));
            } catch (MacroException e) {
              throw new HumanReadableException(
                  e, "%s: %s", params.getBuildTarget(), e.getMessage());
            }
          }

          return deps.build();
        };

    CxxTest test;

    CxxTestType type = args.getFramework().orElse(getDefaultTestType());
    switch (type) {
      case GTEST:
        {
          test =
              new CxxGtestTest(
                  testParams,
                  ruleFinder,
                  cxxLinkAndCompileRules.getBinaryRule(),
                  cxxLinkAndCompileRules.executable,
                  testEnv,
                  testArgs,
                  FluentIterable.from(args.getResources())
                      .transform(p -> new PathSourcePath(params.getProjectFilesystem(), p))
                      .toSortedSet(Ordering.natural()),
                  args.getAdditionalCoverageTargets(),
                  additionalDeps,
                  args.getLabels(),
                  args.getContacts(),
                  args.getRunTestSeparately().orElse(false),
                  args.getTestRuleTimeoutMs().map(Optional::of).orElse(defaultTestRuleTimeoutMs),
                  cxxBuckConfig.getMaximumTestOutputSize());
          break;
        }
      case BOOST:
        {
          test =
              new CxxBoostTest(
                  testParams,
                  ruleFinder,
                  cxxLinkAndCompileRules.getBinaryRule(),
                  cxxLinkAndCompileRules.executable,
                  testEnv,
                  testArgs,
                  FluentIterable.from(args.getResources())
                      .transform(p -> new PathSourcePath(params.getProjectFilesystem(), p))
                      .toSortedSet(Ordering.natural()),
                  args.getAdditionalCoverageTargets(),
                  additionalDeps,
                  args.getLabels(),
                  args.getContacts(),
                  args.getRunTestSeparately().orElse(false),
                  args.getTestRuleTimeoutMs().map(Optional::of).orElse(defaultTestRuleTimeoutMs));
          break;
        }
      default:
        {
          Preconditions.checkState(false, "Unhandled C++ test type: %s", type);
          throw new RuntimeException();
        }
    }

    return test;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractCxxTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {

    // Get any parse time deps from the C/C++ platforms.
    extraDepsBuilder.addAll(
        CxxPlatforms.getParseTimeDeps(getCxxPlatform(buildTarget, constructorArg)));

    // Extract parse time deps from flags, args, and environment parameters.
    Iterable<Iterable<String>> macroStrings =
        ImmutableList.<Iterable<String>>builder()
            .add(constructorArg.getArgs())
            .add(constructorArg.getEnv().values())
            .build();
    for (String macroString : Iterables.concat(macroStrings)) {
      try {
        CxxDescriptionEnhancer.MACRO_HANDLER.extractParseTimeDeps(
            buildTarget, cellRoots, macroString, extraDepsBuilder, targetGraphOnlyDepsBuilder);
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
      }
    }

    // Add in any implicit framework deps.
    extraDepsBuilder.addAll(getImplicitFrameworkDeps(constructorArg));

    constructorArg
        .getDepsQuery()
        .ifPresent(
            depsQuery ->
                QueryUtils.extractParseTimeTargets(buildTarget, cellRoots, depsQuery)
                    .forEach(extraDepsBuilder::add));
  }

  public CxxTestType getDefaultTestType() {
    return DEFAULT_TEST_TYPE;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {

    if (flavors.isEmpty()) {
      return true;
    }

    if (flavors.contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      return true;
    }

    if (flavors.contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)) {
      return true;
    }

    if (StripStyle.FLAVOR_DOMAIN.containsAnyOf(flavors)) {
      return true;
    }

    if (LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(flavors)) {
      return true;
    }

    for (Flavor flavor : cxxPlatforms.getFlavors()) {
      if (flavors.equals(ImmutableSet.of(flavor))) {
        return true;
      }
    }

    return false;
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CxxTestDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      final Class<U> metadataClass)
      throws NoSuchBuildTargetException {
    if (!metadataClass.isAssignableFrom(CxxCompilationDatabaseDependencies.class)
        || !buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      return Optional.empty();
    }
    return CxxDescriptionEnhancer.createCompilationDatabaseDependencies(
            buildTarget, cxxPlatforms, resolver, args)
        .map(metadataClass::cast);
  }

  @Override
  public boolean isVersionRoot(ImmutableSet<Flavor> flavors) {
    return true;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractCxxTestDescriptionArg extends CxxBinaryDescription.CommonArg {
    ImmutableSet<String> getContacts();

    Optional<CxxTestType> getFramework();

    ImmutableMap<String, String> getEnv();

    ImmutableList<String> getArgs();

    Optional<Boolean> getRunTestSeparately();

    Optional<Boolean> getUseDefaultTestMain();

    Optional<Long> getTestRuleTimeoutMs();

    @Value.NaturalOrder
    ImmutableSortedSet<Path> getResources();

    ImmutableSet<SourcePath> getAdditionalCoverageTargets();
  }
}
