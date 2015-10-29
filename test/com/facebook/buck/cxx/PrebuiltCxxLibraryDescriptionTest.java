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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.python.PythonTestUtils;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

public class PrebuiltCxxLibraryDescriptionTest {

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:target");
  private static final CxxPlatform CXX_PLATFORM = PrebuiltCxxLibraryBuilder.createDefaultPlatform();

  private static Path getStaticLibraryPath(PrebuiltCxxLibraryDescription.Arg arg) {
    String libDir = arg.libDir.or("lib");
    String libName = arg.libName.or(TARGET.getShortName());
    return TARGET.getBasePath().resolve(libDir).resolve(
        String.format("lib%s.a", libName));
  }

  private static Path getStaticPicLibraryPath(PrebuiltCxxLibraryDescription.Arg arg) {
    String libDir = arg.libDir.or("lib");
    String libName = arg.libName.or(TARGET.getShortName());
    return TARGET.getBasePath().resolve(libDir).resolve(
        String.format("lib%s_pic.a", libName));
  }

  private static Path getSharedLibraryPath(PrebuiltCxxLibraryDescription.Arg arg) {
    String libDir = arg.libDir.or("lib");
    String libName = arg.libName.or(TARGET.getShortName());
    return TARGET.getBasePath().resolve(libDir).resolve(
        String.format("lib%s.%s", libName, CXX_PLATFORM.getSharedLibraryExtension()));
  }

  private static String getSharedLibrarySoname(PrebuiltCxxLibraryDescription.Arg arg) {
    String libName = arg.libName.or(TARGET.getShortName());
    return arg.soname.or(
        String.format("lib%s.%s", libName, CXX_PLATFORM.getSharedLibraryExtension()));
  }

  private static ImmutableList<Path> getIncludeDirs(PrebuiltCxxLibraryDescription.Arg arg) {
    return FluentIterable
        .from(arg.includeDirs.or(ImmutableList.of("include")))
        .transform(
            new Function<String, Path>() {
              @Override
              public Path apply(String input) {
                return TARGET.getBasePath().resolve(input);
              }
            })
        .toList();
  }

  private static Iterable<BuildTarget> getInputRules(BuildRule buildRule) {
    return ImmutableList.of(
        BuildTarget.builder()
            .from(buildRule.getBuildTarget())
            .addFlavors(CXX_PLATFORM.getFlavor())
            .addFlavors(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
            .build());
  }

  private static Iterable<Path> getIncludeRoots(BuildRule buildRule) {
    return Iterables.transform(
        getInputRules(buildRule),
        new Function<BuildTarget, Path>() {
          @Override
          public Path apply(BuildTarget target) {
            return CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
                target,
                CXX_PLATFORM.getFlavor(),
                HeaderVisibility.PUBLIC);
          }
        });
  }

  @Test
  public void createBuildRuleDefault() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeRoots(lib))
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(
            targetGraph,
            CXX_PLATFORM,
            HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(
                pathResolver,
                new PathSourcePath(filesystem, getStaticLibraryPath(arg)))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(targetGraph, CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(
                pathResolver,
                new PathSourcePath(filesystem, getSharedLibraryPath(arg)))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(targetGraph, CXX_PLATFORM, Linker.LinkableDepType.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(getSharedLibrarySoname(arg)),
            new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
    assertEquals(
        expectedComponents,
        lib.getPythonPackageComponents(targetGraph, PythonTestUtils.PYTHON_PLATFORM, CXX_PLATFORM));
  }

  @Test
  public void createBuildRuleHeaderOnly() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setHeaderOnly(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeRoots(lib))
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(targetGraph, CXX_PLATFORM, HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(targetGraph, CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(targetGraph, CXX_PLATFORM, Linker.LinkableDepType.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
    assertEquals(
        expectedComponents,
        lib.getPythonPackageComponents(targetGraph, PythonTestUtils.PYTHON_PLATFORM, CXX_PLATFORM));
  }

  @Test
  public void createBuildRuleExternal() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setProvided(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeRoots(lib))
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(targetGraph, CXX_PLATFORM, HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(
                pathResolver,
                new PathSourcePath(filesystem, getSharedLibraryPath(arg)))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(targetGraph, CXX_PLATFORM, Linker.LinkableDepType.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
    assertEquals(
        expectedComponents,
        lib.getPythonPackageComponents(targetGraph, PythonTestUtils.PYTHON_PLATFORM, CXX_PLATFORM));
  }

  @Test
  public void createBuildRuleIncludeDirs() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setIncludeDirs(ImmutableList.of("test"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeRoots(lib))
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(targetGraph, CXX_PLATFORM, HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(
                pathResolver,
                new PathSourcePath(filesystem, getStaticLibraryPath(arg)))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(targetGraph, CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(
                pathResolver,
                new PathSourcePath(filesystem, getSharedLibraryPath(arg)))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(targetGraph, CXX_PLATFORM, Linker.LinkableDepType.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(getSharedLibrarySoname(arg)),
            new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
    assertEquals(
        expectedComponents,
        lib.getPythonPackageComponents(targetGraph, PythonTestUtils.PYTHON_PLATFORM, CXX_PLATFORM));
  }

  @Test
  public void missingSharedLibsAreAutoBuilt() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput = lib.getNativeLinkableInput(
        targetGraph,
        CXX_PLATFORM,
        Linker.LinkableDepType.SHARED);
    BuildRule rule =
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(Arg.getDepsFunction(pathResolver))
            .toList()
            .get(0);
    assertTrue(rule instanceof CxxLink);
  }

  @Test
  public void missingSharedLibsAreNotAutoBuiltForHeaderOnlyRules() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setHeaderOnly(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput = lib.getNativeLinkableInput(
        targetGraph,
        CXX_PLATFORM,
        Linker.LinkableDepType.SHARED);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(Arg.getDepsFunction(pathResolver))
            .toList(),
        Matchers.empty());
  }

  @Test
  public void addsLibsToAndroidPackageableCollector() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            getSharedLibrarySoname(arg),
            new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        lib.getSharedLibraries(targetGraph, CXX_PLATFORM));
  }

  @Test
  public void platformMacro() {
    Optional<String> libDir = Optional.of("libs/$(platform)");
    Optional<String> libName = Optional.of("test-$(platform)");
    Optional<String> soname = Optional.absent();

    CxxPlatform platform1 =
        CxxPlatformUtils.DEFAULT_PLATFORM
            .withFlavor(ImmutableFlavor.of("PLATFORM1"));
    CxxPlatform platform2 =
        CxxPlatformUtils.DEFAULT_PLATFORM
            .withFlavor(ImmutableFlavor.of("PLATFORM2"));

    assertEquals(
        String.format("libtest-PLATFORM1.%s", platform1.getSharedLibraryExtension()),
        PrebuiltCxxLibraryDescription.getSoname(
            TARGET,
            platform1, soname, libName));
    assertEquals(
        String.format("libtest-PLATFORM2.%s", platform2.getSharedLibraryExtension()),
        PrebuiltCxxLibraryDescription.getSoname(
            TARGET,
            platform2, soname, libName));

    assertEquals(
        TARGET.getBasePath()
            .resolve(
                String.format(
                    "libs/PLATFORM1/libtest-PLATFORM1.%s",
                    platform1.getSharedLibraryExtension())),
        PrebuiltCxxLibraryDescription.getSharedLibraryPath(TARGET, platform1, libDir, libName));
    assertEquals(
        TARGET.getBasePath()
            .resolve("libs/PLATFORM1/libtest-PLATFORM1.a"),
        PrebuiltCxxLibraryDescription.getStaticLibraryPath(TARGET, platform1, libDir, libName));

    assertEquals(
        TARGET.getBasePath()
            .resolve(
                String.format(
                    "libs/PLATFORM2/libtest-PLATFORM2.%s",
                    platform2.getSharedLibraryExtension())),
        PrebuiltCxxLibraryDescription.getSharedLibraryPath(TARGET, platform2, libDir, libName));
    assertEquals(
        TARGET.getBasePath()
            .resolve("libs/PLATFORM2/libtest-PLATFORM2.a"),
        PrebuiltCxxLibraryDescription.getStaticLibraryPath(TARGET, platform2, libDir, libName));
  }

  @Test
  public void createBuildRuleExportedHeaders() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setExportedHeaders(SourceList.ofUnnamedSources(ImmutableSortedSet.<SourcePath>of()));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .setSystemIncludeRoots(getIncludeRoots(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(
            targetGraph,
            CXX_PLATFORM,
            HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(
                pathResolver,
                new PathSourcePath(filesystem, getStaticLibraryPath(arg)))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(targetGraph, CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(
                pathResolver,
                new PathSourcePath(filesystem, getSharedLibraryPath(arg)))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(targetGraph, CXX_PLATFORM, Linker.LinkableDepType.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(getSharedLibrarySoname(arg)),
            new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
    assertEquals(
        expectedComponents,
        lib.getPythonPackageComponents(targetGraph, PythonTestUtils.PYTHON_PLATFORM, CXX_PLATFORM));
  }

  @Test
  public void createBuildRuleExportedPlatformHeaders() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setExportedPlatformHeaders(
            CXX_PLATFORM,
            SourceList.ofUnnamedSources(ImmutableSortedSet.<SourcePath>of()));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeRoots(lib))
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(targetGraph, CXX_PLATFORM, HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(
                pathResolver,
                new PathSourcePath(filesystem, getStaticLibraryPath(arg)))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(targetGraph, CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(
                pathResolver,
                new PathSourcePath(filesystem, getSharedLibraryPath(arg)))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(targetGraph, CXX_PLATFORM, Linker.LinkableDepType.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(getSharedLibrarySoname(arg)),
            new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
    assertEquals(
        expectedComponents,
        lib.getPythonPackageComponents(targetGraph, PythonTestUtils.PYTHON_PLATFORM, CXX_PLATFORM));
  }

  @Test
  public void createBuildRuleHeaderNamespace() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setHeaderNamespace("hello");
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeRoots(lib))
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(targetGraph, CXX_PLATFORM, HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(
                pathResolver,
                new PathSourcePath(filesystem, getStaticLibraryPath(arg)))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(targetGraph, CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(
                pathResolver,
                new PathSourcePath(filesystem, getSharedLibraryPath(arg)))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(targetGraph, CXX_PLATFORM, Linker.LinkableDepType.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(getSharedLibrarySoname(arg)),
            new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
    assertEquals(
        expectedComponents,
        lib.getPythonPackageComponents(targetGraph, PythonTestUtils.PYTHON_PLATFORM, CXX_PLATFORM));
  }

  @Test
  public void staticPicLibsUseCorrectPath() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            targetGraph,
            CXX_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transform(Arg.stringifyFunction())
            .toList()
            .get(0),
        Matchers.endsWith(getStaticLibraryPath(libBuilder.build().getConstructorArg()).toString()));
  }

  @Test
  public void missingStaticPicLibsUseStaticLibs() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    filesystem.touch(getStaticPicLibraryPath(libBuilder.build().getConstructorArg()));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder
        .build(resolver, filesystem, targetGraph);
    NativeLinkableInput nativeLinkableInput = lib.getNativeLinkableInput(
        targetGraph,
        CXX_PLATFORM,
        Linker.LinkableDepType.STATIC_PIC);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transform(Arg.stringifyFunction())
            .toList()
            .get(0),
        Matchers.endsWith(
            getStaticPicLibraryPath(
                libBuilder.build().getConstructorArg()).toString()));
  }

  @Test
  public void forceStatic() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Set<TargetNode<?>> targetNodes = Sets.newHashSet();
    PrebuiltCxxLibrary prebuiltCxxLibrary =
        (PrebuiltCxxLibrary) new PrebuiltCxxLibraryBuilder(TARGET)
            .setForceStatic(true)
            .build(resolver, filesystem, targetNodes);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(targetNodes);
    NativeLinkableInput nativeLinkableInput =
        prebuiltCxxLibrary.getNativeLinkableInput(
            targetGraph,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transform(Arg.stringifyFunction())
            .toList()
            .get(0),
        Matchers.endsWith(".a"));
    assertThat(
        prebuiltCxxLibrary.getSharedLibraries(
            targetGraph,
            CxxPlatformUtils.DEFAULT_PLATFORM)
            .entrySet(),
        Matchers.empty());
    assertThat(
        prebuiltCxxLibrary.getPythonPackageComponents(
            targetGraph,
            PythonTestUtils.PYTHON_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORM)
            .getNativeLibraries()
            .entrySet(),
        Matchers.empty());
  }

  @Test
  public void exportedLinkerFlagsAreUsedToBuildSharedLibrary() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildTarget target =
        BuildTarget.builder(BuildTargetFactory.newInstance("//:lib"))
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .addFlavors(CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor())
            .build();
    CxxLink cxxLink =
        (CxxLink) new PrebuiltCxxLibraryBuilder(target)
            .setExportedLinkerFlags(ImmutableList.of("--some-flag"))
            .setForceStatic(true)
            .build(resolver);
    assertThat(
        cxxLink.getArgs(),
        Matchers.hasItem("--some-flag"));
  }

  @Test
  public void nativeLinkableDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    PrebuiltCxxLibrary dep =
        (PrebuiltCxxLibrary) new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:r"))
            .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .build(resolver);
    assertThat(
        rule.getNativeLinkableDeps(CxxLibraryBuilder.createDefaultPlatform()),
        Matchers.<NativeLinkable>contains(dep));
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableExportedDeps(CxxLibraryBuilder.createDefaultPlatform())),
        Matchers.<NativeLinkable>empty());
  }

  @Test
  public void nativeLinkableExportedDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    PrebuiltCxxLibrary dep =
        (PrebuiltCxxLibrary) new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    PrebuiltCxxLibrary rule =
        (PrebuiltCxxLibrary) new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:r"))
            .setExportedDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .build(resolver);
    assertThat(
        ImmutableList.copyOf(rule.getNativeLinkableDeps(CxxLibraryBuilder.createDefaultPlatform())),
        Matchers.<NativeLinkable>empty());
    assertThat(
        rule.getNativeLinkableExportedDeps(CxxLibraryBuilder.createDefaultPlatform()),
        Matchers.<NativeLinkable>contains(dep));
  }

}
