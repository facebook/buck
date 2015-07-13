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
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxPythonExtensionDescriptionTest {

  private static final BuildTarget PYTHON_DEP_TARGET =
      BuildTargetFactory.newInstance("//:python_dep");

  private static FakeBuildRule createFakeBuildRule(
      String target,
      SourcePathResolver resolver,
      BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build(), resolver);
  }

  public CxxPythonExtensionBuilder getBuilder(BuildTarget target) {
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.of(
            "cxx", ImmutableMap.of(
                "python_dep", PYTHON_DEP_TARGET.toString())));
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
    FlavorDomain<CxxPlatform> cxxPlatforms = CxxPythonExtensionBuilder.createDefaultPlatforms();
    return new CxxPythonExtensionBuilder(target, cxxBuckConfig, cxxPlatforms);
  }

  @Test
  public void createBuildRuleBaseModule() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxPythonExtensionBuilder.createDefaultPlatform();

    // Create the python library dep.
    GenruleBuilder pyDepBuilder =
        GenruleBuilder.newGenruleBuilder(PYTHON_DEP_TARGET)
            .setOut("out");
    pyDepBuilder.build(resolver);

    // Verify we use the default base module when none is set.
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionBuilder normalBuilder = getBuilder(target);
    CxxPythonExtensionDescription desc =
        (CxxPythonExtensionDescription) normalBuilder.build().getDescription();
    CxxPythonExtension normal = (CxxPythonExtension) normalBuilder
        .build(
            resolver,
            filesystem,
            TargetGraphFactory.newInstance(
                normalBuilder.build(),
                pyDepBuilder.build()));
    PythonPackageComponents normalComps = normal.getPythonPackageComponents(cxxPlatform);
    assertEquals(
        ImmutableSet.of(
            target.getBasePath().resolve(desc.getExtensionName(target))),
        normalComps.getModules().keySet());

    // Verify that explicitly setting works.
    BuildTarget target2 = BuildTargetFactory.newInstance("//:target2");
    String name = "blah";
    CxxPythonExtensionBuilder baseModuleBuilder = getBuilder(target2)
        .setBaseModule(name);
    desc = (CxxPythonExtensionDescription) baseModuleBuilder.build().getDescription();
    CxxPythonExtension baseModule = (CxxPythonExtension) baseModuleBuilder
        .build(
            resolver,
            filesystem,
            TargetGraphFactory.newInstance(
                baseModuleBuilder.build(),
                GenruleBuilder.newGenruleBuilder(PYTHON_DEP_TARGET).build()));
    PythonPackageComponents baseModuleComps = baseModule.getPythonPackageComponents(cxxPlatform);
    assertEquals(
        ImmutableSet.of(
            Paths.get(name).resolve(desc.getExtensionName(target2))),
        baseModuleComps.getModules().keySet());
  }

  @Test
  public void createBuildRuleNativeLinkableDep() {
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    CxxPlatform cxxPlatform = CxxPythonExtensionBuilder.createDefaultPlatform();

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    final BuildRule sharedLibraryDep = createFakeBuildRule("//:shared", pathResolver);
    final Path sharedLibraryOutput = Paths.get("output/path/lib.so");
    final String sharedLibrarySoname = "soname";
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRuleParams depParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(depTarget);
    AbstractCxxLibrary dep = new AbstractCxxLibrary(depParams, pathResolver) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput(
          CxxPlatform cxxPlatform,
          HeaderVisibility headerVisibility) {
        return CxxPreprocessorInput.EMPTY;
      }

      @Override
      public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
          CxxPlatform cxxPlatform,
          HeaderVisibility headerVisibility) {
        return ImmutableMap.of(
            getBuildTarget(),
            getCxxPreprocessorInput(cxxPlatform, headerVisibility));
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType type) {
        return type == Linker.LinkableDepType.STATIC ?
            NativeLinkableInput.of(
                ImmutableList.<SourcePath>of(),
                ImmutableList.<String>of(),
                ImmutableSet.<Path>of()) :
            NativeLinkableInput.of(
                ImmutableList.<SourcePath>of(
                    new BuildTargetSourcePath(
                        sharedLibraryDep.getBuildTarget(),
                        sharedLibraryOutput)),
                ImmutableList.of(sharedLibraryOutput.toString()),
                ImmutableSet.<Path>of());
      }

      @Override
      public Optional<Linker.LinkableDepType> getPreferredLinkage(CxxPlatform cxxPlatform) {
        return Optional.absent();
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents(CxxPlatform cxxPlatform) {
        return PythonPackageComponents.of(
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(sharedLibrarySoname),
                new PathSourcePath(getProjectFilesystem(), sharedLibraryOutput)),
            ImmutableSet.<SourcePath>of(),
            Optional.<Boolean>absent());
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

      @Override
      public boolean isTestedBy(BuildTarget buildTarget) {
        return false;
      }
    };
    resolver.addAllToIndex(ImmutableList.of(sharedLibraryDep, dep));

    // Create the python library dep.
    GenruleBuilder pyDepBuilder =
        GenruleBuilder.newGenruleBuilder(PYTHON_DEP_TARGET)
            .setOut("out");
    pyDepBuilder.build(resolver);

    // Create args with the above dep set and create the python extension.
    CxxPythonExtensionBuilder extensionBuilder = (CxxPythonExtensionBuilder) getBuilder(target)
        .setDeps(ImmutableSortedSet.of(depTarget));
    CxxPythonExtensionDescription desc =
        (CxxPythonExtensionDescription) extensionBuilder.build().getDescription();
    CxxPythonExtension extension = (CxxPythonExtension) extensionBuilder.build(
        resolver,
        new FakeProjectFilesystem(),
        TargetGraphFactory.newInstance(
            extensionBuilder.build(),
            pyDepBuilder.build(),
            GenruleBuilder.newGenruleBuilder(depTarget).build()));

    // Verify that the shared library dep propagated to the link rule.
    extension.getPythonPackageComponents(cxxPlatform);
    BuildRule rule = resolver.getRule(desc.getExtensionTarget(target, cxxPlatform.getFlavor()));
    assertEquals(
        ImmutableSortedSet.of(sharedLibraryDep),
        rule.getDeps());
  }

  @Test
  public void createBuildRulePythonPackageable() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleResolver resolver = new BuildRuleResolver();

    // Create the python library dep.
    GenruleBuilder pyDepBuilder =
        GenruleBuilder.newGenruleBuilder(PYTHON_DEP_TARGET)
            .setOut("out");
    pyDepBuilder.build(resolver);

    CxxPlatform cxxPlatform = CxxPythonExtensionBuilder.createDefaultPlatform();
    CxxPythonExtensionBuilder extensionBuilder = getBuilder(target);
    CxxPythonExtensionDescription desc =
        (CxxPythonExtensionDescription) extensionBuilder.build().getDescription();
    CxxPythonExtension extension = (CxxPythonExtension) extensionBuilder.build(
        resolver,
        projectFilesystem,
        TargetGraphFactory.newInstance(
            extensionBuilder.build(),
            pyDepBuilder.build()));

    // Verify that we get the expected view from the python packageable interface.
    PythonPackageComponents actualComponent = extension.getPythonPackageComponents(cxxPlatform);
    BuildRule rule = resolver.getRule(desc.getExtensionTarget(target, cxxPlatform.getFlavor()));
    PythonPackageComponents expectedComponents = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(
            target.getBasePath().resolve(desc.getExtensionName(target)),
            new BuildTargetSourcePath(rule.getBuildTarget())),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableSet.<SourcePath>of(),
        Optional.of(false));
    assertEquals(
        expectedComponents,
        actualComponent);
  }

  @Test
  public void findDepsFromParamsAddsPythonDep() {
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionDescription desc =
        (CxxPythonExtensionDescription) getBuilder(target).build().getDescription();
    CxxPythonExtensionDescription.Arg constructorArg = desc.createUnpopulatedConstructorArg();
    constructorArg.lexSrcs = Optional.of(ImmutableList.<SourcePath>of());
    Iterable<BuildTarget> res = desc.findDepsForTargetFromConstructorArgs(
        BuildTargetFactory.newInstance("//foo:bar"),
        constructorArg);
    assertTrue(Iterables.contains(res, PYTHON_DEP_TARGET));
  }

}
