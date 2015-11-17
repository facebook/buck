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
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PythonBinaryDescriptionTest {

  @Test
  public void thatComponentSourcePathDepsPropagateProperly() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    Genrule genrule =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen"))
            .setOut("blah.py")
            .build(resolver);
    PythonLibrary lib =
        (PythonLibrary) new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSrcs(
                SourceList.ofUnnamedSources(
                    ImmutableSortedSet.<SourcePath>of(
                        new BuildTargetSourcePath(genrule.getBuildTarget()))))
            .build(resolver);
    PythonBinary binary =
        (PythonBinary) PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setMainModule("main")
            .setDeps(ImmutableSortedSet.of(lib.getBuildTarget()))
            .build(resolver);
    assertThat(binary.getDeps(), Matchers.hasItem(genrule));
  }

  @Test
  public void thatMainSourcePathPropagatesToDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    Genrule genrule =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen"))
            .setOut("blah.py")
            .build(resolver);
    PythonBinary binary =
        (PythonBinary) PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setMain(new BuildTargetSourcePath(genrule.getBuildTarget()))
            .build(resolver);
    assertThat(binary.getDeps(), Matchers.hasItem(genrule));
  }

  @Test
  public void baseModule() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    String sourceName = "main.py";
    SourcePath source = new FakeSourcePath("foo/" + sourceName);

    // Run without a base module set and verify it defaults to using the build target
    // base name.
    PythonBinary normal =
        (PythonBinary) PythonBinaryBuilder.create(target)
            .setMain(source)
            .build(new BuildRuleResolver());
    assertThat(
        normal.getComponents().getModules().keySet(),
        Matchers.hasItem(target.getBasePath().resolve(sourceName)));

    // Run *with* a base module set and verify it gets used to build the main module path.
    String baseModule = "blah";
    PythonBinary withBaseModule =
        (PythonBinary) PythonBinaryBuilder.create(target)
            .setMain(source)
            .setBaseModule(baseModule)
            .build(new BuildRuleResolver());
    assertThat(
        withBaseModule.getComponents().getModules().keySet(),
        Matchers.hasItem(Paths.get(baseModule).resolve(sourceName)));
  }

  @Test
  public void mainModule() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    BuildRuleResolver resolver = new BuildRuleResolver();
    String mainModule = "foo.main";
    PythonBinary binary =
        (PythonBinary) PythonBinaryBuilder.create(target)
            .setMainModule(mainModule)
            .build(resolver);
    assertThat(mainModule, Matchers.equalTo(binary.getMainModule()));
  }

  @Test
  public void pexExtension() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    BuildRuleResolver resolver = new BuildRuleResolver();
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of(
                    "python",
                    ImmutableMap.of("pex_extension", ".different_extension"))).build(),
            new AlwaysFoundExecutableFinder());
    PythonBinaryBuilder builder =
        new PythonBinaryBuilder(
            target,
            config,
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    PythonBinary binary =
        (PythonBinary) builder
            .setMainModule("main")
            .build(resolver);
    assertThat(
        Preconditions.checkNotNull(binary.getPathToOutput()).toString(),
        Matchers.endsWith(".different_extension"));
  }

  @Test
  public void buildArgs() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    BuildRuleResolver resolver = new BuildRuleResolver();
    ImmutableList<String> buildArgs = ImmutableList.of("--some", "--args");
    PythonBinary binary =
        (PythonBinary) PythonBinaryBuilder.create(target)
            .setMainModule("main")
            .setBuildArgs(buildArgs)
            .build(resolver);
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
  public void explicitPythonHome() {
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
    PythonBinaryBuilder builder =
        PythonBinaryBuilder.create(
            BuildTargetFactory.newInstance("//:bin"),
            new FlavorDomain<>(
                "Python Platform",
                ImmutableMap.of(
                    platform1.getFlavor(), platform1,
                    platform2.getFlavor(), platform2)));
    builder.setMainModule("main");
    PythonBinary binary1 =
        (PythonBinary) builder
            .setPlatform(platform1.getFlavor().toString())
            .build(new BuildRuleResolver());
    assertThat(binary1.getPythonPlatform(), Matchers.equalTo(platform1));
    PythonBinary binary2 =
        (PythonBinary) builder
            .setPlatform(platform2.getFlavor().toString())
            .build(new BuildRuleResolver());
    assertThat(binary2.getPythonPlatform(), Matchers.equalTo(platform2));
  }

  @Test
  public void runtimeDepOnDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRule cxxBinary =
        new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    BuildRule pythonLibrary =
        new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setDeps(ImmutableSortedSet.of(cxxBinary.getBuildTarget()))
            .build(resolver);
    PythonBinary pythonBinary =
        (PythonBinary) PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setMainModule("main")
            .setDeps(ImmutableSortedSet.of(pythonLibrary.getBuildTarget()))
            .build(resolver);
    assertThat(
        BuildRules.getTransitiveRuntimeDeps(pythonBinary),
        Matchers.hasItem(cxxBinary));
  }

  @Test
  public void executableCommandWithPathToPexExecutor() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final Path executor = Paths.get("executor");
    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.builder().build(), new AlwaysFoundExecutableFinder()) {
          @Override
          public Optional<Tool> getPathToPexExecuter(BuildRuleResolver resolver) {
            return Optional.<Tool>of(new HashedFileTool(executor));
          }
        };
    PythonBinaryBuilder builder =
        new PythonBinaryBuilder(
            target,
            config,
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    PythonPackagedBinary binary =
        (PythonPackagedBinary) builder
            .setMainModule("main")
            .build(resolver);
    assertThat(
        binary.getExecutableCommand().getCommandPrefix(pathResolver),
        Matchers.contains(
            executor.toString(),
            binary.getBinPath().toAbsolutePath().toString()));
  }

  @Test
  public void executableCommandWithNoPathToPexExecutor() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    PythonPackagedBinary binary =
        (PythonPackagedBinary) PythonBinaryBuilder.create(target)
            .setMainModule("main")
        .build(resolver);
    assertThat(
        binary.getExecutableCommand().getCommandPrefix(pathResolver),
        Matchers.contains(
            PythonTestUtils.PYTHON_PLATFORM.getEnvironment().getPythonPath().toString(),
            binary.getBinPath().toAbsolutePath().toString()));
  }

}
