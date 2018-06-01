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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxSymlinkTreeHeaders;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class HalideLibraryDescriptionTest {

  @Test
  public void testCreateBuildRule() throws Exception {
    // Set up a #halide-compiler rule, then set up a halide_library rule, and
    // check that the library rule depends on the compiler rule.
    BuildTarget compilerTarget =
        BuildTargetFactory.newInstance("//:rule")
            .withFlavors(HalideLibraryDescription.HALIDE_COMPILER_FLAVOR);
    BuildTarget libTarget = BuildTargetFactory.newInstance("//:rule");

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    HalideLibraryBuilder compilerBuilder = new HalideLibraryBuilder(compilerTarget);
    compilerBuilder.setSrcs(
        ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("main.cpp"))));
    HalideLibraryBuilder libBuilder = new HalideLibraryBuilder(libTarget);
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(compilerBuilder.build(), libBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    HalideLibrary lib = (HalideLibrary) libBuilder.build(graphBuilder, filesystem, targetGraph);

    // Check that the library rule has the correct preprocessor input.
    CxxPlatform cxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;
    String headerName = "rule.h";
    BuildTarget flavoredLibTarget =
        libTarget.withFlavors(
            HalideLibraryDescription.HALIDE_COMPILE_FLAVOR, cxxPlatform.getFlavor());
    Path headerPath =
        HalideCompile.headerOutputPath(
            flavoredLibTarget, lib.getProjectFilesystem(), Optional.empty());
    CxxSymlinkTreeHeaders publicHeaders =
        (CxxSymlinkTreeHeaders)
            lib.getCxxPreprocessorInput(cxxPlatform, graphBuilder).getIncludes().get(0);
    assertThat(
        publicHeaders.getIncludeType(), Matchers.equalTo(CxxPreprocessables.IncludeType.SYSTEM));
    assertThat(
        publicHeaders.getNameToPathMap(),
        Matchers.equalTo(
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(headerName),
                ExplicitBuildTargetSourcePath.of(flavoredLibTarget, headerPath))));

    // Check that the library rule has the correct native linkable input.
    NativeLinkableInput input =
        lib.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC, graphBuilder);
    BuildRule buildRule =
        FluentIterable.from(input.getArgs())
            .transformAndConcat(
                arg ->
                    BuildableSupport.getDepsCollection(arg, new SourcePathRuleFinder(graphBuilder)))
            .get(0);
    assertThat(buildRule, is(instanceOf(Archive.class)));
  }

  @Test
  public void supportedPlatforms() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");

    // First, make sure without any platform regex, we get something back for each of the interface
    // methods.
    HalideLibraryBuilder halideLibraryBuilder = new HalideLibraryBuilder(target);
    TargetGraph targetGraph1 = TargetGraphFactory.newInstance(halideLibraryBuilder.build());
    ActionGraphBuilder graphBuilder1 = new TestActionGraphBuilder(targetGraph1);
    HalideLibrary halideLibrary =
        (HalideLibrary) halideLibraryBuilder.build(graphBuilder1, filesystem, targetGraph1);
    assertThat(
        halideLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, graphBuilder1)
            .getArgs(),
        not(Matchers.empty()));

    // Now, verify we get nothing when the supported platform regex excludes our platform.
    halideLibraryBuilder.setSupportedPlatformsRegex(Pattern.compile("nothing"));
    TargetGraph targetGraph2 = TargetGraphFactory.newInstance(halideLibraryBuilder.build());
    ActionGraphBuilder graphBuilder2 = new TestActionGraphBuilder(targetGraph2);
    halideLibrary =
        (HalideLibrary) halideLibraryBuilder.build(graphBuilder2, filesystem, targetGraph2);
    assertThat(
        halideLibrary
            .getNativeLinkableInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, graphBuilder2)
            .getArgs(),
        Matchers.empty());
  }

  @Test
  public void extraCompilerFlags() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ImmutableList<String> extraCompilerFlags =
        ImmutableList.<String>builder()
            .add("--test-flag")
            .add("test-value")
            .add("$TEST_MACRO")
            .build();

    // Set up a #halide-compile rule, then check its build steps.
    BuildTarget compileTarget =
        BuildTargetFactory.newInstance("//:rule")
            .withFlavors(HalideLibraryDescription.HALIDE_COMPILE_FLAVOR);
    HalideLibraryBuilder compileBuilder = new HalideLibraryBuilder(compileTarget);
    compileBuilder.setSrcs(
        ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("main.cpp"))));

    // First, make sure the compile step doesn't include the extra flags.
    TargetGraph targetGraph = TargetGraphFactory.newInstance(compileBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    HalideCompile compile =
        (HalideCompile) compileBuilder.build(graphBuilder, filesystem, targetGraph);

    ImmutableList<Step> buildSteps =
        compile.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());
    HalideCompilerStep compilerStep = (HalideCompilerStep) buildSteps.get(2);
    ImmutableList<String> shellCommand =
        compilerStep.getShellCommandInternal(TestExecutionContext.newInstance());
    assertThat(shellCommand, not(hasItems("--test-flag", "test-value")));

    // Next verify that the shell command picks up on the extra compiler flags.
    compileBuilder.setCompilerInvocationFlags(extraCompilerFlags);
    targetGraph = TargetGraphFactory.newInstance(compileBuilder.build());
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    compile = (HalideCompile) compileBuilder.build(graphBuilder, filesystem, targetGraph);

    buildSteps =
        compile.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());
    compilerStep = (HalideCompilerStep) buildSteps.get(2);
    shellCommand = compilerStep.getShellCommandInternal(TestExecutionContext.newInstance());
    assertThat(shellCommand, hasItems("--test-flag", "test-value", "test_macro_expansion"));
  }

  @Test
  public void functionNameOverride() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Set up a #halide-compile rule, then check its build steps.
    String defaultName = "default-halide-name";
    BuildTarget compileTarget =
        BuildTargetFactory.newInstance("//:" + defaultName)
            .withFlavors(HalideLibraryDescription.HALIDE_COMPILE_FLAVOR);
    HalideLibraryBuilder compileBuilder = new HalideLibraryBuilder(compileTarget);
    compileBuilder.setSrcs(
        ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("main.cpp"))));

    // First, make sure the compile step passes the rulename "default-halide-name"
    // for the function output name.
    TargetGraph targetGraph = TargetGraphFactory.newInstance(compileBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    HalideCompile compile =
        (HalideCompile) compileBuilder.build(graphBuilder, filesystem, targetGraph);

    ImmutableList<Step> buildSteps =
        compile.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());
    HalideCompilerStep compilerStep = (HalideCompilerStep) buildSteps.get(2);
    ImmutableList<String> shellCommand =
        compilerStep.getShellCommandInternal(TestExecutionContext.newInstance());
    assertThat(shellCommand, hasItem(defaultName));

    // Next verify that the shell command picks up on the override name.
    String overrideName = "override-halide-name";
    compileBuilder.setFunctionNameOverride(overrideName);
    targetGraph = TargetGraphFactory.newInstance(compileBuilder.build());
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    compile = (HalideCompile) compileBuilder.build(graphBuilder, filesystem, targetGraph);

    buildSteps =
        compile.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());
    compilerStep = (HalideCompilerStep) buildSteps.get(2);
    shellCommand = compilerStep.getShellCommandInternal(TestExecutionContext.newInstance());
    assertThat(shellCommand, hasItem(overrideName));
    assertThat(shellCommand, not(hasItem(defaultName)));
  }
}
