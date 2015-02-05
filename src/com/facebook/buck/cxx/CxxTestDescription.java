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
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class CxxTestDescription implements
    Description<CxxTestDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<CxxTestDescription.Arg> {

  private static final BuildRuleType TYPE = ImmutableBuildRuleType.of("cxx_test");
  private static final CxxTestType DEFAULT_TEST_TYPE = CxxTestType.GTEST;

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
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

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

    // Generate the link rule that builds the test binary.
    CxxLink cxxLink = CxxDescriptionEnhancer.createBuildRulesForCxxBinaryDescriptionArg(
        params,
        resolver,
        cxxPlatform,
        args);

    // Construct the actual build params we'll use, notably with an added dependency on the
    // CxxLink rule above which builds the test binary.
    BuildRuleParams testParams =
        params.copyWithDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(params.getDeclaredDeps())
                    .add(cxxLink)
                    .build()),
            Suppliers.ofInstance(params.getExtraDeps()));

    CxxTest test;

    CxxTestType type = args.framework.or(getDefaultTestType());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    switch (type) {
      case GTEST: {
        test = new CxxGtestTest(
            testParams,
            pathResolver,
            cxxLink.getOutput(),
            args.labels.get(),
            args.contacts.get(),
            resolver.getAllRules(args.sourceUnderTest.get()));
        break;
      }
      case BOOST: {
        test = new CxxBoostTest(
            testParams,
            pathResolver,
            cxxLink.getOutput(),
            args.labels.get(),
            args.contacts.get(),
            resolver.getAllRules(args.sourceUnderTest.get()));
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

    CxxTestType type = constructorArg.framework.or(getDefaultTestType());
    switch (type) {
      case GTEST: {
        deps.add(cxxBuckConfig.getGtestDep());
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

    @Override
    public ImmutableSortedSet<BuildTarget> getSourceUnderTest() {
      return sourceUnderTest.get();
    }
  }

}
