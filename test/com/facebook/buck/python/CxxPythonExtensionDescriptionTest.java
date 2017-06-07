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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBinaryBuilder;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.CxxTestUtils;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkTarget;
import com.facebook.buck.cxx.NativeLinkTargetMode;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.cxx.PrebuiltCxxLibraryBuilder;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxPythonExtensionDescriptionTest {

  private static final BuildTarget PYTHON2_DEP_TARGET =
      BuildTargetFactory.newInstance("//:python2_dep");
  private static final PythonPlatform PY2 =
      PythonPlatform.of(
          InternalFlavor.of("py2"),
          new PythonEnvironment(Paths.get("python2"), PythonVersion.of("CPython", "2.6")),
          Optional.empty());

  private static final BuildTarget PYTHON3_DEP_TARGET =
      BuildTargetFactory.newInstance("//:python3_dep");
  private static final PythonPlatform PY3 =
      PythonPlatform.of(
          InternalFlavor.of("py3"),
          new PythonEnvironment(Paths.get("python3"), PythonVersion.of("CPython", "3.5")),
          Optional.empty());

  @Test
  public void createBuildRuleBaseModule() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            target,
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestUtils.createDefaultPlatforms());

    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    CxxPythonExtension normal = builder.build(resolver, filesystem, targetGraph);

    PythonPackageComponents normalComps =
        normal.getPythonPackageComponents(PY2, CxxPlatformUtils.DEFAULT_PLATFORM);
    assertEquals(
        ImmutableSet.of(
            target
                .getBasePath()
                .resolve(CxxPythonExtensionDescription.getExtensionName(target.getShortName()))),
        normalComps.getModules().keySet());

    // Verify that explicitly setting works.
    BuildTarget target2 = BuildTargetFactory.newInstance("//:target2#py2");
    String name = "blah";
    CxxPythonExtensionBuilder baseModuleBuilder =
        new CxxPythonExtensionBuilder(
                target2,
                FlavorDomain.of("Python Platform", PY2, PY3),
                new CxxBuckConfig(FakeBuckConfig.builder().build()),
                CxxTestUtils.createDefaultPlatforms())
            .setBaseModule(name);
    targetGraph = TargetGraphFactory.newInstance(baseModuleBuilder.build());
    resolver = new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    CxxPythonExtension baseModule = baseModuleBuilder.build(resolver, filesystem, targetGraph);
    PythonPackageComponents baseModuleComps =
        baseModule.getPythonPackageComponents(PY2, CxxPlatformUtils.DEFAULT_PLATFORM);
    assertEquals(
        ImmutableSet.of(
            Paths.get(name)
                .resolve(CxxPythonExtensionDescription.getExtensionName(target2.getShortName()))),
        baseModuleComps.getModules().keySet());
  }

  @Test
  public void createBuildRuleNativeLinkableDep() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    BuildTarget cxxLibraryTarget = BuildTargetFactory.newInstance("//:dep");
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(cxxLibraryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath("something.cpp"), ImmutableList.of())));
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
                target,
                FlavorDomain.of("Python Platform", PY2, PY3),
                new CxxBuckConfig(FakeBuckConfig.builder().build()),
                CxxTestUtils.createDefaultPlatforms())
            .setDeps(ImmutableSortedSet.of(cxxLibraryTarget));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(cxxLibraryBuilder.build(), builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    CxxLibrary dep = (CxxLibrary) cxxLibraryBuilder.build(resolver, filesystem, targetGraph);
    CxxPythonExtension extension = builder.build(resolver, filesystem, targetGraph);

    NativeLinkableInput depInput =
        dep.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED);

    // Verify that the shared library dep propagated to the link rule.
    extension.getPythonPackageComponents(PY2, CxxPlatformUtils.DEFAULT_PLATFORM);
    BuildRule rule =
        resolver.getRule(
            CxxPythonExtensionDescription.getExtensionTarget(
                target, PY2.getFlavor(), CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor()));
    assertThat(
        rule.getBuildDeps(),
        Matchers.hasItems(
            FluentIterable.from(depInput.getArgs())
                .transformAndConcat(arg -> arg.getDeps(ruleFinder))
                .toArray(BuildRule.class)));
  }

  @Test
  public void createBuildRulePythonPackageable() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            target,
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestUtils.createDefaultPlatforms());

    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    CxxPythonExtension extension = builder.build(resolver, filesystem, targetGraph);

    // Verify that we get the expected view from the python packageable interface.
    PythonPackageComponents actualComponent =
        extension.getPythonPackageComponents(PY2, CxxPlatformUtils.DEFAULT_PLATFORM);
    BuildRule rule =
        resolver.getRule(
            CxxPythonExtensionDescription.getExtensionTarget(
                target, PY2.getFlavor(), CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor()));
    PythonPackageComponents expectedComponents =
        PythonPackageComponents.of(
            ImmutableMap.of(
                target
                    .getBasePath()
                    .resolve(CxxPythonExtensionDescription.getExtensionName(target.getShortName())),
                rule.getSourcePathToOutput()),
            ImmutableMap.of(),
            ImmutableMap.of(),
            ImmutableSet.of(),
            Optional.of(false));
    assertEquals(expectedComponents, actualComponent);
  }

  @Test
  public void findDepsFromParamsAddsPythonDep() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionDescription desc =
        new CxxPythonExtensionBuilder(
                target,
                FlavorDomain.of(
                    "Python Platform",
                    PY2.withCxxLibrary(PYTHON2_DEP_TARGET),
                    PY3.withCxxLibrary(PYTHON3_DEP_TARGET)),
                new CxxBuckConfig(FakeBuckConfig.builder().build()),
                CxxTestUtils.createDefaultPlatforms())
            .build()
            .getDescription();
    CxxPythonExtensionDescriptionArg constructorArg =
        CxxPythonExtensionDescriptionArg.builder().setName("target").build();
    ImmutableSortedSet.Builder<BuildTarget> builder = ImmutableSortedSet.naturalOrder();
    desc.findDepsForTargetFromConstructorArgs(
        BuildTargetFactory.newInstance("//foo:bar"),
        createCellRoots(filesystem),
        constructorArg,
        builder,
        ImmutableSortedSet.naturalOrder());
    assertThat(builder.build(), Matchers.contains(PYTHON2_DEP_TARGET, PYTHON3_DEP_TARGET));
  }

  @Test
  public void py2AndPy3PropagateToLinkRules() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    PrebuiltCxxLibraryBuilder python2Builder =
        new PrebuiltCxxLibraryBuilder(PYTHON2_DEP_TARGET)
            .setHeaderOnly(true)
            .setExportedLinkerFlags(ImmutableList.of("-lpython2"));
    PrebuiltCxxLibraryBuilder python3Builder =
        new PrebuiltCxxLibraryBuilder(PYTHON3_DEP_TARGET)
            .setHeaderOnly(true)
            .setExportedLinkerFlags(ImmutableList.of("-lpython3"));

    PythonPlatform py2 = PY2.withCxxLibrary(PYTHON2_DEP_TARGET);
    PythonPlatform py3 = PY3.withCxxLibrary(PYTHON3_DEP_TARGET);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            target,
            FlavorDomain.of("Python Platform", py2, py3),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestUtils.createDefaultPlatforms());

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            python2Builder.build(), python3Builder.build(), builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));

    python2Builder.build(resolver, filesystem, targetGraph);
    python3Builder.build(resolver, filesystem, targetGraph);
    CxxPythonExtension extension = builder.build(resolver, filesystem, targetGraph);

    // Get the py2 extension, and verify it pulled in the py2 lib but not the py3 lib.
    CxxLink py2Ext = (CxxLink) extension.getExtension(py2, CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(
        Arg.stringify(py2Ext.getArgs(), pathResolver),
        Matchers.allOf(Matchers.hasItem("-lpython2"), Matchers.not(Matchers.hasItem("-lpython3"))));

    // Get the py3 extension, and verify it pulled in the py3 lib but not the py2 lib.
    CxxLink py3Ext = (CxxLink) extension.getExtension(py3, CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(
        Arg.stringify(py3Ext.getArgs(), pathResolver),
        Matchers.allOf(Matchers.hasItem("-lpython3"), Matchers.not(Matchers.hasItem("-lpython2"))));
  }

  @Test
  public void nativeLinkTargetMode() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestUtils.createDefaultPlatforms());
    CxxPythonExtension rule = builder.build(resolver);
    NativeLinkTarget nativeLinkTarget = rule.getNativeLinkTarget(PY2);
    assertThat(
        nativeLinkTarget.getNativeLinkTargetMode(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.equalTo(NativeLinkTargetMode.library()));
  }

  @Test
  public void nativeLinkTargetDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary dep =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep")).build(resolver);
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestUtils.createDefaultPlatforms());
    CxxPythonExtension rule =
        builder.setDeps(ImmutableSortedSet.of(dep.getBuildTarget())).build(resolver);
    NativeLinkTarget nativeLinkTarget = rule.getNativeLinkTarget(PY2);
    assertThat(
        ImmutableList.copyOf(
            nativeLinkTarget.getNativeLinkTargetDeps(CxxPlatformUtils.DEFAULT_PLATFORM)),
        Matchers.<NativeLinkable>hasItem(dep));
  }

  @Test
  public void nativeLinkTargetDepsIncludePlatformCxxLibrary() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder python2Builder = new CxxLibraryBuilder(PYTHON2_DEP_TARGET);
    PythonPlatform platform = PY2.withCxxLibrary(PYTHON2_DEP_TARGET);
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", platform),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestUtils.createDefaultPlatforms());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(python2Builder.build(), builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    python2Builder.build(resolver, filesystem, targetGraph);
    CxxPythonExtension rule = builder.build(resolver, filesystem, targetGraph);
    NativeLinkTarget nativeLinkTarget = rule.getNativeLinkTarget(platform);
    assertThat(
        ImmutableList.copyOf(
            nativeLinkTarget.getNativeLinkTargetDeps(CxxPlatformUtils.DEFAULT_PLATFORM)),
        Matchers.hasItem((NativeLinkable) resolver.getRule(PYTHON2_DEP_TARGET)));
  }

  @Test
  public void nativeLinkTargetInput() throws Exception {
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestUtils.createDefaultPlatforms());
    builder.setLinkerFlags(ImmutableList.of(StringWithMacrosUtils.format("--flag")));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    CxxPythonExtension rule = builder.build(resolver);
    NativeLinkTarget nativeLinkTarget = rule.getNativeLinkTarget(PY2);
    NativeLinkableInput input =
        nativeLinkTarget.getNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(Arg.stringify(input.getArgs(), pathResolver), Matchers.hasItems("--flag"));
  }

  @Test
  public void platformDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxLibrary dep =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep")).build(resolver);
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxTestUtils.createDefaultPlatforms());
    CxxPythonExtension rule =
        builder
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(PY2.getFlavor().toString()),
                        ImmutableSortedSet.of(dep.getBuildTarget()))
                    .build())
            .build(resolver);
    NativeLinkTarget py2NativeLinkTarget = rule.getNativeLinkTarget(PY2);
    assertThat(
        ImmutableList.copyOf(
            py2NativeLinkTarget.getNativeLinkTargetDeps(CxxPlatformUtils.DEFAULT_PLATFORM)),
        Matchers.<NativeLinkable>hasItem(dep));
    NativeLinkTarget py3NativeLinkTarget = rule.getNativeLinkTarget(PY3);
    assertThat(
        ImmutableList.copyOf(
            py3NativeLinkTarget.getNativeLinkTargetDeps(CxxPlatformUtils.DEFAULT_PLATFORM)),
        Matchers.not(Matchers.<NativeLinkable>hasItem(dep)));
  }

  @Test
  public void platformDepsSeparateLinkage() throws Exception {
    PythonBuckConfig pythonBuckConfig =
        new PythonBuckConfig(FakeBuckConfig.builder().build(), new ExecutableFinder());
    FlavorDomain<PythonPlatform> pythonPlatforms = FlavorDomain.of("Python Platform", PY2, PY3);

    CxxLibraryBuilder depBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test.c"))));
    CxxPythonExtensionBuilder extensionBuilder =
        new CxxPythonExtensionBuilder(
                BuildTargetFactory.newInstance("//:rule"),
                pythonPlatforms,
                new CxxBuckConfig(FakeBuckConfig.builder().build()),
                CxxTestUtils.createDefaultPlatforms())
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
                CxxPlatformUtils.DEFAULT_PLATFORM,
                CxxTestUtils.createDefaultPlatforms())
            .setMainModule("test")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(extensionBuilder.getTarget()));
    PythonBinaryBuilder binary3Builder =
        new PythonBinaryBuilder(
                BuildTargetFactory.newInstance("//:bin3"),
                pythonBuckConfig,
                pythonPlatforms,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                CxxTestUtils.createDefaultPlatforms())
            .setMainModule("test")
            .setPlatform(PY3.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(extensionBuilder.getTarget()));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                depBuilder.build(), extensionBuilder.build(), binary2Builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    depBuilder.build(resolver);
    extensionBuilder.build(resolver);
    PythonBinary binary2 = binary2Builder.build(resolver);
    PythonBinary binary3 = binary3Builder.build(resolver);

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
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep#sandbox")).build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRule cxxBinary = new CxxBinaryBuilder(depTarget).build(resolver);
    CxxPythonExtension cxxPythonExtension =
        new CxxPythonExtensionBuilder(
                BuildTargetFactory.newInstance("//:ext"),
                FlavorDomain.of("Python Platform", PY2, PY3),
                new CxxBuckConfig(FakeBuckConfig.builder().build()),
                CxxTestUtils.createDefaultPlatforms())
            .setDeps(ImmutableSortedSet.of(cxxBinary.getBuildTarget()))
            .build(resolver);
    assertThat(
        cxxPythonExtension.getRuntimeDeps().collect(MoreCollectors.toImmutableSet()),
        Matchers.hasItem(cxxBinary.getBuildTarget()));
  }

  @Test
  public void moduleName() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    CxxPythonExtension cxxPythonExtension =
        new CxxPythonExtensionBuilder(
                BuildTargetFactory.newInstance("//:ext"),
                FlavorDomain.of("Python Platform", PY2, PY3),
                new CxxBuckConfig(FakeBuckConfig.builder().build()),
                CxxTestUtils.createDefaultPlatforms())
            .setModuleName("blah")
            .build(resolver);
    assertThat(
        cxxPythonExtension.getModule().toString(),
        Matchers.endsWith(CxxPythonExtensionDescription.getExtensionName("blah")));
  }
}
