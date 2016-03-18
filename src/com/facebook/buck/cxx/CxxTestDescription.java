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
import com.facebook.buck.model.HasSourceUnderTest;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.macros.MacroException;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.nio.file.Path;

public class CxxTestDescription implements
    Description<CxxTestDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<CxxTestDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("cxx_test");
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

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams inputParams,
      final BuildRuleResolver resolver,
      final A args) throws NoSuchBuildTargetException {
    Optional<CxxStrip.StripStyle> flavoredStripStyle =
        CxxStrip.StripStyle.FLAVOR_DOMAIN.getValue(inputParams.getBuildTarget());
    final BuildRuleParams params =
        CxxStrip.removeStripStyleFlavorInParams(inputParams, flavoredStripStyle);

    CxxPlatform cxxPlatform = cxxPlatforms.getValue(params.getBuildTarget()).or(defaultCxxPlatform);
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    if (params.getBuildTarget().getFlavors()
          .contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      return CxxDescriptionEnhancer.createCompilationDatabase(
          params,
          resolver,
          pathResolver,
          cxxPlatform,
          cxxBuckConfig.getPreprocessMode(),
          args);
    }

    // Generate the link rule that builds the test binary.
    final CxxLinkAndCompileRules cxxLinkAndCompileRules =
        CxxDescriptionEnhancer.createBuildRulesForCxxBinaryDescriptionArg(
            params,
            resolver,
            cxxPlatform,
            args,
            cxxBuckConfig.getPreprocessMode(),
            flavoredStripStyle);

    // Construct the actual build params we'll use, notably with an added dependency on the
    // CxxLink rule above which builds the test binary.
    BuildRuleParams testParams =
        params.appendExtraDeps(cxxLinkAndCompileRules.executable.getDeps(pathResolver));
    testParams = CxxStrip.restoreStripStyleFlavorInParams(testParams, flavoredStripStyle);

    // Supplier which expands macros in the passed in test environment.
    Supplier<ImmutableMap<String, String>> testEnv =
        new Supplier<ImmutableMap<String, String>>() {
          @Override
          public ImmutableMap<String, String> get() {
            return ImmutableMap.copyOf(
                Maps.transformValues(
                    args.env.or(ImmutableMap.<String, String>of()),
                    CxxDescriptionEnhancer.MACRO_HANDLER.getExpander(
                        params.getBuildTarget(),
                        params.getCellRoots(),
                        resolver)));
          }
        };

    // Supplier which expands macros in the passed in test arguments.
    Supplier<ImmutableList<String>> testArgs =
        new Supplier<ImmutableList<String>>() {
          @Override
          public ImmutableList<String> get() {
            return FluentIterable.from(args.args.or(ImmutableList.<String>of()))
                .transform(
                    CxxDescriptionEnhancer.MACRO_HANDLER.getExpander(
                        params.getBuildTarget(),
                        params.getCellRoots(),
                        resolver))
                .toList();
          }
        };

    Supplier<ImmutableSortedSet<BuildRule>> additionalDeps =
        new Supplier<ImmutableSortedSet<BuildRule>>() {
          @Override
          public ImmutableSortedSet<BuildRule> get() {
            ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();

            // It's not uncommon for users to add dependencies onto other binaries that they run
            // during the test, so make sure to add them as runtime deps.
            deps.addAll(
                Sets.difference(
                    params.getDeps(),
                    cxxLinkAndCompileRules.getBinaryRule().getDeps()));

            // Add any build-time from any macros embedded in the `env` or `args` parameter.
            for (String part :
                Iterables.concat(
                    args.args.or(ImmutableList.<String>of()),
                    args.env.or(ImmutableMap.<String, String>of()).values())) {
              try {
                deps.addAll(
                    CxxDescriptionEnhancer.MACRO_HANDLER.extractBuildTimeDeps(
                        params.getBuildTarget(),
                        params.getCellRoots(),
                        resolver,
                        part));
              } catch (MacroException e) {
                throw new HumanReadableException(
                    e,
                    "%s: %s",
                    params.getBuildTarget(),
                    e.getMessage());
              }
            }

            return deps.build();
          }
        };

    CxxTest test;

    CxxTestType type = args.framework.or(getDefaultTestType());
    switch (type) {
      case GTEST: {
        test = new CxxGtestTest(
            testParams,
            pathResolver,
            cxxLinkAndCompileRules.getBinaryRule(),
            cxxLinkAndCompileRules.executable,
            testEnv,
            testArgs,
            additionalDeps,
            args.labels.get(),
            args.contacts.get(),
            resolver.getAllRules(args.sourceUnderTest.get()),
            args.runTestSeparately.or(false),
            args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
            cxxBuckConfig.getMaximumTestOutputSize());
        break;
      }
      case BOOST: {
        test = new CxxBoostTest(
            testParams,
            pathResolver,
            cxxLinkAndCompileRules.getBinaryRule(),
            cxxLinkAndCompileRules.executable,
            testEnv,
            testArgs,
            additionalDeps,
            args.labels.get(),
            args.contacts.get(),
            resolver.getAllRules(args.sourceUnderTest.get()),
            args.runTestSeparately.or(false),
            args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs));
        break;
      }
      default: {
        Preconditions.checkState(false, "Unhandled C++ test type: %s", type);
        throw new RuntimeException();
      }
    }

    return test;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Function<Optional<String>, Path> cellRoots,
      Arg constructorArg) {

    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    // Get any parse time deps from the C/C++ platforms.
    deps.addAll(
        CxxPlatforms.getParseTimeDeps(
            cxxPlatforms
                .getValue(buildTarget.getFlavors())
                .or(defaultCxxPlatform)));

    // Extract parse time deps from flags, args, and environment parameters.
    Iterable<Iterable<String>> macroStrings =
        ImmutableList.<Iterable<String>>builder()
            .add(constructorArg.linkerFlags.get())
            .addAll(constructorArg.platformLinkerFlags.get().getValues())
            .add(constructorArg.args.get())
            .add(constructorArg.env.get().values())
            .build();
    for (String macroString : Iterables.concat(macroStrings)) {
      try {
        deps.addAll(
            CxxDescriptionEnhancer.MACRO_HANDLER.extractParseTimeDeps(
                buildTarget,
                cellRoots,
                macroString));
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
      }
    }

    CxxTestType type = constructorArg.framework.or(getDefaultTestType());
    switch (type) {
      case GTEST: {
        deps.add(cxxBuckConfig.getGtestDep());
        boolean useDefaultTestMain = constructorArg.useDefaultTestMain.or(true);
        if (useDefaultTestMain) {
          deps.add(cxxBuckConfig.getGtestDefaultTestMainDep());
        }
        break;
      }
      case BOOST: {
        deps.add(cxxBuckConfig.getBoostTestDep());
        break;
      }
      default: {
        break;
      }
    }

    return deps.build();
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

    if (CxxStrip.StripStyle.FLAVOR_DOMAIN.containsAnyOf(flavors)) {
      return true;
    }

    for (Flavor flavor : cxxPlatforms.getFlavors()) {
      if (flavors.equals(ImmutableSet.of(flavor))) {
        return true;
      }
    }

    return false;
  }

  @SuppressFieldNotInitialized
  public class Arg extends CxxBinaryDescription.Arg implements HasSourceUnderTest {
    public Optional<ImmutableSet<String>> contacts;
    public Optional<ImmutableSet<Label>> labels;
    public Optional<ImmutableSortedSet<BuildTarget>> sourceUnderTest;
    public Optional<CxxTestType> framework;
    public Optional<ImmutableMap<String, String>> env;
    public Optional<ImmutableList<String>> args;
    public Optional<Boolean> runTestSeparately;
    public Optional<Boolean> useDefaultTestMain;
    public Optional<Long> testRuleTimeoutMs;

    @Override
    public ImmutableSortedSet<BuildTarget> getSourceUnderTest() {
      return sourceUnderTest.get();
    }
  }

}
