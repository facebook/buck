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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.regex.Pattern;

public class CxxTestDescriptionTest {

  private CxxTestBuilder createTestBuilder(
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem) throws NoSuchBuildTargetException {
    BuildRule frameworkRule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:framework_rule"))
            .setOut("out")
            .build(resolver, filesystem);
    return new CxxTestBuilder(
        BuildTargetFactory.newInstance("//:test"),
        new CxxBuckConfig(
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of(
                    "cxx",
                    ImmutableMap.of(
                        "gtest_dep", frameworkRule.getBuildTarget().toString(),
                        "gtest_default_test_main_dep",
                        frameworkRule.getBuildTarget().toString(),
                        "boost_test_dep", frameworkRule.getBuildTarget().toString()))).build()),
        CxxTestBuilder.createDefaultPlatform(),
        CxxTestBuilder.createDefaultPlatforms());
  }

  @Test
  public void findDepsFromParams() {
    BuildTarget gtest = BuildTargetFactory.newInstance("//:gtest");
    BuildTarget gtestMain = BuildTargetFactory.newInstance("//:gtest_main");

    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(
        ImmutableMap.of(
            "cxx",
            ImmutableMap.of(
                "gtest_dep", gtest.toString(),
                "gtest_default_test_main_dep", gtestMain.toString()
            )
        )).build();
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new CxxBuckConfig(buckConfig));
    CxxTestDescription desc = new CxxTestDescription(
        cxxBuckConfig,
        cxxPlatform,
        new FlavorDomain<>("platform", ImmutableMap.<Flavor, CxxPlatform>of()),
        /* testRuleTimeoutMs */ Optional.<Long>absent());

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxTestDescription.Arg constructorArg = desc.createUnpopulatedConstructorArg();
    constructorArg.framework = Optional.of(CxxTestType.GTEST);
    constructorArg.env = Optional.of(ImmutableMap.<String, String>of());
    constructorArg.args = Optional.of(ImmutableList.<String>of());
    constructorArg.useDefaultTestMain = Optional.of(true);
    constructorArg.linkerFlags = Optional.of(ImmutableList.<String>of());
    constructorArg.platformLinkerFlags =
        Optional.of(PatternMatchedCollection.<ImmutableList<String>>of());
    Iterable<BuildTarget> implicit = desc.findDepsForTargetFromConstructorArgs(
        target,
        TestCellBuilder.createCellRoots(new FakeProjectFilesystem()),
        constructorArg);

    assertTrue(Iterables.contains(implicit, gtest));
    assertTrue(Iterables.contains(implicit, gtestMain));
  }

  @Test
  public void environmentIsPropagated() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    BuildRule someRule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:some_rule"))
            .setOut("someRule")
            .build(resolver);
    CxxTest cxxTest =
        (CxxTest) createTestBuilder(resolver, filesystem)
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
  public void testArgsArePropagated() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    BuildRule someRule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:some_rule"))
            .setOut("someRule")
            .build(resolver);
    CxxTest cxxTest =
        (CxxTest) createTestBuilder(resolver, filesystem)
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
  public void runTestSeparately() throws Exception {
    for (CxxTestType framework : CxxTestType.values()) {
      ProjectFilesystem filesystem = new FakeProjectFilesystem();
      BuildRuleResolver resolver =
          new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
      CxxTest cxxTest =
          (CxxTest) createTestBuilder(resolver, filesystem)
              .setRunTestSeparately(true)
              .setUseDefaultTestMain(true)
              .setFramework(framework)
              .build(resolver);
      assertTrue(cxxTest.runTestSeparately());
    }
  }

  @Test
  public void runtimeDepOnDeps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget cxxBinaryTarget = BuildTargetFactory.newInstance("//:dep");
    BuildTarget cxxLibraryTarget = BuildTargetFactory.newInstance("//:lib");
    CxxBinaryBuilder cxxBinaryBuilder = new CxxBinaryBuilder(cxxBinaryTarget);
    CxxLibraryBuilder cxxLibraryBuilder = new CxxLibraryBuilder(cxxLibraryTarget)
        .setDeps(ImmutableSortedSet.of(cxxBinaryTarget));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(cxxLibraryBuilder.build(), cxxBinaryBuilder.build()),
            new BuildTargetNodeToBuildRuleTransformer());
    BuildRule cxxBinary = cxxBinaryBuilder.build(resolver, filesystem);
    cxxLibraryBuilder.build(resolver, filesystem);
    CxxTestBuilder cxxTestBuilder = createTestBuilder(resolver, filesystem)
        .setDeps(ImmutableSortedSet.of(cxxLibraryTarget));
    CxxTest cxxTest = (CxxTest) cxxTestBuilder.build(resolver, filesystem);
    assertThat(
        BuildRules.getTransitiveRuntimeDeps(cxxTest),
        Matchers.hasItem(cxxBinary));
  }

  @Test
  public void locationMacro() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    Genrule dep =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    CxxTestBuilder builder =
        createTestBuilder(resolver, filesystem)
            .setLinkerFlags(ImmutableList.of("--linker-script=$(location //:dep)"));
    assertThat(
        builder.findImplicitDeps(),
        Matchers.hasItem(dep.getBuildTarget()));
    CxxTest test = (CxxTest) builder.build(resolver);
    CxxLink binary =
        (CxxLink) resolver.getRule(
            CxxDescriptionEnhancer.createCxxLinkTarget(test.getBuildTarget()));
    assertThat(
        binary.getArgs(),
        Matchers.hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(
        binary.getDeps(),
        Matchers.hasItem(dep));
  }


  @Test
  public void platformLinkerFlagsLocationMacroWithMatch() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    Genrule dep =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    CxxTestBuilder builder =
        createTestBuilder(resolver, filesystem)
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<String>>()
                    .add(
                        Pattern.compile(
                            Pattern.quote(
                                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor().toString())),
                        ImmutableList.of("--linker-script=$(location //:dep)"))
                    .build());
    assertThat(
        builder.findImplicitDeps(),
        Matchers.hasItem(dep.getBuildTarget()));
    CxxTest test = (CxxTest) builder.build(resolver);
    CxxLink binary =
        (CxxLink) resolver.getRule(
            CxxDescriptionEnhancer.createCxxLinkTarget(test.getBuildTarget()));
    assertThat(
        binary.getArgs(),
        Matchers.hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(
        binary.getDeps(),
        Matchers.hasItem(dep));
  }

  @Test
  public void platformLinkerFlagsLocationMacroWithoutMatch() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    Genrule dep =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    CxxTestBuilder builder =
        createTestBuilder(resolver, filesystem)
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<String>>()
                    .add(
                        Pattern.compile("nothing matches this string"),
                        ImmutableList.of("--linker-script=$(location //:dep)"))
                    .build());
    assertThat(
        builder.findImplicitDeps(),
        Matchers.hasItem(dep.getBuildTarget()));
    CxxTest test = (CxxTest) builder.build(resolver);
    CxxLink binary =
        (CxxLink) resolver.getRule(
            CxxDescriptionEnhancer.createCxxLinkTarget(test.getBuildTarget()));
    assertThat(
        binary.getArgs(),
        Matchers.not(
            Matchers.hasItem(
                String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath()))));
    assertThat(
        binary.getDeps(),
        Matchers.not(Matchers.hasItem(dep)));
  }

}
