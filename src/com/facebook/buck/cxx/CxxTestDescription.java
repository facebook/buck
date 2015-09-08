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
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.HasSourceUnderTest;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroException;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
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

public class CxxTestDescription implements
    Description<CxxTestDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<CxxTestDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("cxx_test");
  private static final CxxTestType DEFAULT_TEST_TYPE = CxxTestType.GTEST;

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.<String, MacroExpander>of(
              "location", new LocationMacroExpander()));

  private final CxxBuckConfig cxxBuckConfig;
  private final CxxPlatform defaultCxxPlatform;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public CxxTestDescription(
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.cxxPlatforms = cxxPlatforms;
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
  public <A extends Arg> CxxTest createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args) {

    // Extract the platform from the flavor, falling back to the default platform if none are
    // found.
    CxxPlatform cxxPlatform;
    try {
      cxxPlatform = cxxPlatforms
          .getValue(ImmutableSet.copyOf(params.getBuildTarget().getFlavors()))
          .or(defaultCxxPlatform);
    } catch (FlavorDomainException e) {
      throw new HumanReadableException("%s: %s", params.getBuildTarget(), e.getMessage());
    }

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // Generate the link rule that builds the test binary.
    final CxxDescriptionEnhancer.CxxLinkAndCompileRules cxxLinkAndCompileRules =
        CxxDescriptionEnhancer.createBuildRulesForCxxBinaryDescriptionArg(
            targetGraph,
            params,
            resolver,
            cxxPlatform,
            args,
            cxxBuckConfig.getPreprocessMode());

    // Construct the actual build params we'll use, notably with an added dependency on the
    // CxxLink rule above which builds the test binary.
    BuildRuleParams testParams =
        params.appendExtraDeps(cxxLinkAndCompileRules.executable.getDeps(pathResolver));

    // Supplier which expands macros in the passed in test environment.
    Supplier<ImmutableMap<String, String>> testEnv =
        new Supplier<ImmutableMap<String, String>>() {
          @Override
          public ImmutableMap<String, String> get() {
            return ImmutableMap.copyOf(
                Maps.transformValues(
                    args.env.or(ImmutableMap.<String, String>of()),
                    MACRO_HANDLER.getExpander(
                        params.getBuildTarget(),
                        resolver,
                        params.getProjectFilesystem())));
          }
        };

    // Supplier which expands macros in the passed in test arguments.
    Supplier<ImmutableList<String>> testArgs =
        new Supplier<ImmutableList<String>>() {
          @Override
          public ImmutableList<String> get() {
            return FluentIterable.from(args.args.or(ImmutableList.<String>of()))
                .transform(
                    MACRO_HANDLER.getExpander(
                        params.getBuildTarget(),
                        resolver,
                        params.getProjectFilesystem()))
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
                Sets.difference(params.getDeps(), cxxLinkAndCompileRules.cxxLink.getDeps()));

            // Add any build-time from any macros embedded in the `env` or `args` parameter.
            for (String part :
                Iterables.concat(
                    args.args.or(ImmutableList.<String>of()),
                    args.env.or(ImmutableMap.<String, String>of()).values())) {
              try {
                deps.addAll(
                    MACRO_HANDLER.extractAdditionalBuildTimeDeps(
                        params.getBuildTarget(),
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
            cxxLinkAndCompileRules.executable,
            testEnv,
            testArgs,
            additionalDeps,
            args.labels.get(),
            args.contacts.get(),
            resolver.getAllRules(args.sourceUnderTest.get()),
            args.runTestSeparately.or(false),
            cxxBuckConfig.getMaximumTestOutputSize());
        break;
      }
      case BOOST: {
        test = new CxxBoostTest(
            testParams,
            pathResolver,
            cxxLinkAndCompileRules.executable,
            testEnv,
            testArgs,
            additionalDeps,
            args.labels.get(),
            args.contacts.get(),
            resolver.getAllRules(args.sourceUnderTest.get()),
            args.runTestSeparately.or(false));
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
      Arg constructorArg) {

    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    if (!constructorArg.lexSrcs.get().isEmpty()) {
      deps.add(cxxBuckConfig.getLexDep());
    }

    // Extract parse time deps from args and environment parameters.
    for (String part :
          Iterables.concat(
              constructorArg.args.or(ImmutableList.<String>of()),
              constructorArg.env.or(ImmutableMap.<String, String>of()).values())) {
      try {
        deps.addAll(MACRO_HANDLER.extractParseTimeDeps(buildTarget, part));
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

    @Override
    public ImmutableSortedSet<BuildTarget> getSourceUnderTest() {
      return sourceUnderTest.get();
    }
  }

}
