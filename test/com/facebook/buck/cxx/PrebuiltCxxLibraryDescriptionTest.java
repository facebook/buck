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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PrebuiltCxxLibraryDescriptionTest {

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:target");
  private static final BuildRuleParams PARAMS =
      BuildRuleParamsFactory.createTrivialBuildRuleParams(TARGET);
  private static final BuildRuleResolver RESOLVER = new BuildRuleResolver();
  private static final PrebuiltCxxLibraryDescription DESC = new PrebuiltCxxLibraryDescription();

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
    PrebuiltCxxLibraryDescription.Arg arg = getDefaultArg();
    CxxLibrary lib = DESC.createBuildRule(PARAMS, RESOLVER, arg);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = new CxxPreprocessorInput(
        ImmutableSet.<BuildTarget>of(),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableList.<Path>of(),
        getIncludeDirs(arg));
    assertEquals(
        expectedCxxPreprocessorInput,
        lib.getCxxPreprocessorInput());

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(new PathSourcePath(getStaticLibraryPath(arg))),
        ImmutableList.of(getStaticLibraryPath(arg).toString()));
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(NativeLinkable.Type.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(new PathSourcePath(getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()));
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(NativeLinkable.Type.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = new PythonPackageComponents(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(getSharedLibrarySoname(arg)),
            new PathSourcePath(getSharedLibraryPath(arg))));
    assertEquals(
        expectedComponents,
        lib.getPythonPackageComponents());
  }

  @Test
  public void createBuildRuleHeaderOnly() {
    PrebuiltCxxLibraryDescription.Arg arg = getDefaultArg();
    arg.headerOnly = Optional.of(true);
    CxxLibrary lib = DESC.createBuildRule(PARAMS, RESOLVER, arg);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = new CxxPreprocessorInput(
        ImmutableSet.<BuildTarget>of(),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableList.<Path>of(),
        getIncludeDirs(arg));
    assertEquals(
        expectedCxxPreprocessorInput,
        lib.getCxxPreprocessorInput());

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(),
        ImmutableList.<String>of());
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(NativeLinkable.Type.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(),
        ImmutableList.<String>of());
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(NativeLinkable.Type.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = new PythonPackageComponents(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of());
    assertEquals(
        expectedComponents,
        lib.getPythonPackageComponents());
  }

  @Test
  public void createBuildRuleExternal() {
    PrebuiltCxxLibraryDescription.Arg arg = getDefaultArg();
    arg.provided = Optional.of(true);
    CxxLibrary lib = DESC.createBuildRule(PARAMS, RESOLVER, arg);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = new CxxPreprocessorInput(
        ImmutableSet.<BuildTarget>of(),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableList.<Path>of(),
        getIncludeDirs(arg));
    assertEquals(
        expectedCxxPreprocessorInput,
        lib.getCxxPreprocessorInput());

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(new PathSourcePath(getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()));
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(NativeLinkable.Type.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(new PathSourcePath(getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()));
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(NativeLinkable.Type.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = new PythonPackageComponents(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of());
    assertEquals(
        expectedComponents,
        lib.getPythonPackageComponents());
  }

  @Test
  public void createBuildRuleIncludeDirs() {
    PrebuiltCxxLibraryDescription.Arg arg = getDefaultArg();
    arg.includeDirs = Optional.of(ImmutableList.of("test"));
    CxxLibrary lib = DESC.createBuildRule(PARAMS, RESOLVER, arg);

    // Verify the preprocessable input is as expected.
    CxxPreprocessorInput expectedCxxPreprocessorInput = new CxxPreprocessorInput(
        ImmutableSet.<BuildTarget>of(),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableList.<Path>of(),
        getIncludeDirs(arg));
    assertEquals(
        expectedCxxPreprocessorInput,
        lib.getCxxPreprocessorInput());

    // Verify static native linkable input.
    NativeLinkableInput expectedStaticLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(new PathSourcePath(getStaticLibraryPath(arg))),
        ImmutableList.of(getStaticLibraryPath(arg).toString()));
    assertEquals(
        expectedStaticLinkableInput,
        lib.getNativeLinkableInput(NativeLinkable.Type.STATIC));

    // Verify shared native linkable input.
    NativeLinkableInput expectedSharedLinkableInput = new NativeLinkableInput(
        ImmutableList.<SourcePath>of(new PathSourcePath(getSharedLibraryPath(arg))),
        ImmutableList.of(getSharedLibraryPath(arg).toString()));
    assertEquals(
        expectedSharedLinkableInput,
        lib.getNativeLinkableInput(NativeLinkable.Type.SHARED));

    // Verify the python packageable components are correct.
    PythonPackageComponents expectedComponents = new PythonPackageComponents(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(getSharedLibrarySoname(arg)),
            new PathSourcePath(getSharedLibraryPath(arg))));
    assertEquals(
        expectedComponents,
        lib.getPythonPackageComponents());
  }

}
