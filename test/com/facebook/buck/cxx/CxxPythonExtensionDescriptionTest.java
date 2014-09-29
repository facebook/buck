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

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.EmptyDescription;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

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
  private CxxPlatform cxxPlatform;
  private FlavorDomain<CxxPlatform> cxxPlatforms;

  private <T> TargetNode<?> createTargetNode(
      BuildTarget target,
      Description<T> description,
      T arg) {
    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        new FakeProjectFilesystem(),
        new BuildTargetParser(),
        target,
        new FakeRuleKeyBuilderFactory());
    try {
      return new TargetNode<>(
          description,
          arg,
          params,
          ImmutableSet.<BuildTarget>of(),
          ImmutableSet.<BuildTargetPattern>of());
    } catch (NoSuchBuildTargetException e) {
      throw Throwables.propagate(e);
    }
  }

  private BuildRuleParams paramsForArg(CxxPythonExtensionDescription.Arg arg, BuildTarget... deps) {
    ImmutableSet.Builder<TargetNode<?>> nodesBuilder = ImmutableSet.builder();
    nodesBuilder.add(createTargetNode(target, desc, arg));
    for (BuildTarget dep : deps) {
      nodesBuilder.add(createTargetNode(dep, new EmptyDescription(), new EmptyDescription.Arg()));
    }
    ImmutableSet<TargetNode<?>> nodes = nodesBuilder.build();
    return new FakeBuildRuleParamsBuilder(target)
        .setTargetGraph(TargetGraphFactory.newInstance(nodes))
        .build();
  }

  private static FakeBuildRule createFakeBuildRule(
      String target,
      SourcePathResolver resolver,
      BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build(), resolver);
  }

  private static BuildRule createFakeCxxLibrary(
      String target,
      SourcePathResolver resolver,
      BuildRule... deps) {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build();
    return new AbstractCxxLibrary(params, resolver) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput(CxxPlatform cxxPlatform) {
        return null;
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(CxxPlatform cxxPlatform, Type type) {
        return null;
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents(CxxPlatform cxxPlatform) {
        return null;
      }

      @Override
      public Iterable<AndroidPackageable> getRequiredPackageables() {
        return ImmutableList.of();
      }

      @Override
      public void addToCollector(AndroidPackageableCollector collector) {}

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
        return ImmutableMap.of();
      }

    };
  }

  @Before
  public void setUp() {
    target = BuildTargetFactory.newInstance("//:target");
    params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    pythonDep = createFakeCxxLibrary(
        "//:python_dep",
        new SourcePathResolver(new BuildRuleResolver()));

    // Setup a buck config with the python_dep as an entry.
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "cxx", ImmutableMap.of(
                "python_dep", pythonDep.toString())));
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
    cxxPlatform = new DefaultCxxPlatform(buckConfig);
    cxxPlatforms = new FlavorDomain<>(
        "C/C++ Platform",
        ImmutableMap.of(cxxPlatform.asFlavor(), cxxPlatform));
    desc = new CxxPythonExtensionDescription(cxxBuckConfig, cxxPlatforms);
  }

  private CxxPythonExtensionDescription.Arg getDefaultArg() {
    CxxPythonExtensionDescription.Arg arg = desc.createUnpopulatedConstructorArg();
    arg.srcs = Optional.absent();
    arg.deps = Optional.absent();
    arg.headers = Optional.absent();
    arg.deps = Optional.absent();
    arg.compilerFlags = Optional.absent();
    arg.preprocessorFlags = Optional.absent();
    arg.langPreprocessorFlags = Optional.absent();
    arg.lexSrcs = Optional.absent();
    arg.yaccSrcs = Optional.absent();
    arg.baseModule = Optional.absent();
    arg.headerNamespace = Optional.absent();
    return arg;
  }

  @Test
  public void createBuildRuleBaseModule() {
    CxxPythonExtensionDescription.Arg arg = getDefaultArg();

    // Verify we use the default base module when none is set.
    arg.baseModule = Optional.absent();
    params = paramsForArg(arg, pythonDep.getBuildTarget());
    CxxPythonExtension normal =
        (CxxPythonExtension) desc.createBuildRule(params, new BuildRuleResolver(), arg);
    PythonPackageComponents normalComps = normal.getPythonPackageComponents(cxxPlatform);
    assertEquals(
        ImmutableSet.of(
            target.getBasePath().resolve(desc.getExtensionName(target))),
        normalComps.getModules().keySet());

    // Verify that explicitly setting works.
    arg.baseModule = Optional.of("blah");
    params = paramsForArg(arg, pythonDep.getBuildTarget());
    CxxPythonExtension baseModule =
        (CxxPythonExtension) desc.createBuildRule(params, new BuildRuleResolver(), arg);
    PythonPackageComponents baseModuleComps = baseModule.getPythonPackageComponents(cxxPlatform);
    assertEquals(
        ImmutableSet.of(
            Paths.get(arg.baseModule.get()).resolve(desc.getExtensionName(target))),
        baseModuleComps.getModules().keySet());
  }

  @Test
  public void createBuildRuleNativeLinkableDep() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    final BuildRule sharedLibraryDep = createFakeBuildRule("//:shared", pathResolver);
    final Path sharedLibraryOutput = Paths.get("output/path/lib.so");
    final String sharedLibrarySoname = "soname";
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRuleParams depParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(depTarget);
    AbstractCxxLibrary dep = new AbstractCxxLibrary(depParams, pathResolver) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput(CxxPlatform cxxPlatform) {
        return CxxPreprocessorInput.EMPTY;
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(CxxPlatform cxxPlatform, Type type) {
        return type == Type.STATIC ?
            new NativeLinkableInput(
                ImmutableList.<SourcePath>of(),
                ImmutableList.<String>of()) :
            new NativeLinkableInput(
                ImmutableList.<SourcePath>of(
                    new BuildTargetSourcePath(
                        sharedLibraryDep.getBuildTarget(),
                        sharedLibraryOutput)),
                ImmutableList.of(sharedLibraryOutput.toString()));
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents(CxxPlatform cxxPlatform) {
        return new PythonPackageComponents(
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(sharedLibrarySoname),
                new PathSourcePath(sharedLibraryOutput)));
      }

      @Override
      public Iterable<AndroidPackageable> getRequiredPackageables() {
        return ImmutableList.of();
      }

      @Override
      public void addToCollector(AndroidPackageableCollector collector) {}

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
        return ImmutableMap.of();
      }

    };
    resolver.addToIndex(sharedLibraryDep);

    // Create args with the above dep set and create the python extension.
    CxxPythonExtensionDescription.Arg arg = getDefaultArg();
    arg.deps = Optional.of(ImmutableSortedSet.of(dep.getBuildTarget()));
    params = paramsForArg(arg, pythonDep.getBuildTarget(), depTarget);
    BuildRuleParams newParams = params.copyWithDeps(
        ImmutableSortedSet.<BuildRule>of(dep),
        ImmutableSortedSet.<BuildRule>of());
    CxxPythonExtension extension =
        (CxxPythonExtension) desc.createBuildRule(newParams, resolver, arg);

    // Verify that the shared library dep propagated to the link rule.
    extension.getPythonPackageComponents(cxxPlatform);
    BuildRule rule = resolver.getRule(desc.getExtensionTarget(target, cxxPlatform.asFlavor()));
    assertEquals(
        ImmutableSortedSet.of(sharedLibraryDep),
        rule.getDeps());
  }

  @Test
  public void createBuildRulePythonPackageable() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    CxxPythonExtensionDescription.Arg arg = getDefaultArg();
    params = paramsForArg(arg, pythonDep.getBuildTarget());
    CxxPythonExtension extension = (CxxPythonExtension) desc.createBuildRule(params, resolver, arg);

    // Verify that we get the expected view from the python packageable interface.
    PythonPackageComponents actualComponent = extension.getPythonPackageComponents(cxxPlatform);
    BuildRule rule = resolver.getRule(desc.getExtensionTarget(target, cxxPlatform.asFlavor()));
    PythonPackageComponents expectedComponents = new PythonPackageComponents(
        ImmutableMap.<Path, SourcePath>of(
            target.getBasePath().resolve(desc.getExtensionName(target)),
            new BuildTargetSourcePath(rule.getBuildTarget())),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of());
    assertEquals(
        expectedComponents,
        actualComponent);
  }

  @Test
  public void findDepsFromParamsAddsPythonDep() {
    CxxPythonExtensionDescription.Arg constructorArg = desc.createUnpopulatedConstructorArg();
    constructorArg.lexSrcs = Optional.of(ImmutableList.<SourcePath>of());
    Iterable<String> res = desc.findDepsForTargetFromConstructorArgs(
        BuildTargetFactory.newInstance("//foo:bar"),
        constructorArg);
    assertTrue(Iterables.contains(res, pythonDep.getBuildTarget().toString()));
  }

}
