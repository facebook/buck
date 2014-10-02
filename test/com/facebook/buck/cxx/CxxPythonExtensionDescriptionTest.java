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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class CxxPythonExtensionDescriptionTest {

  private BuildTarget target;
  private BuildRuleParams params;
  private CxxPythonExtensionDescription desc;
  private BuildRule pythonDep;

  private static FakeBuildRule createFakeBuildRule(
      String target,
      BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build());
  }

  private static BuildRule createFakeCxxLibrary(
      String target,
      BuildRule... deps) {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build();
    return new CxxLibrary(params) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput() {
        return null;
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(Type type) {
        return null;
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents() {
        return null;
      }

    };
  }

  @Before
  public void setUp() {
    target = BuildTargetFactory.newInstance("//:target");
    params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    pythonDep = createFakeCxxLibrary("//:python_dep");

    // Setup a buck config with the python_dep as an entry.
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "cxx", ImmutableMap.of(
                "python_dep", pythonDep.toString())));
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
    desc = new CxxPythonExtensionDescription(cxxBuckConfig);
  }

  private CxxPythonExtensionDescription.Arg getDefaultArg() {
    CxxPythonExtensionDescription.Arg arg = desc.createUnpopulatedConstructorArg();
    arg.srcs = Optional.absent();
    arg.deps = Optional.absent();
    arg.headers = Optional.absent();
    arg.deps = Optional.absent();
    arg.compilerFlags = Optional.absent();
    arg.preprocessorFlags = Optional.absent();
    arg.lexSrcs = Optional.absent();
    arg.yaccSrcs = Optional.absent();
    arg.baseModule = Optional.absent();
    return arg;
  }

  @Test
  public void createBuildRuleBaseModule() {
    CxxPythonExtensionDescription.Arg arg = getDefaultArg();

    // Verify we use the default base module when none is set.
    arg.baseModule = Optional.absent();
    CxxPythonExtension normal = desc.createBuildRule(params, new BuildRuleResolver(), arg);
    assertEquals(
        target.getBasePath().resolve(desc.getExtensionName(target)),
        normal.getModule());

    // Verify that explicitly setting works.
    arg.baseModule = Optional.of("blah");
    CxxPythonExtension baseModule = desc.createBuildRule(params, new BuildRuleResolver(), arg);
    assertEquals(
        Paths.get(arg.baseModule.get()).resolve(desc.getExtensionName(target)),
        baseModule.getModule());
  }

  @Test
  public void createBuildRuleNativeLinkableDep() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    final BuildRule sharedLibraryDep = createFakeBuildRule("//:shared");
    final Path sharedLibraryOutput = Paths.get("output/path/lib.so");
    final String sharedLibrarySoname = "soname";
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRuleParams depParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(depTarget);
    CxxLibrary dep = new CxxLibrary(depParams) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput() {
        return new CxxPreprocessorInput(
            ImmutableSet.<BuildTarget>of(),
            ImmutableList.<String>of(),
            ImmutableList.<String>of(),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableList.<Path>of(),
            ImmutableList.<Path>of());
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(Type type) {
        return type == Type.STATIC ?
            new NativeLinkableInput(
                ImmutableList.<SourcePath>of(),
                ImmutableList.<String>of()) :
            new NativeLinkableInput(
                ImmutableList.<SourcePath>of(
                    new BuildRuleSourcePath(sharedLibraryDep, sharedLibraryOutput)),
                ImmutableList.of(sharedLibraryOutput.toString()));
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents() {
        return new PythonPackageComponents(
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(sharedLibrarySoname),
                new PathSourcePath(sharedLibraryOutput)));
      }

    };
    resolver.addToIndex(sharedLibraryDep);

    // Create args with the above dep set and create the python extension.
    CxxPythonExtensionDescription.Arg arg = getDefaultArg();
    arg.deps = Optional.of(ImmutableSortedSet.of(dep.getBuildTarget()));
    BuildRuleParams newParams = params.copyWithDeps(
        ImmutableSortedSet.<BuildRule>of(dep),
        ImmutableSortedSet.<BuildRule>of());
    CxxPythonExtension extension = desc.createBuildRule(newParams, resolver, arg);

    // Verify that the shared library dep propagated to the link rule.
    CxxLink cxxLink = extension.getRule();
    assertEquals(
        ImmutableSortedSet.of(sharedLibraryDep),
        cxxLink.getDeps());
  }

  @Test
  public void createBuildRulePythonPackageable() {
    CxxPythonExtensionDescription.Arg arg = getDefaultArg();
    CxxPythonExtension extension = desc.createBuildRule(params, new BuildRuleResolver(), arg);

    // Verify that we get the expected view from the python packageable interface.
    PythonPackageComponents expectedComponents = new PythonPackageComponents(
        ImmutableMap.<Path, SourcePath>of(
            target.getBasePath().resolve(desc.getExtensionName(target)),
            new BuildRuleSourcePath(extension.getRule())),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of());
    assertEquals(
        expectedComponents,
        extension.getPythonPackageComponents());
  }

  @Test
  public void findDepsFromParamsAddsPythonDep() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTargetParser parser = new BuildTargetParser();
    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        Maps.<String, Object>newHashMap(),
        filesystem,
        parser,
        target,
        new FakeRuleKeyBuilderFactory());
    Iterable<String> res = desc.findDepsFromParams(params);
    assertTrue(Iterables.contains(res, pythonDep.getBuildTarget().toString()));
  }

}
