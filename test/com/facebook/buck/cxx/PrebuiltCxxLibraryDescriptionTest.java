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
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PrebuiltCxxLibraryDescriptionTest {

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

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:target");
  private static final CxxPlatform CXX_PLATFORM = new DefaultCxxPlatform(new FakeBuckConfig());
  private static final FlavorDomain<CxxPlatform> CXX_PLATFORMS =
      new FlavorDomain<>(
          "C/C++ Platform",
          ImmutableMap.of(CXX_PLATFORM.asFlavor(), CXX_PLATFORM));
  private static final PrebuiltCxxLibraryDescription DESC =
      new PrebuiltCxxLibraryDescription(CXX_PLATFORMS);

  private static PrebuiltCxxLibraryDescription.Arg getDefaultArg() {
    PrebuiltCxxLibraryDescription.Arg arg = DESC.createUnpopulatedConstructorArg();
    arg.includeDirs = Optional.absent();
    arg.libName = Optional.absent();
    arg.libDir = Optional.absent();
    arg.headerOnly = Optional.absent();
    arg.provided = Optional.absent();
    arg.linkWhole = Optional.absent();
    arg.soname = Optional.absent();
    arg.deps = Optional.absent();
    return arg;
  }

  private static Path getStaticLibraryPath(PrebuiltCxxLibraryDescription.Arg arg) {
    String libDir = arg.libDir.or("lib");
    String libName = arg.libName.or(TARGET.getShortNameOnly());
    return TARGET.getBasePath().resolve(libDir).resolve(
        String.format("lib%s.a", libName));
  }

  private static Path getSharedLibraryPath(PrebuiltCxxLibraryDescription.Arg arg) {
    String libDir = arg.libDir.or("lib");
    String libName = arg.libName.or(TARGET.getShortNameOnly());
    return TARGET.getBasePath().resolve(libDir).resolve(
        String.format("lib%s.so", libName));
  }

  private static String getSharedLibrarySoname(PrebuiltCxxLibraryDescription.Arg arg) {
    String libName = arg.libName.or(TARGET.getShortNameOnly());
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
    PrebuiltCxxLibraryDescription.Arg arg = getDefaultArg();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(TARGET)
        .setProjectFilesystem(filesystem)
        .build();
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) DESC.createBuildRule(params, resolver, arg);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .setSystemIncludeRoots(getIncludeDirs(arg))
        .build();
    assertEquals(
        expectedCxxPreprocessorInput,
        lib.getCxxPreprocessorInput(CXX_PLATFORM));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(new PathSourcePath(getStaticLibraryPath(arg))),
        ImmutableList.of(getStaticLibraryPath(arg).toString()));
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, NativeLinkable.Type.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(new PathSourcePath(getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()));
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, NativeLinkable.Type.SHARED));

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
    PrebuiltCxxLibraryDescription.Arg arg = getDefaultArg();
    arg.headerOnly = Optional.of(true);
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(TARGET)
        .setProjectFilesystem(filesystem)
        .build();
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) DESC.createBuildRule(params, resolver, arg);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .setSystemIncludeRoots(getIncludeDirs(arg))
        .build();
    assertEquals(
        expectedCxxPreprocessorInput,
        lib.getCxxPreprocessorInput(CXX_PLATFORM));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(),
        ImmutableList.<String>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, NativeLinkable.Type.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(),
        ImmutableList.<String>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, NativeLinkable.Type.SHARED));

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
    PrebuiltCxxLibraryDescription.Arg arg = getDefaultArg();
    arg.provided = Optional.of(true);
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(TARGET)
        .setProjectFilesystem(filesystem)
        .build();
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) DESC.createBuildRule(params, resolver, arg);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .setSystemIncludeRoots(getIncludeDirs(arg))
        .build();
    assertEquals(
        expectedCxxPreprocessorInput,
        lib.getCxxPreprocessorInput(CXX_PLATFORM));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(new PathSourcePath(getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()));
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, NativeLinkable.Type.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(new PathSourcePath(getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()));
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, NativeLinkable.Type.SHARED));

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
    PrebuiltCxxLibraryDescription.Arg arg = getDefaultArg();
    arg.includeDirs = Optional.of(ImmutableList.of("test"));
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(TARGET)
        .setProjectFilesystem(filesystem)
        .build();
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) DESC.createBuildRule(params, resolver, arg);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .setSystemIncludeRoots(getIncludeDirs(arg))
        .build();
    assertEquals(
        expectedCxxPreprocessorInput,
        lib.getCxxPreprocessorInput(CXX_PLATFORM));

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(new PathSourcePath(getStaticLibraryPath(arg))),
        ImmutableList.of(getStaticLibraryPath(arg).toString()));
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, NativeLinkable.Type.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(new PathSourcePath(getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()));
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(CXX_PLATFORM, NativeLinkable.Type.SHARED));

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
    PrebuiltCxxLibraryDescription.Arg arg = getDefaultArg();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(TARGET)
        .setProjectFilesystem(filesystem)
        .setTargetGraph(
            TargetGraphFactory.newInstance(
                createTargetNode(TARGET, DESC, arg)))
        .build();
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) DESC.createBuildRule(params, resolver, arg);
    NativeLinkableInput nativeLinkableInput = lib.getNativeLinkableInput(
        CXX_PLATFORM,
        NativeLinkable.Type.SHARED);
    SourcePath input = nativeLinkableInput.getInputs().get(0);
    assertTrue(input instanceof BuildTargetSourcePath);
    assertTrue(new SourcePathResolver(resolver).getRule(input).get() instanceof CxxLink);
  }

  @Test
  public void missingSharedLibsAreNotAutoBuiltForHeaderOnlyRules() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    PrebuiltCxxLibraryDescription.Arg arg = getDefaultArg();
    arg.headerOnly = Optional.of(true);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(TARGET)
        .setProjectFilesystem(filesystem)
        .build();
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) DESC.createBuildRule(params, resolver, arg);
    NativeLinkableInput nativeLinkableInput = lib.getNativeLinkableInput(
        CXX_PLATFORM,
        NativeLinkable.Type.SHARED);
    assertTrue(nativeLinkableInput.getInputs().isEmpty());
  }

  @Test
  public void addsLibsToAndroidPackageableCollector() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    PrebuiltCxxLibraryDescription.Arg arg = getDefaultArg();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(TARGET)
        .setProjectFilesystem(filesystem)
        .build();
    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) DESC.createBuildRule(params, resolver, arg);
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            getSharedLibrarySoname(arg),
            new PathSourcePath(getSharedLibraryPath(arg))),
        lib.getSharedLibraries(CXX_PLATFORM));
  }

}
