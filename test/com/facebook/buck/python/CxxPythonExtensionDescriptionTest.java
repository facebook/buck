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

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBinaryBuilder;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.CxxTestBuilder;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.cxx.PrebuiltCxxLibraryBuilder;
import com.facebook.buck.cxx.SharedNativeLinkTarget;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

public class CxxPythonExtensionDescriptionTest {

  private static final BuildTarget PYTHON2_DEP_TARGET =
      BuildTargetFactory.newInstance("//:python2_dep");
  private static final PythonPlatform PY2 =
      PythonPlatform.of(
          ImmutableFlavor.of("py2"),
          new PythonEnvironment(Paths.get("python2"), PythonVersion.of("CPython", "2.6.9")),
          Optional.of(PYTHON2_DEP_TARGET));

  private static final BuildTarget PYTHON3_DEP_TARGET =
      BuildTargetFactory.newInstance("//:python3_dep");
  private static final PythonPlatform PY3 =
      PythonPlatform.of(
          ImmutableFlavor.of("py3"),
          new PythonEnvironment(Paths.get("python3"), PythonVersion.of("CPython", "3.5.0")),
          Optional.of(PYTHON3_DEP_TARGET));

  @Test
  public void createBuildRuleBaseModule() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder python2Builder = new PrebuiltCxxLibraryBuilder(
        PYTHON2_DEP_TARGET)
        .setHeaderOnly(true)
        .setExportedLinkerFlags(ImmutableList.of("-lpython2"));
    PrebuiltCxxLibraryBuilder python3Builder = new PrebuiltCxxLibraryBuilder(
        PYTHON3_DEP_TARGET)
        .setHeaderOnly(true)
        .setExportedLinkerFlags(ImmutableList.of("-lpython3"));

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionBuilder builder = new CxxPythonExtensionBuilder(
        target,
        FlavorDomain.of("Python Platform", PY2, PY3),
        new CxxBuckConfig(FakeBuckConfig.builder().build()),
        CxxTestBuilder.createDefaultPlatforms());

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                builder.build(),
                python2Builder.build(),
                python3Builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());

    python2Builder.build(resolver, filesystem);
    python3Builder.build(resolver, filesystem);

    CxxPythonExtension normal =
        (CxxPythonExtension) builder
            .build(resolver, filesystem);

    PythonPackageComponents normalComps =
        normal.getPythonPackageComponents(
            PY2,
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertEquals(
        ImmutableSet.of(
            target.getBasePath().resolve(CxxPythonExtensionDescription.getExtensionName(target))),
        normalComps.getModules().keySet());

    // Verify that explicitly setting works.
    BuildTarget target2 = BuildTargetFactory.newInstance("//:target2#py2");
    String name = "blah";
    CxxPythonExtensionBuilder baseModuleBuilder = new CxxPythonExtensionBuilder(
        target2,
        FlavorDomain.of("Python Platform", PY2, PY3),
        new CxxBuckConfig(FakeBuckConfig.builder().build()),
        CxxTestBuilder.createDefaultPlatforms())
        .setBaseModule(name);
    resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                baseModuleBuilder.build(),
                python2Builder.build(),
                python3Builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    python2Builder.build(resolver, filesystem);
    python3Builder.build(resolver, filesystem);
    CxxPythonExtension baseModule =
        (CxxPythonExtension) baseModuleBuilder.build(resolver, filesystem);
    PythonPackageComponents baseModuleComps =
        baseModule.getPythonPackageComponents(
            PY2,
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertEquals(
        ImmutableSet.of(
            Paths.get(name).resolve(CxxPythonExtensionDescription.getExtensionName(target2))),
        baseModuleComps.getModules().keySet());
  }

  @Test
  public void createBuildRuleNativeLinkableDep() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");

    PrebuiltCxxLibraryBuilder python2Builder = new PrebuiltCxxLibraryBuilder(
        PYTHON2_DEP_TARGET)
        .setHeaderOnly(true)
        .setExportedLinkerFlags(ImmutableList.of("-lpython2"));
    PrebuiltCxxLibraryBuilder python3Builder = new PrebuiltCxxLibraryBuilder(
        PYTHON3_DEP_TARGET)
        .setHeaderOnly(true)
        .setExportedLinkerFlags(ImmutableList.of("-lpython3"));
    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    BuildTarget cxxLibraryTarget = BuildTargetFactory.newInstance("//:dep");
    CxxLibraryBuilder cxxLibraryBuilder = new CxxLibraryBuilder(cxxLibraryTarget)
        .setSrcs(
            ImmutableSortedSet.of(
                SourceWithFlags.of(
                    new FakeSourcePath("something.cpp"),
                    ImmutableList.<String>of())));
    CxxPythonExtensionBuilder builder = new CxxPythonExtensionBuilder(
        target,
        FlavorDomain.of("Python Platform", PY2, PY3),
        new CxxBuckConfig(FakeBuckConfig.builder().build()),
        CxxTestBuilder.createDefaultPlatforms())
        .setDeps(ImmutableSortedSet.of(cxxLibraryTarget));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                cxxLibraryBuilder.build(),
                builder.build(),
                python2Builder.build(),
                python3Builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    python2Builder.build(resolver, filesystem);
    python3Builder.build(resolver, filesystem);
    CxxLibrary dep = (CxxLibrary) cxxLibraryBuilder.build(resolver, filesystem);
    CxxPythonExtension extension = (CxxPythonExtension) builder.build(resolver, filesystem);

    NativeLinkableInput depInput =
        dep.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED);


    // Verify that the shared library dep propagated to the link rule.
    extension.getPythonPackageComponents(
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
  public void createBuildRulePythonPackageable() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder python2Builder = new PrebuiltCxxLibraryBuilder(
        PYTHON2_DEP_TARGET)
        .setHeaderOnly(true)
        .setExportedLinkerFlags(ImmutableList.of("-lpython2"));
    PrebuiltCxxLibraryBuilder python3Builder = new PrebuiltCxxLibraryBuilder(
        PYTHON3_DEP_TARGET)
        .setHeaderOnly(true)
        .setExportedLinkerFlags(ImmutableList.of("-lpython3"));

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionBuilder builder = new CxxPythonExtensionBuilder(
        target,
        FlavorDomain.of("Python Platform", PY2, PY3),
        new CxxBuckConfig(FakeBuckConfig.builder().build()),
        CxxTestBuilder.createDefaultPlatforms());

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                builder.build(),
                python2Builder.build(),
                python3Builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());

    python2Builder.build(resolver, filesystem);
    python3Builder.build(resolver, filesystem);

    CxxPythonExtension extension =
        (CxxPythonExtension) builder
            .build(resolver, filesystem);

    // Verify that we get the expected view from the python packageable interface.
    PythonPackageComponents actualComponent =
        extension.getPythonPackageComponents(
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
  public void findDepsFromParamsAddsPythonDep() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    new PrebuiltCxxLibraryBuilder(PYTHON2_DEP_TARGET)
        .setHeaderOnly(true)
        .setExportedLinkerFlags(ImmutableList.of("-lpython2"))
        .build(resolver, filesystem);
    new PrebuiltCxxLibraryBuilder(PYTHON3_DEP_TARGET)
        .setHeaderOnly(true)
        .setExportedLinkerFlags(ImmutableList.of("-lpython3"))
        .build(resolver, filesystem);
    CxxPythonExtensionDescription desc =
        (CxxPythonExtensionDescription) new CxxPythonExtensionBuilder(
            target,
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestBuilder.createDefaultPlatforms())
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
  public void py2AndPy3PropagateToLinkRules() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder python2Builder = new PrebuiltCxxLibraryBuilder(
        PYTHON2_DEP_TARGET)
        .setHeaderOnly(true)
        .setExportedLinkerFlags(ImmutableList.of("-lpython2"));
    PrebuiltCxxLibraryBuilder python3Builder = new PrebuiltCxxLibraryBuilder(
        PYTHON3_DEP_TARGET)
        .setHeaderOnly(true)
        .setExportedLinkerFlags(ImmutableList.of("-lpython3"));

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionBuilder builder = new CxxPythonExtensionBuilder(
        target,
        FlavorDomain.of("Python Platform", PY2, PY3),
        new CxxBuckConfig(FakeBuckConfig.builder().build()),
        CxxTestBuilder.createDefaultPlatforms());

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                builder.build(),
                python2Builder.build(),
                python3Builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());

    python2Builder.build(resolver, filesystem);
    python3Builder.build(resolver, filesystem);

    CxxPythonExtension extension =
        (CxxPythonExtension) builder
            .build(resolver, filesystem);

    // Get the py2 extension, and verify it pulled in the py2 lib but not the py3 lib.
    CxxLink py2Ext =
        (CxxLink) extension.getExtension(
            PY2,
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(
        Arg.stringify(py2Ext.getArgs()),
        Matchers.allOf(Matchers.hasItem("-lpython2"), Matchers.not(Matchers.hasItem("-lpython3"))));

    // Get the py3 extension, and verify it pulled in the py3 lib but not the py2 lib.
    CxxLink py3Ext =
        (CxxLink) extension.getExtension(
            PY3,
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(
        Arg.stringify(py3Ext.getArgs()),
        Matchers.allOf(Matchers.hasItem("-lpython3"), Matchers.not(Matchers.hasItem("-lpython2"))));
  }

  @Test
  public void sharedNativeLinkTargetLibraryName() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    new CxxLibraryBuilder(PYTHON2_DEP_TARGET).build(resolver);
    new CxxLibraryBuilder(PYTHON3_DEP_TARGET).build(resolver);
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestBuilder.createDefaultPlatforms());
    CxxPythonExtension rule =
        (CxxPythonExtension) builder.build(resolver);
    SharedNativeLinkTarget sharedNativeLinkTarget = rule.getNativeLinkTarget(PY2);
    assertThat(
        sharedNativeLinkTarget.getSharedNativeLinkTargetLibraryName(
            CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.equalTo(Optional.<String>absent()));
  }

  @Test
  public void sharedNativeLinkTargetDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    new CxxLibraryBuilder(PYTHON2_DEP_TARGET).build(resolver);
    new CxxLibraryBuilder(PYTHON3_DEP_TARGET).build(resolver);
    CxxLibrary dep =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestBuilder.createDefaultPlatforms());
    CxxPythonExtension rule =
        (CxxPythonExtension) builder
            .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .build(resolver);
    SharedNativeLinkTarget sharedNativeLinkTarget = rule.getNativeLinkTarget(PY2);
    assertThat(
        ImmutableList.copyOf(
            sharedNativeLinkTarget.getSharedNativeLinkTargetDeps(
                CxxPlatformUtils.DEFAULT_PLATFORM)),
        Matchers.<NativeLinkable>hasItem(dep));
    assertThat(
        ImmutableList.copyOf(
            sharedNativeLinkTarget.getSharedNativeLinkTargetDeps(
                CxxPlatformUtils.DEFAULT_PLATFORM)),
        Matchers.hasItem((NativeLinkable) resolver.getRule(PY2.getCxxLibrary().get())));
  }

  @Test
  public void sharedNativeLinkTargetInput() throws Exception {
    CxxLibraryBuilder python2Builder = new CxxLibraryBuilder(PYTHON2_DEP_TARGET);
    CxxLibraryBuilder python3Builder = new CxxLibraryBuilder(PYTHON3_DEP_TARGET);
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestBuilder.createDefaultPlatforms());
    builder.setLinkerFlags(ImmutableList.of("--flag"));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                builder.build(),
                python2Builder.build(),
                python3Builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    python2Builder.build(resolver);
    python3Builder.build(resolver);
    CxxPythonExtension rule = (CxxPythonExtension) builder.build(resolver);
    SharedNativeLinkTarget sharedNativeLinkTarget = rule.getNativeLinkTarget(PY2);
    NativeLinkableInput input =
        sharedNativeLinkTarget.getSharedNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(
        Arg.stringify(input.getArgs()),
        Matchers.hasItems("--flag"));
  }

  @Test
  public void platformDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    new CxxLibraryBuilder(PYTHON2_DEP_TARGET).build(resolver);
    new CxxLibraryBuilder(PYTHON3_DEP_TARGET).build(resolver);
    CxxLibrary dep =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestBuilder.createDefaultPlatforms());
    CxxPythonExtension rule =
        (CxxPythonExtension) builder
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(PY2.getFlavor().toString()),
                        ImmutableSortedSet.of(dep.getBuildTarget()))
                    .build())
            .build(resolver);
    SharedNativeLinkTarget py2SharedNativeLinkTarget = rule.getNativeLinkTarget(PY2);
    assertThat(
        ImmutableList.copyOf(
            py2SharedNativeLinkTarget.getSharedNativeLinkTargetDeps(
                CxxPlatformUtils.DEFAULT_PLATFORM)),
        Matchers.<NativeLinkable>hasItem(dep));
    SharedNativeLinkTarget py3SharedNativeLinkTarget = rule.getNativeLinkTarget(PY3);
    assertThat(
        ImmutableList.copyOf(
            py3SharedNativeLinkTarget.getSharedNativeLinkTargetDeps(
                CxxPlatformUtils.DEFAULT_PLATFORM)),
        Matchers.not(Matchers.<NativeLinkable>hasItem(dep)));
  }

  @Test
  public void platformDepsSeparateLinkage() throws Exception {
    PythonBuckConfig pythonBuckConfig =
        new PythonBuckConfig(FakeBuckConfig.builder().build(), new ExecutableFinder());
    FlavorDomain<PythonPlatform> pythonPlatforms = FlavorDomain.of("Python Platform", PY2, PY3);

    CxxLibraryBuilder py2Builder = new CxxLibraryBuilder(PYTHON2_DEP_TARGET);
    CxxLibraryBuilder py3Builder = new CxxLibraryBuilder(PYTHON3_DEP_TARGET);

    CxxLibraryBuilder depBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test.c"))));
    CxxPythonExtensionBuilder extensionBuilder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            pythonPlatforms,
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestBuilder.createDefaultPlatforms())
        .setPlatformDeps(
            PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                .add(
                    Pattern.compile(PY2.getFlavor().toString()),
                    ImmutableSortedSet.of(depBuilder.getTarget()))
                .build());
    PythonBinaryBuilder binary2Builder =
        new PythonBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin2"),
            pythonBuckConfig,
            pythonPlatforms,
            CxxTestBuilder.createDefaultPlatform(),
            CxxTestBuilder.createDefaultPlatforms())
            .setMainModule("test")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(extensionBuilder.getTarget()));
    PythonBinaryBuilder binary3Builder =
        new PythonBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin3"),
            pythonBuckConfig,
            pythonPlatforms,
            CxxTestBuilder.createDefaultPlatform(),
            CxxTestBuilder.createDefaultPlatforms())
            .setMainModule("test")
            .setPlatform(PY3.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(extensionBuilder.getTarget()));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                py2Builder.build(),
                py3Builder.build(),
                depBuilder.build(),
                extensionBuilder.build(),
                binary2Builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    py2Builder.build(resolver);
    py3Builder.build(resolver);
    depBuilder.build(resolver);
    extensionBuilder.build(resolver);
    PythonBinary binary2 = (PythonBinary) binary2Builder.build(resolver);
    PythonBinary binary3 = (PythonBinary) binary3Builder.build(resolver);

    assertThat(
        binary2.getComponents().getNativeLibraries().keySet(),
        Matchers.contains(Paths.get("libdep.so")));
    assertThat(
        binary3.getComponents().getNativeLibraries().keySet(),
        Matchers.not(Matchers.contains(Paths.get("libdep.so"))));
  }

  @Test
  public void runtimeDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule cxxBinary =
        new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    new CxxLibraryBuilder(PYTHON2_DEP_TARGET).build(resolver);
    new CxxLibraryBuilder(PYTHON3_DEP_TARGET).build(resolver);
    CxxPythonExtension cxxPythonExtension =
        (CxxPythonExtension) new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:ext"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestBuilder.createDefaultPlatforms())
            .setDeps(ImmutableSortedSet.of(cxxBinary.getBuildTarget()))
            .build(resolver);
    assertThat(
        cxxPythonExtension.getRuntimeDeps(),
        Matchers.hasItem(cxxBinary));
  }

}
