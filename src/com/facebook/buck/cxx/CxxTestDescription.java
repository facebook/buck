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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class CxxTestDescription implements
    Description<CxxTestDescription.Arg>,
    ImplicitDepsInferringDescription {

  private static final BuildRuleType TYPE = new BuildRuleType("cxx_test");
  private static final CxxTestType DEFAULT_TEST_TYPE = CxxTestType.GTEST;

  private final CxxBuckConfig cxxBuckConfig;

  public CxxTestDescription(CxxBuckConfig cxxBuckConfig) {
    this.cxxBuckConfig = Preconditions.checkNotNull(cxxBuckConfig);
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

    // Generate the link rule that builds the test binary.
    CxxLink cxxLink = CxxDescriptionEnhancer.createBuildRulesForCxxBinaryDescriptionArg(
        params,
        resolver,
        cxxBuckConfig,
        args);

    // Construct the actual build params we'll use, notably with an added dependency on the
    // CxxLink rule above which builds the test binary.
    BuildRuleParams testParams =
        params.copyWithDeps(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(params.getDeclaredDeps())
                .add(cxxLink)
                .build(),
            params.getExtraDeps());

    CxxTest test;

    CxxTestType type = args.framework.or(getDefaultTestType());
    switch (type) {
      case GTEST: {
        test = new CxxGtestTest(
            testParams,
            cxxLink.getOutput(),
            args.labels.get(),
            args.contacts.get(),
            resolver.getAllRules(args.sourceUnderTest.get()));
        break;
      }
      case BOOST: {
        test = new CxxBoostTest(
            testParams,
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

  private Optional<CxxTestType> getTypeFromParams(BuildRuleFactoryParams params) {
    Object rawType = params.getNullableRawAttribute("framework");

    // If not set, return the default test type.
    if (rawType == null) {
      return Optional.of(getDefaultTestType());
    }

    // If the parameter isn't a string, this should be a coercion error, so just bail here
    // and let the type coercer handle the actual error.
    if (!(rawType instanceof String)) {
      return Optional.absent();
    }

    // Try to convert the string to an enum.  If we fail, just return nothing and let the
    // coercer throw the real error.
    try {
      String strType = (String) rawType;
      return Optional.of(CxxTestType.valueOf(strType.toUpperCase()));
    } catch (IllegalArgumentException e) {
      return Optional.absent();
    }
  }

  @Override
  public Iterable<String> findDepsFromParams(BuildRuleFactoryParams params) {
    ImmutableSet.Builder<String> deps = ImmutableSet.builder();

    if (!params.getOptionalListAttribute("lexSrcs").isEmpty()) {
      deps.add(cxxBuckConfig.getLexDep().toString());
    }

    // Attempt to extract the test type from the params, and add an implicit dep on the
    // corresponding test framework library.
    Optional<CxxTestType> type = getTypeFromParams(params);
    if (type.isPresent()) {
      switch (type.get()) {
        case GTEST: {
          deps.add(cxxBuckConfig.getGtestDep().toString());
          break;
        }
        case BOOST: {
          deps.add(cxxBuckConfig.getBoostTestDep().toString());
          break;
        }
        default: {
          break;
        }
      }
    }

    return deps.build();
  }

  public CxxTestType getDefaultTestType() {
    return DEFAULT_TEST_TYPE;
  }

  @SuppressFieldNotInitialized
  public class Arg extends CxxBinaryDescription.Arg {
    public Optional<ImmutableSet<String>> contacts;
    public Optional<ImmutableSet<Label>> labels;
    public Optional<ImmutableSortedSet<BuildTarget>> sourceUnderTest;
    public Optional<CxxTestType> framework;
  }

}
