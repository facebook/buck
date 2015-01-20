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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

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
        String.format("lib%s.so", libName));
  }

  private static String getSharedLibrarySoname(PrebuiltCxxLibraryDescription.Arg arg) {
    String libName = arg.libName.or(TARGET.getShortName());
    return arg.soname.or(String.format("lib%s.so", libName));
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

  @Test
  public void createBuildRuleDefault() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    PrebuiltCxxLibraryBuilder libBuilder = new PrebuiltCxxLibraryBuilder(TARGET);
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .build();
    assertEquals(
        expectedCxxPreprocessorInput,
        lib.getCxxPreprocessorInput(CXX_PLATFORM));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = ImmutableNativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(getStaticLibraryPath(arg))),
        ImmutableList.of(getStaticLibraryPath(arg).toString()));
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = ImmutableNativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()));
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = new PythonPackageComponents(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(getSharedLibrarySoname(arg)),
            new PathSourcePath(getSharedLibraryPath(arg))));
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
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .build();
    assertEquals(
        expectedCxxPreprocessorInput,
        lib.getCxxPreprocessorInput(CXX_PLATFORM));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = ImmutableNativeLinkableInput.of(
        ImmutableList.<SourcePath>of(),
        ImmutableList.<String>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = ImmutableNativeLinkableInput.of(
        ImmutableList.<SourcePath>of(),
        ImmutableList.<String>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = new PythonPackageComponents(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of());
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
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .build();
    assertEquals(
        expectedCxxPreprocessorInput,
        lib.getCxxPreprocessorInput(CXX_PLATFORM));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = ImmutableNativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()));
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = ImmutableNativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()));
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = new PythonPackageComponents(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of());
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
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) libBuilder.build(resolver, filesystem);
    PrebuiltCxxLibraryDescription.Arg arg = libBuilder.build().getConstructorArg();

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addAllSystemIncludeRoots(getIncludeDirs(arg))
        .build();
    assertEquals(
        expectedCxxPreprocessorInput,
        lib.getCxxPreprocessorInput(CXX_PLATFORM));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = ImmutableNativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(getStaticLibraryPath(arg))),
        ImmutableList.of(getStaticLibraryPath(arg).toString()));
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = ImmutableNativeLinkableInput.of(
        ImmutableList.<SourcePath>of(new PathSourcePath(getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()));
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, Linker.LinkableDepType.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = new PythonPackageComponents(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(getSharedLibrarySoname(arg)),
            new PathSourcePath(getSharedLibraryPath(arg))));
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
            new PathSourcePath(getSharedLibraryPath(arg))),
        lib.getSharedLibraries(CXX_PLATFORM));
  }

}
