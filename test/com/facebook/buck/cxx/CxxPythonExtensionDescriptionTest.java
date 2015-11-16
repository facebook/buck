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

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.python.PythonEnvironment;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.python.PythonPlatform;
import com.facebook.buck.python.PythonVersion;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Set;

public class CxxPythonExtensionDescriptionTest {

  private static final BuildTarget PYTHON2_DEP_TARGET =
      BuildTargetFactory.newInstance("//:python2_dep");
  private static final PythonPlatform PY2 =
      PythonPlatform.of(
          ImmutableFlavor.of("py2"),
          new PythonEnvironment(Paths.get("python2"), PythonVersion.of("2.6")),
          Optional.of(PYTHON2_DEP_TARGET));

  private static final BuildTarget PYTHON3_DEP_TARGET =
      BuildTargetFactory.newInstance("//:python3_dep");
  private static final PythonPlatform PY3 =
      PythonPlatform.of(
          ImmutableFlavor.of("py3"),
          new PythonEnvironment(Paths.get("python3"), PythonVersion.of("3.5")),
          Optional.of(PYTHON3_DEP_TARGET));

  public CxxPythonExtensionBuilder createBuilder(
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      Collection<TargetNode<?>> targetNodes,
      BuildTarget target) {
    new PrebuiltCxxLibraryBuilder(PYTHON2_DEP_TARGET)
        .setHeaderOnly(true)
        .setExportedLinkerFlags(ImmutableList.of("-lpython2"))
        .build(resolver, filesystem, targetNodes);
    new PrebuiltCxxLibraryBuilder(PYTHON3_DEP_TARGET)
        .setHeaderOnly(true)
        .setExportedLinkerFlags(ImmutableList.of("-lpython3"))
        .build(resolver, filesystem, targetNodes);
    return new CxxPythonExtensionBuilder(
        target,
        new FlavorDomain<>(
            "Python Platform",
            ImmutableMap.of(
                PY2.getFlavor(), PY2,
                PY3.getFlavor(), PY3)),
        new CxxBuckConfig(FakeBuckConfig.builder().build()),
        CxxTestBuilder.createDefaultPlatforms());
  }

  @Test
  public void createBuildRuleBaseModule() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Set<TargetNode<?>> targetNodes = Sets.newHashSet();

    // Verify we use the default base module when none is set.
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtension normal =
        (CxxPythonExtension) createBuilder(resolver, filesystem, targetNodes, target)
            .build(resolver, filesystem, targetNodes);
    PythonPackageComponents normalComps =
        normal.getPythonPackageComponents(
            TargetGraphFactory.newInstance(ImmutableSet.copyOf(targetNodes)),
            PY2,
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertEquals(
        ImmutableSet.of(
            target.getBasePath().resolve(CxxPythonExtensionDescription.getExtensionName(target))),
        normalComps.getModules().keySet());

    // Verify that explicitly setting works.
    resolver = new BuildRuleResolver();
    BuildTarget target2 = BuildTargetFactory.newInstance("//:target2#py2");
    String name = "blah";
    CxxPythonExtension baseModule =
        (CxxPythonExtension) createBuilder(resolver, filesystem, targetNodes, target2)
            .setBaseModule(name)
            .build(resolver, filesystem, targetNodes);
    PythonPackageComponents baseModuleComps =
        baseModule.getPythonPackageComponents(
            TargetGraphFactory.newInstance(ImmutableSet.copyOf(targetNodes)),
            PY2,
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertEquals(
        ImmutableSet.of(
            Paths.get(name).resolve(CxxPythonExtensionDescription.getExtensionName(target2))),
        baseModuleComps.getModules().keySet());
  }

  @Test
  public void createBuildRuleNativeLinkableDep() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Set<TargetNode<?>> targetNodes = Sets.newHashSet();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    CxxLibrary dep =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(
                        new FakeSourcePath("something.cpp"),
                        ImmutableList.<String>of())))
            .build(resolver, filesystem, targetNodes);
    NativeLinkableInput depInput =
        dep.getNativeLinkableInput(
            TargetGraphFactory.newInstance(ImmutableSet.copyOf(targetNodes)),
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED);

    // Create args with the above dep set and create the python extension.
    CxxPythonExtension extension =
        (CxxPythonExtension) createBuilder(resolver, filesystem, targetNodes, target)
            .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .build(resolver, filesystem, targetNodes);

    // Verify that the shared library dep propagated to the link rule.
    extension.getPythonPackageComponents(
        TargetGraphFactory.newInstance(ImmutableSet.copyOf(targetNodes)),
        PY2,
        CxxPlatformUtils.DEFAULT_PLATFORM);
    BuildRule rule = resolver.getRule(
        CxxPythonExtensionDescription.getExtensionTarget(
            target,
            PY2.getFlavor(),
            CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor()));
    assertThat(
        rule.getDeps(),
        Matchers.hasItems(
            FluentIterable.from(depInput.getArgs())
                .transformAndConcat(Arg.getDepsFunction(pathResolver))
                .toArray(BuildRule.class)));
  }

  @Test
  public void createBuildRulePythonPackageable() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Set<TargetNode<?>> targetNodes = Sets.newHashSet();

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtension extension =
        (CxxPythonExtension) createBuilder(resolver, filesystem, targetNodes, target)
            .build(resolver, filesystem, targetNodes);

    // Verify that we get the expected view from the python packageable interface.
    PythonPackageComponents actualComponent =
        extension.getPythonPackageComponents(
            TargetGraphFactory.newInstance(ImmutableSet.copyOf(targetNodes)),
            PY2,
            CxxPlatformUtils.DEFAULT_PLATFORM);
    BuildRule rule = resolver.getRule(
        CxxPythonExtensionDescription.getExtensionTarget(
            target,
            PY2.getFlavor(),
            CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor()));
    PythonPackageComponents expectedComponents = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(
            target.getBasePath().resolve(CxxPythonExtensionDescription.getExtensionName(target)),
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
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Set<TargetNode<?>> targetNodes = Sets.newHashSet();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionDescription desc =
        (CxxPythonExtensionDescription) createBuilder(resolver, filesystem, targetNodes, target)
            .build()
            .getDescription();
    CxxPythonExtensionDescription.Arg constructorArg = desc.createUnpopulatedConstructorArg();
    Iterable<BuildTarget> res = desc.findDepsForTargetFromConstructorArgs(
        BuildTargetFactory.newInstance("//foo:bar"),
        createCellRoots(filesystem),
        constructorArg);
    assertThat(res, Matchers.contains(PYTHON2_DEP_TARGET, PYTHON3_DEP_TARGET));
  }

  @Test
  public void py2AndPy3PropagateToLinkRules() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Set<TargetNode<?>> targetNodes = Sets.newHashSet();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtension extension =
        (CxxPythonExtension) createBuilder(resolver, filesystem, targetNodes, target)
            .build(resolver, filesystem, targetNodes);

    // Get the py2 extension, and verify it pulled in the py2 lib but not the py3 lib.
    CxxLink py2Ext =
        (CxxLink) extension.getExtension(
            TargetGraphFactory.newInstance(ImmutableSet.copyOf(targetNodes)),
            PY2,
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(
        py2Ext.getArgs(),
        Matchers.allOf(Matchers.hasItem("-lpython2"), Matchers.not(Matchers.hasItem("-lpython3"))));

    // Get the py3 extension, and verify it pulled in the py3 lib but not the py2 lib.
    CxxLink py3Ext =
        (CxxLink) extension.getExtension(
            TargetGraphFactory.newInstance(ImmutableSet.copyOf(targetNodes)),
            PY3,
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(
        py3Ext.getArgs(),
        Matchers.allOf(Matchers.hasItem("-lpython3"), Matchers.not(Matchers.hasItem("-lpython2"))));
  }

}
