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

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxTestDescriptionTest {

  @Test
  public void findDepsFromParams() {
    BuildTarget gtest = BuildTargetFactory.newInstance("//:gtest");

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.of(
            "cxx",
            ImmutableMap.of("gtest_dep", gtest.toString())));
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new CxxBuckConfig(buckConfig));
    CxxTestDescription desc = new CxxTestDescription(
        cxxBuckConfig,
        cxxPlatform,
        new FlavorDomain<>("platform", ImmutableMap.<Flavor, CxxPlatform>of()));

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxTestDescription.Arg constructorArg = desc.createUnpopulatedConstructorArg();
    constructorArg.framework = Optional.of(CxxTestType.GTEST);
    constructorArg.lexSrcs = Optional.of(ImmutableList.<SourcePath>of());
    Iterable<BuildTarget> implicit = desc
        .findDepsForTargetFromConstructorArgs(target, constructorArg);

    assertTrue(Iterables.contains(implicit, gtest));
  }

  @Test
  public void environmentIsPropagated() {
    ImmutableMap<String, String> env = ImmutableMap.of("TEST", "value");
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRule gtest =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gtest"))
            .setOut("out")
            .build(resolver);
    CxxTestBuilder builder =
        new CxxTestBuilder(
            BuildTargetFactory.newInstance("//:test"),
            new CxxBuckConfig(
                new FakeBuckConfig(
                    ImmutableMap.of(
                        "cxx",
                        ImmutableMap.of("gtest_dep", gtest.getBuildTarget().toString())))),
            CxxTestBuilder.createDefaultPlatform(),
            CxxTestBuilder.createDefaultPlatforms());
    CxxTest test = (CxxTest) builder
          .setEnv(env)
          .build(resolver);
    ImmutableList<Step> steps =
        test.runTests(
            FakeBuildContext.NOOP_CONTEXT,
            TestExecutionContext.newInstance(),
            /* isDryRun */ false,
            /* isShufflingTests */ false,
            TestSelectorList.empty(),
            TestRule.NOOP_REPORTING_CALLBACK);
    CxxTestStep testStep = (CxxTestStep) Iterables.getLast(steps);
    assertThat(testStep.getEnv(), Matchers.equalTo(env));
  }

}
