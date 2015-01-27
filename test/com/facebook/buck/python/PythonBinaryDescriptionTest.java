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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.DefaultCxxPlatforms;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PythonBinaryDescriptionTest {

  private static final Path PEX_PATH = Paths.get("pex");
  private static final CxxPlatform CXX_PLATFORM = DefaultCxxPlatforms.build(new FakeBuckConfig());
  private static final FlavorDomain<CxxPlatform> CXX_PLATFORMS =
      new FlavorDomain<>("platform", ImmutableMap.<Flavor, CxxPlatform>of());

  @Test
  public void thatComponentSourcePathDepsPropagateProperly() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    Genrule genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//:gen"))
        .setOut("blah.py")
        .build(resolver);
    BuildRuleParams libParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(
        BuildTargetFactory.newInstance("//:lib"));
    PythonLibrary lib = new PythonLibrary(
        libParams,
        new SourcePathResolver(resolver),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get("hello"), new BuildTargetSourcePath(genrule.getBuildTarget())),
        ImmutableMap.<Path, SourcePath>of());

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:bin"))
            .setDeps(ImmutableSortedSet.<BuildRule>of(lib))
            .build();
    PythonBinaryDescription desc = new PythonBinaryDescription(
        PEX_PATH,
        new PythonEnvironment(Paths.get("fake_python"), ImmutablePythonVersion.of("Python 2.7")),
        CXX_PLATFORM,
        CXX_PLATFORMS);
    PythonBinaryDescription.Arg arg = desc.createUnpopulatedConstructorArg();
    arg.deps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.main = new TestSourcePath("blah.py");
    arg.baseModule = Optional.absent();
    BuildRule rule = desc.createBuildRule(params, resolver, arg);

    assertEquals(
        ImmutableSortedSet.<BuildRule>of(genrule),
        rule.getDeps());
  }

  @Test
  public void thatMainSourcePathPropagatesToDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    Genrule genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//:gen"))
        .setOut("blah.py")
        .build(resolver);
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(
        BuildTargetFactory.newInstance("//:bin"));
    PythonBinaryDescription desc = new PythonBinaryDescription(
        PEX_PATH,
        new PythonEnvironment(Paths.get("fake_python"), ImmutablePythonVersion.of("Python 2.7")),
        CXX_PLATFORM,
        CXX_PLATFORMS);
    PythonBinaryDescription.Arg arg = desc.createUnpopulatedConstructorArg();
    arg.deps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.main = new BuildTargetSourcePath(genrule.getBuildTarget());
    arg.baseModule = Optional.absent();
    BuildRule rule = desc.createBuildRule(params, resolver, arg);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(genrule),
        rule.getDeps());
  }

  @Test
  public void baseModule() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    String mainName = "main.py";
    PythonBinaryDescription desc = new PythonBinaryDescription(
        PEX_PATH,
        new PythonEnvironment(Paths.get("python"), ImmutablePythonVersion.of("2.5")),
        CXX_PLATFORM,
        CXX_PLATFORMS);
    PythonBinaryDescription.Arg arg = desc.createUnpopulatedConstructorArg();
    arg.deps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.main = new TestSourcePath("foo/" + mainName);

    // Run without a base module set and verify it defaults to using the build target
    // base name.
    arg.baseModule = Optional.absent();
    PythonBinary normalRule = desc.createBuildRule(params, resolver, arg);
    assertEquals(
        target.getBasePath().resolve(mainName),
        normalRule.getMain());

    // Run *with* a base module set and verify it gets used to build the main module path.
    arg.baseModule = Optional.of("blah");
    PythonBinary baseModuleRule = desc.createBuildRule(params, resolver, arg);
    assertEquals(
        Paths.get(arg.baseModule.get()).resolve(mainName),
        baseModuleRule.getMain());
  }

}
