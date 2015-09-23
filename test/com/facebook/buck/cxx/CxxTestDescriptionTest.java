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
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.Collection;
import java.util.Set;

public class CxxTestDescriptionTest {

  private CxxTestBuilder createTestBuilder(
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      Collection<TargetNode<?>> targetNodes) {
    BuildRule frameworkRule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:framwork_rule"))
            .setOut("out")
            .build(resolver, filesystem, targetNodes);
    return new CxxTestBuilder(
        BuildTargetFactory.newInstance("//:test"),
        new CxxBuckConfig(
            new FakeBuckConfig(
                ImmutableMap.of(
                    "cxx",
                    ImmutableMap.of(
                        "gtest_dep", frameworkRule.getBuildTarget().toString(),
                        "gtest_default_test_main_dep",
                        frameworkRule.getBuildTarget().toString(),
                        "boost_test_dep", frameworkRule.getBuildTarget().toString())))),
        CxxTestBuilder.createDefaultPlatform(),
        CxxTestBuilder.createDefaultPlatforms());
  }

  @Test
  public void findDepsFromParams() {
    BuildTarget gtest = BuildTargetFactory.newInstance("//:gtest");
    BuildTarget gtestMain = BuildTargetFactory.newInstance("//:gtest_main");

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.of(
            "cxx",
            ImmutableMap.of(
                            "gtest_dep", gtest.toString(),
                            "gtest_default_test_main_dep", gtestMain.toString()
                            )
                        ));
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new CxxBuckConfig(buckConfig));
    CxxTestDescription desc = new CxxTestDescription(
        cxxBuckConfig,
        cxxPlatform,
        new FlavorDomain<>("platform", ImmutableMap.<Flavor, CxxPlatform>of()));

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxTestDescription.Arg constructorArg = desc.createUnpopulatedConstructorArg();
    constructorArg.framework = Optional.of(CxxTestType.GTEST);
    constructorArg.env = Optional.absent();
    constructorArg.args = Optional.absent();
    constructorArg.useDefaultTestMain = Optional.of(true);
    constructorArg.lexSrcs = Optional.of(ImmutableList.<SourcePath>of());
    Iterable<BuildTarget> implicit = desc
        .findDepsForTargetFromConstructorArgs(target, constructorArg);

    assertTrue(Iterables.contains(implicit, gtest));
    assertTrue(Iterables.contains(implicit, gtestMain));
  }

  @Test
  public void environmentIsPropagated() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    Set<TargetNode<?>> targetNodes = Sets.newTreeSet();
    BuildRule someRule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:some_rule"))
            .setOut("someRule")
            .build(resolver);
    CxxTest cxxTest =
        (CxxTest) createTestBuilder(resolver, filesystem, targetNodes)
            .setEnv(ImmutableMap.of("TEST", "value $(location //:some_rule)"))
            .build(resolver);
    TestRunningOptions options =
        TestRunningOptions.builder()
            .setDryRun(false)
            .setTestSelectorList(TestSelectorList.empty())
            .build();
    ImmutableList<Step> steps =
        cxxTest.runTests(
            FakeBuildContext.NOOP_CONTEXT,
            TestExecutionContext.newInstance(),
            options,
            TestRule.NOOP_REPORTING_CALLBACK);
    CxxTestStep testStep = (CxxTestStep) Iterables.getLast(steps);
    assertThat(
        testStep.getEnv(),
        Matchers.equalTo(
            ImmutableMap.of(
                "TEST",
                "value " +
                    Preconditions.checkNotNull(someRule.getPathToOutput()).toAbsolutePath())));
  }

  @Test
  public void testArgsArePropagated() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    Set<TargetNode<?>> targetNodes = Sets.newTreeSet();
    BuildRule someRule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:some_rule"))
            .setOut("someRule")
            .build(resolver);
    CxxTest cxxTest =
        (CxxTest) createTestBuilder(resolver, filesystem, targetNodes)
            .setArgs(ImmutableList.of("value $(location //:some_rule)"))
            .build(resolver);
    TestRunningOptions testOptions =
          TestRunningOptions.builder()
          .setDryRun(false)
          .setShufflingTests(false)
          .setTestSelectorList(TestSelectorList.empty())
          .build();
    ImmutableList<Step> steps =
        cxxTest.runTests(
            FakeBuildContext.NOOP_CONTEXT,
            TestExecutionContext.newInstance(),
            testOptions,
            TestRule.NOOP_REPORTING_CALLBACK);
    CxxTestStep testStep = (CxxTestStep) Iterables.getLast(steps);
    assertThat(
        testStep.getCommand(),
        Matchers.hasItem(
            "value " + Preconditions.checkNotNull(someRule.getPathToOutput()).toAbsolutePath()));
  }

  @Test
  public void runTestSeparately() {
    for (CxxTestType framework : CxxTestType.values()) {
      ProjectFilesystem filesystem = new FakeProjectFilesystem();
      BuildRuleResolver resolver = new BuildRuleResolver();
      Set<TargetNode<?>> targetNodes = Sets.newTreeSet();
      CxxTest cxxTest =
          (CxxTest) createTestBuilder(resolver, filesystem, targetNodes)
              .setRunTestSeparately(true)
              .setUseDefaultTestMain(true)
              .setFramework(framework)
              .build(resolver);
      assertTrue(cxxTest.runTestSeparately());
    }
  }

  private ImmutableSortedSet<BuildRule> getTransitiveRuntimeDeps(HasRuntimeDeps rule) {
    final Set<BuildRule> runtimeDeps = Sets.newHashSet();
    AbstractBreadthFirstTraversal<BuildRule> visitor =
        new AbstractBreadthFirstTraversal<BuildRule>(rule.getRuntimeDeps()) {
          @Override
          public ImmutableSet<BuildRule> visit(BuildRule rule) {
            runtimeDeps.add(rule);
            if (rule instanceof HasRuntimeDeps) {
              return ((HasRuntimeDeps) rule).getRuntimeDeps();
            }
            return ImmutableSet.of();
          }
        };
    visitor.start();
    return ImmutableSortedSet.copyOf(runtimeDeps);
  }


  @Test
  public void runtimeDepOnDeps() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    Set<TargetNode<?>> targetNodes = Sets.newTreeSet();
    BuildRule cxxBinary =
        new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver, filesystem, targetNodes);
    BuildRule cxxLibrary =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setDeps(ImmutableSortedSet.of(cxxBinary.getBuildTarget()))
            .build(resolver, filesystem, targetNodes);
    CxxTest cxxTest =
        (CxxTest) createTestBuilder(resolver, filesystem, targetNodes)
            .setDeps(ImmutableSortedSet.of(cxxLibrary.getBuildTarget()))
            .build(resolver, filesystem, targetNodes);
    assertThat(
        getTransitiveRuntimeDeps(cxxTest),
        Matchers.hasItem(cxxBinary));
  }

}
