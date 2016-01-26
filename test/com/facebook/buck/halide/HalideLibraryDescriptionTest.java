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
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxHeaders;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

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
        new BuildRuleResolver(targetGraph, new BuildTargetNodeToBuildRuleTransformer());
    HalideLibrary lib = (HalideLibrary) libBuilder.build(
        resolver,
        filesystem,
        targetGraph);

    // Check that the library rule has the correct preprocessor input.
    CxxPlatform cxxPlatform = CxxLibraryBuilder.createDefaultPlatform();
    String headerName = "rule.h";
    Path headerRoot =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            libTarget,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PUBLIC);
    BuildTarget flavoredLibTarget = libTarget.withFlavors(
        HalideLibraryDescription.HALIDE_COMPILE_FLAVOR,
        cxxPlatform.getFlavor());
    Path headerPath = HalideCompile.headerOutputPath(flavoredLibTarget);
    assertEquals(
        CxxPreprocessorInput.builder()
            .addRules(
                CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                    libTarget,
                    cxxPlatform.getFlavor(),
                    HeaderVisibility.PUBLIC))
            .setIncludes(
                CxxHeaders.builder()
                    .putNameToPathMap(
                        Paths.get(headerName),
                        new BuildTargetSourcePath(
                            flavoredLibTarget,
                            headerPath))
                    .putFullNameToPathMap(
                        headerRoot.resolve(headerName),
                        new BuildTargetSourcePath(
                            flavoredLibTarget,
                            headerPath))
                    .build())
            .addSystemIncludeRoots(headerRoot)
            .build(),
        lib.getCxxPreprocessorInput(
            cxxPlatform,
            HeaderVisibility.PUBLIC));

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
}
