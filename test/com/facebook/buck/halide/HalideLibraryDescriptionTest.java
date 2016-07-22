/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.halide;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxSymlinkTreeHeaders;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

public class HalideLibraryDescriptionTest {

  @Test
  public void testCreateBuildRule() throws Exception {
    // Set up a #halide-compiler rule, then set up a halide_library rule, and
    // check that the library rule depends on the compiler rule.
    BuildTarget compilerTarget = BuildTargetFactory
        .newInstance("//:rule")
        .withFlavors(HalideLibraryDescription.HALIDE_COMPILER_FLAVOR);
    BuildTarget libTarget = BuildTargetFactory.newInstance("//:rule");

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    HalideLibraryBuilder compilerBuilder =
        new HalideLibraryBuilder(compilerTarget);
    compilerBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(
                new FakeSourcePath("main.cpp"))));
    HalideLibraryBuilder libBuilder = new HalideLibraryBuilder(libTarget);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        compilerBuilder.build(),
        libBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    HalideLibrary lib = (HalideLibrary) libBuilder.build(
        resolver,
        filesystem,
        targetGraph);

    // Check that the library rule has the correct preprocessor input.
    CxxPlatform cxxPlatform = CxxLibraryBuilder.createDefaultPlatform();
    String headerName = "rule.h";
    BuildTarget flavoredLibTarget = libTarget.withFlavors(
        HalideLibraryDescription.HALIDE_COMPILE_FLAVOR,
        cxxPlatform.getFlavor());
    Path headerPath = HalideCompile.headerOutputPath(flavoredLibTarget, lib.getProjectFilesystem());
    CxxSymlinkTreeHeaders publicHeaders =
        (CxxSymlinkTreeHeaders) lib.getCxxPreprocessorInput(
            cxxPlatform,
            HeaderVisibility.PUBLIC).getIncludes()
            .get(0);
    assertThat(
        publicHeaders.getIncludeType(),
        Matchers.equalTo(CxxPreprocessables.IncludeType.SYSTEM));
    assertThat(
        publicHeaders.getNameToPathMap(),
        Matchers.equalTo(
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(headerName),
                new BuildTargetSourcePath(
                    flavoredLibTarget,
                    headerPath))));

    // Check that the library rule has the correct native linkable input.
    NativeLinkableInput input = lib.getNativeLinkableInput(
        cxxPlatform,
        Linker.LinkableDepType.STATIC);
    BuildRule buildRule =
        FluentIterable.from(input.getArgs())
            .transformAndConcat(Arg.getDepsFunction(new SourcePathResolver(resolver)))
            .get(0);
    assertThat(buildRule, is(instanceOf(Archive.class)));
  }

  @Test
  public void supportedPlatforms() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");

    // First, make sure without any platform regex, we get something back for each of the interface
    // methods.
    HalideLibraryBuilder halideLibraryBuilder =
        new HalideLibraryBuilder(target);
    TargetGraph targetGraph1 = TargetGraphFactory.newInstance(halideLibraryBuilder.build());
    BuildRuleResolver resolver1 =
        new BuildRuleResolver(targetGraph1, new DefaultTargetNodeToBuildRuleTransformer());
    HalideLibrary halideLibrary = (HalideLibrary) halideLibraryBuilder
        .build(resolver1, filesystem, targetGraph1);
    assertThat(
        halideLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Linker.LinkableDepType.STATIC)
            .getArgs(),
        Matchers.not(Matchers.empty()));

    // Now, verify we get nothing when the supported platform regex excludes our platform.
    halideLibraryBuilder.setSupportedPlatformsRegex(Pattern.compile("nothing"));
    TargetGraph targetGraph2 = TargetGraphFactory.newInstance(halideLibraryBuilder.build());
    BuildRuleResolver resolver2 =
        new BuildRuleResolver(targetGraph2, new DefaultTargetNodeToBuildRuleTransformer());
    halideLibrary = (HalideLibrary) halideLibraryBuilder
        .build(resolver2, filesystem, targetGraph2);
    assertThat(
        halideLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Linker.LinkableDepType.STATIC)
            .getArgs(),
        Matchers.empty());
  }

  @Test
  public void extraCompilerFlags() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ImmutableList<String> extraCompilerFlags = ImmutableList.<String>builder()
      .add("--test-flag")
      .add("test-value")
      .add("$TEST_MACRO")
      .build();

    // Set up a #halide-compile rule, then check its build steps.
    BuildTarget compileTarget = BuildTargetFactory
        .newInstance("//:rule")
        .withFlavors(HalideLibraryDescription.HALIDE_COMPILE_FLAVOR);
    HalideLibraryBuilder compileBuilder = new HalideLibraryBuilder(compileTarget);
    compileBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(
                new FakeSourcePath("main.cpp"))));

    // First, make sure the compile step doesn't include the extra flags.
    TargetGraph targetGraph = TargetGraphFactory.newInstance(compileBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    HalideCompile compile = (HalideCompile) compileBuilder.build(
        resolver,
        filesystem,
        targetGraph);

    ImmutableList<Step> buildSteps = compile.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT, new FakeBuildableContext()
    );
    HalideCompilerStep compilerStep = (HalideCompilerStep) buildSteps.get(1);
    ImmutableList<String> shellCommand = compilerStep.getShellCommandInternal(
        TestExecutionContext.newInstance());
    assertThat(
        shellCommand,
        Matchers.not(Matchers.hasItems("--test-flag", "test-value")));

    // Next verify that the shell command picks up on the extra compiler flags.
    compileBuilder.setCompilerInvocationFlags(extraCompilerFlags);
    targetGraph = TargetGraphFactory.newInstance(compileBuilder.build());
    resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    compile = (HalideCompile) compileBuilder.build(
        resolver,
        filesystem,
        targetGraph);

    buildSteps = compile.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT, new FakeBuildableContext()
    );
    compilerStep = (HalideCompilerStep) buildSteps.get(1);
    shellCommand = compilerStep.getShellCommandInternal(TestExecutionContext.newInstance());
    assertThat(
        shellCommand,
        Matchers.hasItems("--test-flag", "test-value", "test_macro_expansion"));
  }
}
