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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PrebuiltCxxLibraryDescriptionTest {

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:target");
  private static final CxxPlatform CXX_PLATFORM = PrebuiltCxxLibraryBuilder.createDefaultPlatform();

  private static Path getStaticLibraryPath(PrebuiltCxxLibraryDescription.Arg arg) {
    String libDir = arg.libDir.or("lib");
    String libName = arg.libName.or(TARGET.getShortName());
    return TARGET.getBasePath().resolve(libDir).resolve(
        String.format("lib%s.a", libName));
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
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(
        resolver,
        filesystem,
        TargetGraphFactory.newInstance(libBuilder.build()));
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeRoots(lib))
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(
            CXX_PLATFORM,
            HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, getStaticLibraryPath(arg))),
        ImmutableList.of(getStaticLibraryPath(arg).toString()),
        ImmutableSet.<Path>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()),
        ImmutableSet.<Path>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));

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
        lib.getPythonPackageComponents(CXX_PLATFORM));
  }

  @Test
  public void createBuildRuleHeaderOnly() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setHeaderOnly(true);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(
        resolver,
        filesystem,
        TargetGraphFactory.newInstance(libBuilder.build()));
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeRoots(lib))
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(
            CXX_PLATFORM,
            HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(),
        ImmutableList.<String>of(),
        ImmutableSet.<Path>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(),
        ImmutableList.<String>of(),
        ImmutableSet.<Path>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
    assertEquals(
        expectedComponents,
        lib.getPythonPackageComponents(CXX_PLATFORM));
  }

  @Test
  public void createBuildRuleExternal() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setProvided(true);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(
        resolver,
        filesystem,
        TargetGraphFactory.newInstance(libBuilder.build()));
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeRoots(lib))
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(
            CXX_PLATFORM,
            HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()),
        ImmutableSet.<Path>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
    assertEquals(
        expectedComponents,
        lib.getPythonPackageComponents(CXX_PLATFORM));
  }

  @Test
  public void createBuildRuleIncludeDirs() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setIncludeDirs(ImmutableList.of("test"));
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(
        resolver,
        filesystem,
        TargetGraphFactory.newInstance(libBuilder.build()));
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeRoots(lib))
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(
            CXX_PLATFORM,
            HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, getStaticLibraryPath(arg))),
        ImmutableList.of(getStaticLibraryPath(arg).toString()),
        ImmutableSet.<Path>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()),
        ImmutableSet.<Path>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));

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
        lib.getPythonPackageComponents(CXX_PLATFORM));
  }

  @Test
  public void missingSharedLibsAreAutoBuilt() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(
        resolver,
        filesystem,
        TargetGraphFactory.newInstance(libBuilder.build()));
    NativeLinkableInput nativeLinkableInput = lib.getNativeLinkableInput(
        CXX_PLATFORM,
        Linker.LinkableDepType.SHARED);
    SourcePath input = nativeLinkableInput.getInputs().get(0);
    assertTrue(input instanceof BuildTargetSourcePath);
    assertTrue(new SourcePathResolver(resolver).getRule(input).get() instanceof CxxLink);
  }

  @Test
  public void missingSharedLibsAreNotAutoBuiltForHeaderOnlyRules() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setHeaderOnly(true);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(
        resolver,
        filesystem,
        TargetGraphFactory.newInstance(libBuilder.build()));
    NativeLinkableInput nativeLinkableInput = lib.getNativeLinkableInput(
        CXX_PLATFORM,
        Linker.LinkableDepType.SHARED);
    assertTrue(nativeLinkableInput.getInputs().isEmpty());
  }

  @Test
  public void addsLibsToAndroidPackageableCollector() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(
        resolver,
        filesystem,
        TargetGraphFactory.newInstance(libBuilder.build()));
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            getSharedLibrarySoname(arg),
            new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        lib.getSharedLibraries(CXX_PLATFORM));
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
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setExportedHeaders(SourceList.ofUnnamedSources(ImmutableSortedSet.<SourcePath>of()));
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(
        resolver,
        filesystem,
        TargetGraphFactory.newInstance(libBuilder.build()));
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .setSystemIncludeRoots(getIncludeRoots(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(
            CXX_PLATFORM,
            HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, getStaticLibraryPath(arg))),
        ImmutableList.of(getStaticLibraryPath(arg).toString()),
        ImmutableSet.<Path>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()),
        ImmutableSet.<Path>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));

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
        lib.getPythonPackageComponents(CXX_PLATFORM));
  }

  @Test
  public void createBuildRuleExportedPlatformHeaders() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setExportedPlatformHeaders(
            CXX_PLATFORM,
            SourceList.ofUnnamedSources(ImmutableSortedSet.<SourcePath>of()));
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(
        resolver,
        filesystem,
        TargetGraphFactory.newInstance(libBuilder.build()));
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeRoots(lib))
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(
            CXX_PLATFORM,
            HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, getStaticLibraryPath(arg))),
        ImmutableList.of(getStaticLibraryPath(arg).toString()),
        ImmutableSet.<Path>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()),
        ImmutableSet.<Path>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));

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
        lib.getPythonPackageComponents(CXX_PLATFORM));
  }

  @Test
  public void createBuildRuleHeaderNamespace() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET)
        .setHeaderNamespace("hello");
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(
        resolver,
        filesystem,
        TargetGraphFactory.newInstance(libBuilder.build()));
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeRoots(lib))
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .setRules(getInputRules(lib))
        .build();
    assertThat(
        lib.getCxxPreprocessorInput(
            CXX_PLATFORM,
            HeaderVisibility.PUBLIC),
        Matchers.equalTo(expectedCxxPreprocessorInput));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, getStaticLibraryPath(arg))),
        ImmutableList.of(getStaticLibraryPath(arg).toString()),
        ImmutableSet.<Path>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()),
        ImmutableSet.<Path>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));

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
        lib.getPythonPackageComponents(CXX_PLATFORM));
  }
}
