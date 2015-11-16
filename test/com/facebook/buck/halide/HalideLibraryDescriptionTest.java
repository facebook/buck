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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cxx.CxxBinary;
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
import com.facebook.buck.model.BuildTargets;
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
  public void testIsHalideCompilerTarget() {
    BuildTarget target = BuildTargetFactory.newInstance("//:foo");
    assertFalse(HalideLibraryDescription.isHalideCompilerTarget(target));
    target = BuildTargetFactory.newInstance("//:foo#halide-compiler");
    assertTrue(HalideLibraryDescription.isHalideCompilerTarget(target));
  }

  @Test
  public void testCreateBuildRule() {
    // Set up a #halide-compiler rule, then set up a halide_library rule, and
    // check that the library rule depends on the compiler rule.
    BuildTarget compilerTarget = BuildTargetFactory
      .newInstance("//:rule")
      .withFlavors(HalideLibraryDescription.HALIDE_COMPILER_FLAVOR);
    BuildTarget libTarget = BuildTargetFactory.newInstance("//:rule");

    BuildRuleResolver resolver = new BuildRuleResolver();
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
    CxxBinary compiler = (CxxBinary) compilerBuilder.build(
      resolver,
      filesystem,
      targetGraph);
    HalideLibrary lib = (HalideLibrary) libBuilder.build(
      resolver,
      filesystem,
      targetGraph);
    // Check that we picked up the implicit dependency on the #halide-compiler
    // version of the rule.
    assertEquals(lib.getDeps(), ImmutableSortedSet.<BuildRule>of(compiler));

    // Check that the library rule has the correct preprocessor input.
    CxxPlatform cxxPlatform = CxxLibraryBuilder.createDefaultPlatform();
    String headerName = "rule.h";
    Path headerPath = BuildTargets.getGenPath(libTarget, "%s/" + headerName);
    Path headerRoot =
      CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
        libTarget,
        cxxPlatform.getFlavor(),
        HeaderVisibility.PUBLIC);
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
              new BuildTargetSourcePath(libTarget, headerPath))
            .putFullNameToPathMap(
              headerRoot.resolve(headerName),
              new BuildTargetSourcePath(libTarget, headerPath))
            .build())
        .addSystemIncludeRoots(headerRoot)
        .build(),
      lib.getCxxPreprocessorInput(
        targetGraph,
        cxxPlatform,
        HeaderVisibility.PUBLIC));

    // Check that the library rule has the correct native linkable input.
    NativeLinkableInput input = lib.getNativeLinkableInput(
        targetGraph,
        cxxPlatform,
        Linker.LinkableDepType.STATIC);
    BuildRule buildRule =
        FluentIterable.from(input.getArgs())
            .transformAndConcat(Arg.getDepsFunction(new SourcePathResolver(resolver)))
            .get(0);
    assertTrue(buildRule instanceof HalideLibrary);
  }
}
