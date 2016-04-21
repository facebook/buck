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

package com.facebook.buck.python;

import static org.junit.Assert.assertThat;

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBinaryBuilder;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.shell.ShBinary;
import com.facebook.buck.shell.ShBinaryBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.regex.Pattern;

public class PythonTestDescriptionTest {

  @Test
  public void thatTestModulesAreInComponents() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    PythonTest testRule =
        (PythonTest) PythonTestBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setSrcs(
                SourceList.ofUnnamedSources(
                    ImmutableSortedSet.<SourcePath>of(new FakeSourcePath("blah.py"))))
            .build(resolver);
    PythonBinary binRule = testRule.getBinary();
    PythonPackageComponents components = binRule.getComponents();
    assertThat(
        components.getModules().keySet(),
        Matchers.hasItem(PythonTestDescription.getTestModulesListName()));
    assertThat(
        components.getModules().keySet(),
        Matchers.hasItem(PythonTestDescription.getTestMainName()));
    assertThat(
        binRule.getMainModule(),
        Matchers.equalTo(
            PythonUtil.toModuleName(
                testRule.getBuildTarget(),
                PythonTestDescription.getTestMainName().toString())));
  }

  @Test
  public void baseModule() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:test");
    String sourceName = "main.py";
    SourcePath source = new FakeSourcePath("foo/" + sourceName);

    // Run without a base module set and verify it defaults to using the build target
    // base name.
    PythonTest normal =
        (PythonTest) PythonTestBuilder.create(target)
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source)))
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));
    assertThat(
        normal.getBinary().getComponents().getModules().keySet(),
        Matchers.hasItem(target.getBasePath().resolve(sourceName)));

    // Run *with* a base module set and verify it gets used to build the main module path.
    String baseModule = "blah";
    PythonTest withBaseModule =
        (PythonTest) PythonTestBuilder.create(target)
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source)))
            .setBaseModule(baseModule)
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));
    assertThat(
        withBaseModule.getBinary().getComponents().getModules().keySet(),
        Matchers.hasItem(Paths.get(baseModule).resolve(sourceName)));
  }

  @Test
  public void buildArgs() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:test");
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ImmutableList<String> buildArgs = ImmutableList.of("--some", "--args");
    PythonTest test =
        (PythonTest) PythonTestBuilder.create(target)
            .setBuildArgs(buildArgs)
            .build(resolver);
    PythonBinary binary = test.getBinary();
    ImmutableList<Step> buildSteps =
        binary.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, new FakeBuildableContext());
    PexStep pexStep = FluentIterable.from(buildSteps)
        .filter(PexStep.class)
        .get(0);
    assertThat(
        pexStep.getCommandPrefix(),
        Matchers.hasItems(buildArgs.toArray(new String[buildArgs.size()])));
  }

  @Test
  public void platformSrcs() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:test");
    SourcePath matchedSource = new FakeSourcePath("foo/a.py");
    SourcePath unmatchedSource = new FakeSourcePath("foo/b.py");
    PythonTest test =
        (PythonTest) PythonTestBuilder.create(target)
            .setPlatformSrcs(
                PatternMatchedCollection.<SourceList>builder()
                    .add(
                        Pattern.compile(PythonTestUtils.PYTHON_PLATFORM.getFlavor().toString()),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        Pattern.compile("won't match anything"),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build())
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));
    assertThat(
        test.getBinary().getComponents().getModules().values(),
        Matchers.allOf(
            Matchers.hasItem(matchedSource),
            Matchers.not(Matchers.hasItem(unmatchedSource))));
  }

  @Test
  public void platformResources() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:test");
    SourcePath matchedSource = new FakeSourcePath("foo/a.dat");
    SourcePath unmatchedSource = new FakeSourcePath("foo/b.dat");
    PythonTest test =
        (PythonTest) PythonTestBuilder.create(target)
            .setPlatformResources(
                PatternMatchedCollection.<SourceList>builder()
                    .add(
                        Pattern.compile(PythonTestUtils.PYTHON_PLATFORM.getFlavor().toString()),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        Pattern.compile("won't match anything"),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build())
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));
    assertThat(
        test.getBinary().getComponents().getResources().values(),
        Matchers.allOf(
            Matchers.hasItem(matchedSource),
            Matchers.not(Matchers.hasItem(unmatchedSource))));
  }

  @Test
  public void explicitPythonHome() throws Exception {
    PythonPlatform platform1 =
        PythonPlatform.of(
            ImmutableFlavor.of("pyPlat1"),
            new PythonEnvironment(Paths.get("python2.6"), PythonVersion.of("2.6")),
            Optional.<BuildTarget>absent());
    PythonPlatform platform2 =
        PythonPlatform.of(
            ImmutableFlavor.of("pyPlat2"),
            new PythonEnvironment(Paths.get("python2.7"), PythonVersion.of("2.7")),
            Optional.<BuildTarget>absent());
    PythonTestBuilder builder =
        PythonTestBuilder.create(
            BuildTargetFactory.newInstance("//:bin"),
            FlavorDomain.of("Python Platform", platform1, platform2));
    PythonTest test1 =
        (PythonTest) builder
            .setPlatform(platform1.getFlavor().toString())
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));
    assertThat(test1.getBinary().getPythonPlatform(), Matchers.equalTo(platform1));
    PythonTest test2 =
        (PythonTest) builder
            .setPlatform(platform2.getFlavor().toString())
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));
    assertThat(test2.getBinary().getPythonPlatform(), Matchers.equalTo(platform2));
  }

  @Test
  public void runtimeDepOnDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule cxxBinary =
        new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    BuildRule pythonLibrary =
        new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setDeps(ImmutableSortedSet.of(cxxBinary.getBuildTarget()))
            .build(resolver);
    PythonTest pythonTest =
        (PythonTest) PythonTestBuilder.create(BuildTargetFactory.newInstance("//:test"))
            .setDeps(ImmutableSortedSet.of(pythonLibrary.getBuildTarget()))
            .build(resolver);
    assertThat(
        BuildRules.getTransitiveRuntimeDeps(pythonTest),
        Matchers.hasItem(cxxBinary));
  }

  @Test
  public void packageStyleParam() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    PythonTest pythonTest =
        (PythonTest) PythonTestBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setPackageStyle(PythonBuckConfig.PackageStyle.INPLACE)
            .build(resolver);
    assertThat(
        pythonTest.getBinary(),
        Matchers.instanceOf(PythonInPlaceBinary.class));
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    pythonTest =
        (PythonTest) PythonTestBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE)
            .build(resolver);
    assertThat(
        pythonTest.getBinary(),
        Matchers.instanceOf(PythonPackagedBinary.class));
  }

  @Test
  public void pexExecutorIsAddedToTestRuntimeDeps() throws Exception {
    ShBinaryBuilder pexExecutorBuilder =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:pex_executor"))
            .setMain(new FakeSourcePath("run.sh"));
    PythonTestBuilder builder =
        new PythonTestBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new PythonBuckConfig(
                FakeBuckConfig.builder()
                    .setSections(
                        ImmutableMap.of(
                            "python",
                            ImmutableMap.of(
                                "path_to_pex_executer",
                                pexExecutorBuilder.getTarget().toString())))
                    .build(),
                new AlwaysFoundExecutableFinder()),
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    builder
        .setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE);
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                pexExecutorBuilder.build(),
                builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    ShBinary pexExecutor = (ShBinary) pexExecutorBuilder.build(resolver);
    PythonTest binary = (PythonTest) builder.build(resolver);
    assertThat(
        binary.getRuntimeDeps(),
        Matchers.hasItem(pexExecutor));
  }

  @Test
  public void pexExecutorRuleIsAddedToParseTimeDeps() throws Exception {
    ShBinaryBuilder pexExecutorBuilder =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:pex_executor"))
            .setMain(new FakeSourcePath("run.sh"));
    PythonTestBuilder builder =
        new PythonTestBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new PythonBuckConfig(
                FakeBuckConfig.builder()
                    .setSections(
                        ImmutableMap.of(
                            "python",
                            ImmutableMap.of(
                                "path_to_pex_executer",
                                pexExecutorBuilder.getTarget().toString())))
                    .build(),
                new AlwaysFoundExecutableFinder()),
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    builder
        .setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE);
    assertThat(
        builder.build().getExtraDeps(),
        Matchers.hasItem(pexExecutorBuilder.getTarget()));
  }

}
