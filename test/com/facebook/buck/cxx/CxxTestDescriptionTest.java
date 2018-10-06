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

import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildRules;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxTestDescriptionTest {

  private final CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(FakeBuckConfig.builder().build());

  private void addFramework(ActionGraphBuilder graphBuilder, ProjectFilesystem filesystem)
      throws NoSuchBuildTargetException {
    GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:framework_rule"))
        .setOut("out")
        .build(graphBuilder, filesystem);
  }

  private CxxTestBuilder createTestBuilder() throws NoSuchBuildTargetException {
    return createTestBuilder("//:test");
  }

  private CxxTestBuilder createTestBuilder(String target) throws NoSuchBuildTargetException {
    return new CxxTestBuilder(
        BuildTargetFactory.newInstance(target),
        cxxBuckConfig,
        CxxPlatformUtils.DEFAULT_PLATFORM,
        CxxTestUtils.createDefaultPlatforms());
  }

  @Test
  public void findDepsFromParams() {
    BuildTarget gtest = BuildTargetFactory.newInstance("//:gtest");
    BuildTarget gtestMain = BuildTargetFactory.newInstance("//:gtest_main");

    CxxBuckConfig cxxBuckConfig =
        new CxxBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "cxx",
                        ImmutableMap.of(
                            "gtest_dep", gtest.toString(),
                            "gtest_default_test_main_dep", gtestMain.toString())))
                .build());

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxTestBuilder builder =
        new CxxTestBuilder(target, cxxBuckConfig)
            .setFramework(CxxTestType.GTEST)
            .setUseDefaultTestMain(true);
    ImmutableSortedSet<BuildTarget> implicit = builder.findImplicitDeps();

    assertThat(implicit, hasItem(gtest));
    assertThat(implicit, hasItem(gtestMain));
  }

  @Test
  public void environmentIsPropagated() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    addFramework(graphBuilder, filesystem);
    BuildRule someRule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:some_rule"))
            .setOut("someRule")
            .build(graphBuilder);
    CxxTestBuilder builder =
        createTestBuilder()
            .setEnv(
                ImmutableMap.of(
                    "TEST",
                    StringWithMacrosUtils.format(
                        "value %s", LocationMacro.of(someRule.getBuildTarget()))));
    CxxTest cxxTest = builder.build(graphBuilder);
    TestRunningOptions options =
        TestRunningOptions.builder().setTestSelectorList(TestSelectorList.empty()).build();
    ImmutableList<Step> steps =
        cxxTest.runTests(
            TestExecutionContext.newInstance(),
            options,
            FakeBuildContext.withSourcePathResolver(pathResolver),
            TestRule.NOOP_REPORTING_CALLBACK);
    CxxTestStep testStep = (CxxTestStep) Iterables.getLast(steps);
    assertThat(
        testStep.getEnv(),
        Matchers.equalTo(
            Optional.of(
                ImmutableMap.of(
                    "TEST",
                    "value "
                        + pathResolver.getAbsolutePath(
                            Objects.requireNonNull(someRule.getSourcePathToOutput()))))));
  }

  @Test
  public void testArgsArePropagated() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    addFramework(graphBuilder, filesystem);
    BuildRule someRule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:some_rule"))
            .setOut("someRule")
            .build(graphBuilder);
    CxxTestBuilder builder =
        createTestBuilder()
            .setArgs(
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "value %s", LocationMacro.of(someRule.getBuildTarget()))));
    CxxTest cxxTest = builder.build(graphBuilder);
    TestRunningOptions testOptions =
        TestRunningOptions.builder()
            .setShufflingTests(false)
            .setTestSelectorList(TestSelectorList.empty())
            .build();
    ImmutableList<Step> steps =
        cxxTest.runTests(
            TestExecutionContext.newInstance(),
            testOptions,
            FakeBuildContext.withSourcePathResolver(pathResolver),
            TestRule.NOOP_REPORTING_CALLBACK);
    CxxTestStep testStep = (CxxTestStep) Iterables.getLast(steps);
    assertThat(
        testStep.getCommand(),
        hasItem(
            "value "
                + pathResolver.getAbsolutePath(
                    Objects.requireNonNull(someRule.getSourcePathToOutput()))));
  }

  @Test
  public void runTestSeparately() {
    for (CxxTestType framework : CxxTestType.values()) {
      ProjectFilesystem filesystem = new FakeProjectFilesystem();
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      addFramework(graphBuilder, filesystem);
      CxxTestBuilder builder =
          createTestBuilder()
              .setRunTestSeparately(true)
              .setUseDefaultTestMain(true)
              .setFramework(framework);
      CxxTest cxxTest = builder.build(graphBuilder);
      assertTrue(cxxTest.runTestSeparately());
    }
  }

  @Test
  public void runtimeDepOnDeps() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget cxxBinaryTarget = BuildTargetFactory.newInstance("//:dep");
    BuildTarget cxxLibraryTarget = BuildTargetFactory.newInstance("//:lib");
    CxxBinaryBuilder cxxBinaryBuilder = new CxxBinaryBuilder(cxxBinaryTarget);
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(cxxLibraryTarget).setDeps(ImmutableSortedSet.of(cxxBinaryTarget));
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(cxxLibraryBuilder.build(), cxxBinaryBuilder.build()));
    addFramework(graphBuilder, filesystem);
    BuildRule cxxBinary = cxxBinaryBuilder.build(graphBuilder, filesystem);
    cxxLibraryBuilder.build(graphBuilder, filesystem);
    CxxTestBuilder cxxTestBuilder =
        createTestBuilder().setDeps(ImmutableSortedSet.of(cxxLibraryTarget));
    CxxTest cxxTest = cxxTestBuilder.build(graphBuilder, filesystem);
    assertThat(
        BuildRules.getTransitiveRuntimeDeps(cxxTest, graphBuilder),
        hasItem(cxxBinary.getBuildTarget()));
  }

  @Test
  public void locationMacro() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    CxxTestBuilder builder =
        createTestBuilder()
            .setLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))));
    addFramework(graphBuilder, filesystem);
    assertThat(builder.build().getExtraDeps(), hasItem(dep.getBuildTarget()));
    CxxTest test = builder.build(graphBuilder);
    CxxLink binary =
        (CxxLink)
            graphBuilder.getRule(
                CxxDescriptionEnhancer.createCxxLinkTarget(
                    test.getBuildTarget(), Optional.empty()));
    assertThat(
        Arg.stringify(binary.getArgs(), pathResolver),
        hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(binary.getBuildDeps(), hasItem(dep));
  }

  @Test
  public void linkerFlagsLocationMacro() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    CxxTestBuilder builder =
        createTestBuilder("//:rule")
            .setLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))));
    assertThat(builder.build().getExtraDeps(), hasItem(dep.getBuildTarget()));
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    addFramework(graphBuilder, filesystem);
    CxxTest test = builder.build(graphBuilder);
    CxxLink binary =
        (CxxLink)
            graphBuilder.getRule(
                CxxDescriptionEnhancer.createCxxLinkTarget(
                    test.getBuildTarget(), Optional.empty()));
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(binary.getArgs(), pathResolver),
        hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(binary.getBuildDeps(), hasItem(dep));
  }

  @Test
  public void platformLinkerFlagsLocationMacroWithMatch() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    CxxTestBuilder builder =
        createTestBuilder()
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<StringWithMacros>>()
                    .add(
                        Pattern.compile(
                            Pattern.quote(
                                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor().toString())),
                        ImmutableList.of(
                            StringWithMacrosUtils.format(
                                "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))))
                    .build());
    addFramework(graphBuilder, filesystem);
    assertThat(builder.build().getExtraDeps(), hasItem(dep.getBuildTarget()));
    CxxTest test = builder.build(graphBuilder);
    CxxLink binary =
        (CxxLink)
            graphBuilder.getRule(
                CxxDescriptionEnhancer.createCxxLinkTarget(
                    test.getBuildTarget(), Optional.empty()));
    assertThat(
        Arg.stringify(binary.getArgs(), pathResolver),
        hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(binary.getBuildDeps(), hasItem(dep));
  }

  @Test
  public void platformLinkerFlagsLocationMacroWithoutMatch() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    addFramework(graphBuilder, filesystem);
    CxxTestBuilder builder =
        createTestBuilder()
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<StringWithMacros>>()
                    .add(
                        Pattern.compile("nothing matches this string"),
                        ImmutableList.of(
                            StringWithMacrosUtils.format(
                                "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))))
                    .build());
    assertThat(builder.build().getExtraDeps(), hasItem(dep.getBuildTarget()));
    CxxTest test = builder.build(graphBuilder);
    CxxLink binary =
        (CxxLink)
            graphBuilder.getRule(
                CxxDescriptionEnhancer.createCxxLinkTarget(
                    test.getBuildTarget(), Optional.empty()));
    assertThat(
        Arg.stringify(binary.getArgs(), pathResolver),
        Matchers.not(
            hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath()))));
    assertThat(binary.getBuildDeps(), Matchers.not(hasItem(dep)));
  }

  @Test
  public void resourcesAffectRuleKey() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path resource = filesystem.getPath("resource");
    filesystem.touch(resource);
    for (CxxTestType framework : CxxTestType.values()) {
      // Create a test rule without resources attached.
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      addFramework(graphBuilder, filesystem);
      CxxTestBuilder builder = createTestBuilder().setFramework(framework);
      CxxTest cxxTestWithoutResources = builder.build(graphBuilder, filesystem);
      RuleKey ruleKeyWithoutResource = getRuleKey(graphBuilder, cxxTestWithoutResources);

      // Create a rule with a resource attached.
      graphBuilder = new TestActionGraphBuilder();
      addFramework(graphBuilder, filesystem);
      builder =
          createTestBuilder().setFramework(framework).setResources(ImmutableSortedSet.of(resource));
      CxxTest cxxTestWithResources = builder.build(graphBuilder, filesystem);
      RuleKey ruleKeyWithResource = getRuleKey(graphBuilder, cxxTestWithResources);

      // Verify that their rule keys are different.
      assertThat(ruleKeyWithoutResource, Matchers.not(Matchers.equalTo(ruleKeyWithResource)));
    }
  }

  @Test
  public void resourcesAreInputs() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path resource = filesystem.getPath("resource");
    filesystem.touch(resource);
    for (CxxTestType framework : CxxTestType.values()) {
      TargetNode<?> cxxTestWithResources =
          createTestBuilder()
              .setFramework(framework)
              .setResources(ImmutableSortedSet.of(resource))
              .build();
      assertThat(cxxTestWithResources.getInputs(), hasItem(resource));
    }
  }

  private RuleKey getRuleKey(BuildRuleResolver resolver, BuildRule rule) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FileHashCache fileHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    rule.getProjectFilesystem(), FileHashCacheMode.DEFAULT)));
    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(fileHashCache, pathResolver, ruleFinder);
    return factory.build(rule);
  }
}
